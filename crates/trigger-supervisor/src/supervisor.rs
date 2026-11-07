// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The supervisor of one trigger instance: a deterministic state machine with no I/O of its own except
//! its [`Store`]. Executors feed it frames, acknowledgements, leases and clock ticks, and carry out the
//! [`Action`]s it returns (spawn, write a frame, terminate, kill).
//!
//! It implements the executor side of `docs/specs/trigger_delivery.tla`:
//!
//! | Model action | Here |
//! |---|---|
//! | `Start` | [`Supervisor::tick`] spawns when the instance should run and holds a valid lease |
//! | `Emit` | an `EMIT` frame admitted by [`Supervisor::on_frame`] (queued, not yet durable) |
//! | `Durable` / `LossRecord` (T1 / T3) | [`Supervisor::pump`] or [`Supervisor::persist`]: one append + fsync |
//! | absence of `Durable` (T2) | a `RETRYABLE_REJECTION`; nothing is persisted and the emit holds the barrier |
//! | `Halt` (T4) | a terminal rejection under `HALT`: the halt condition is persisted, no record |
//! | `LocalCheckpoint` (T5) | the contiguous barrier commits the newest position at or below the watermark |
//! | `Crash` | dropping the supervisor and [`Supervisor::recover`]ing from the store |
//! | `LeaseLost` | lease expiry, revocation or a newer epoch stops the process |
//! | `Send` | [`Supervisor::sync_events`] |
//! | `ReceiveAck` (T8, T10) | [`Supervisor::on_sync_ack`]: truncate (fsync), then translate to `ACCEPTED_UPTO` |
//! | `Reinstall` (T14) | [`Supervisor::resume_from_control`] |
//! | `Upgrade` (T12) | [`Supervisor::discard_checkpoints`] / [`Supervisor::migrate_checkpoints`] |
//! | `Remove` (T13) | [`Supervisor::begin_removal`] and [`Supervisor::finish_removal`] |
//!
//! Sequence namespaces (plan 6.2.1): `emitSeq` exists only in the process's memory and in stdio frames;
//! every durable value and everything sent to Control uses `(leaseEpoch, outboxSeq)`.

use crate::frames::{Inbound, Outbound};
use crate::restart::{Random, RestartBudget, RestartDecision};
use crate::store::{
    CheckpointLevel, EpochMark, Fence, HaltRecord, HaltSide, OutboxEntry, Store, StoreError,
    StoredCheckpoint,
};
use crate::time::rfc3339;
use olo_toolgate_contracts::v2::{
    TriggerCheckpointFrame, TriggerCloudEvent, TriggerCondition, TriggerConditionStatus,
    TriggerConditionType, TriggerCounters, TriggerDescriptor, TriggerDesiredInstance,
    TriggerEmitFrame, TriggerEmitStatus, TriggerEventKind, TriggerHealthStatus,
    TriggerInstanceReport, TriggerMode, TriggerOutboxRecord, TriggerOutboxStatus, TriggerOverflow,
    TriggerPhase, TriggerRevocationReason, TriggerSourceReplay, TriggerStartFrame, TriggerSyncAck,
    TriggerTerminalRejectionPolicy,
};
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, VecDeque};
use std::fmt;

pub const LOSS_RECORD_TYPE: &str = "io.ololabs.toolgate.trigger.event.rejected.v1";
pub const MAX_EVENT_DATA_BYTES: u64 = 16 * 1024;
pub const START_TIMEOUT_MS: u64 = 60_000;
pub const HEARTBEAT_INTERVAL_MS: u64 = 10_000;
pub const MISSED_HEARTBEATS: u64 = 3;
pub const UNHEALTHY_RESTART_MS: u64 = 5 * 60_000;
pub const DEFAULT_EMIT_TIMEOUT_MS: u64 = 30_000;
pub const MAX_STOP_GRACE_MS: u64 = 30_000;
pub const KILL_AFTER_TERMINATE_MS: u64 = 5_000;
pub const REMOVAL_DRAIN_MS: u64 = 24 * 60 * 60_000;
const HOUR_MS: f64 = 3_600_000.0;

/// Outbox bounds (plan 6.7).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OutboxBounds {
    pub max_records: u64,
    pub max_bytes: u64,
}

impl OutboxBounds {
    pub const CLIENT: Self = Self {
        max_records: 1_000,
        max_bytes: 1024 * 1024,
    };
    pub const TOOL_HOST: Self = Self {
        max_records: 10_000,
        max_bytes: 8 * 1024 * 1024,
    };

    /// Loss records take a reserved slice above the bounds, so a full outbox can still resolve a rejection.
    fn reserve_records(self) -> u64 {
        (self.max_records / 10).max(1)
    }

    fn reserve_bytes(self) -> u64 {
        (self.max_bytes / 10).max(4096)
    }
}

/// What the supervisor needs to know about one instance.
#[derive(Debug, Clone)]
pub struct InstanceConfig {
    pub instance_id: String,
    pub trigger_id: String,
    pub config_revision: u64,
    /// CloudEvent `source`: `toolgate:tenant:<tenant>:trigger-instance:<instance>`.
    pub source: String,
    pub event_type: String,
    pub dataschema: Option<String>,
    pub event_schema: Value,
    pub mode: TriggerMode,
    pub policy: TriggerTerminalRejectionPolicy,
    pub overflow: TriggerOverflow,
    pub source_replay: TriggerSourceReplay,
    pub checkpoint_schema_version: u64,
    pub max_event_bytes: u64,
    pub max_checkpoint_bytes: u64,
    pub max_events_per_hour: u64,
    pub emit_timeout_ms: u64,
    pub stop_grace_ms: u64,
    pub resync_interval_ms: Option<u64>,
    pub outbox: OutboxBounds,
    pub config: BTreeMap<String, Value>,
    pub globals: Option<BTreeMap<String, Value>>,
    pub secret_handles: Option<Vec<String>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConfigError {
    DropPolicyNeedsSkipAndRecord,
    InvalidEventSchema(String),
    TriggerMismatch,
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::DropPolicyNeedsSkipAndRecord => write!(
                f,
                "a DROP_* overflow policy requires onTerminalRejection SKIP_AND_RECORD"
            ),
            Self::InvalidEventSchema(detail) => {
                write!(f, "the signed eventSchema does not compile: {detail}")
            }
            Self::TriggerMismatch => write!(
                f,
                "the desired instance names another trigger than the descriptor"
            ),
        }
    }
}

impl std::error::Error for ConfigError {}

impl InstanceConfig {
    /// Builds the configuration from the signed descriptor and one desired instance.
    pub fn from_descriptor(
        tenant_id: &str,
        descriptor: &TriggerDescriptor,
        instance: &TriggerDesiredInstance,
        outbox: OutboxBounds,
    ) -> Result<Self, ConfigError> {
        if descriptor.trigger_id != instance.trigger_id {
            return Err(ConfigError::TriggerMismatch);
        }
        let name = descriptor
            .trigger_id
            .rsplit('/')
            .next()
            .unwrap_or(&descriptor.trigger_id);
        Ok(Self {
            instance_id: instance.instance_id.clone(),
            trigger_id: descriptor.trigger_id.clone(),
            config_revision: instance.config_revision,
            source: format!(
                "toolgate:tenant:{tenant_id}:trigger-instance:{}",
                instance.instance_id
            ),
            event_type: descriptor.event_type.clone(),
            dataschema: Some(format!(
                "toolgate:package:{}:trigger:{name}:event",
                instance.package_digest
            )),
            event_schema: Value::Object(
                descriptor
                    .event_schema
                    .clone()
                    .into_iter()
                    .collect::<Map<_, _>>(),
            ),
            mode: descriptor.mode.clone(),
            policy: descriptor.on_terminal_rejection.clone(),
            overflow: descriptor.overflow.clone(),
            source_replay: descriptor.source_replay.clone(),
            checkpoint_schema_version: descriptor.checkpoint_schema_version,
            max_event_bytes: descriptor.limits.max_event_bytes.min(MAX_EVENT_DATA_BYTES),
            max_checkpoint_bytes: descriptor.limits.max_checkpoint_bytes,
            max_events_per_hour: descriptor.limits.max_events_per_hour,
            emit_timeout_ms: descriptor
                .limits
                .emit_timeout_ms
                .unwrap_or(DEFAULT_EMIT_TIMEOUT_MS),
            stop_grace_ms: descriptor.limits.stop_grace_ms.min(MAX_STOP_GRACE_MS),
            resync_interval_ms: descriptor.schedule.resync_interval_ms,
            outbox,
            config: instance.config.clone(),
            globals: None,
            secret_handles: None,
        })
    }

    fn validate(&self) -> Result<jsonschema::Validator, ConfigError> {
        if self.overflow != TriggerOverflow::Block
            && self.policy != TriggerTerminalRejectionPolicy::SkipAndRecord
        {
            return Err(ConfigError::DropPolicyNeedsSkipAndRecord);
        }
        jsonschema::validator_for(&self.event_schema)
            .map_err(|e| ConfigError::InvalidEventSchema(e.to_string()))
    }

    /// Longest position or checkpoint state accepted, in base64 characters.
    fn max_state_chars(&self) -> usize {
        (self.max_checkpoint_bytes.div_ceil(3) * 4) as usize
    }
}

/// The run lease the executor holds for the instance (plan 7).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Lease {
    pub epoch: u64,
    /// The executor stops the trigger at this time unless the lease is renewed.
    pub renew_deadline_ms: u64,
}

/// Work for the executor.
#[derive(Debug, Clone, PartialEq)]
pub enum Action {
    /// Start the trigger process with `TOOLGATE_PROTOCOL=2`, `TOOLGATE_EXECUTION_MODE=TRIGGER` and write
    /// this frame as its first stdin line. Report the outcome with [`Supervisor::on_exit`] if it fails.
    Spawn(TriggerStartFrame),
    /// Write a frame to the process's stdin.
    Send(Inbound),
    /// An unchanged tool-protocol frame (`LOG`, `BROKER_REQUEST`) for the executor's broker and logs.
    Forward { kind: String, frame: Value },
    /// SIGTERM (after the stop grace).
    Terminate,
    /// SIGKILL.
    Kill,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailReason {
    CrashLoop,
    TerminalRejection,
    StorageError,
    CheckpointSchemaMismatch,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Phase {
    /// Waiting for a lease or for [`Supervisor::run`].
    Pending,
    Backoff {
        until_ms: u64,
    },
    Starting,
    Running,
    Paused,
    Stopping,
    Stopped,
    Failed(FailReason),
    Removing {
        deadline_ms: u64,
    },
    Removed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SupervisorError {
    Config(ConfigError),
    Store(StoreError),
    /// The store had an indeterminate write; recover a new supervisor from it.
    Poisoned(String),
    /// The operation needs the process to be stopped.
    ProcessRunning,
}

impl fmt::Display for SupervisorError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Config(error) => error.fmt(f),
            Self::Store(error) => error.fmt(f),
            Self::Poisoned(detail) => write!(f, "store needs recovery: {detail}"),
            Self::ProcessRunning => write!(f, "the trigger process is running"),
        }
    }
}

impl std::error::Error for SupervisorError {}

impl From<StoreError> for SupervisorError {
    fn from(error: StoreError) -> Self {
        Self::Store(error)
    }
}

/// Events for one check-in (plan 8.2).
#[derive(Debug, Clone, PartialEq)]
pub struct SyncBatch {
    pub events: Vec<TriggerOutboxRecord>,
    /// Sendable records left out by the size budget.
    pub pending: u64,
}

/// T13 outcome.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RemovalReport {
    /// Event records not drained before removal: an `UnrecoverableGap` lower bound for `NON_REPLAYABLE`.
    pub undrained: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct Counters {
    emitted: u64,
    rejected: u64,
    dropped: u64,
    emit_timeouts: u64,
    restarts: u64,
    possible_loss_on_crash: u64,
}

#[derive(Debug, Clone)]
enum Payload {
    Event {
        subject: Option<String>,
        time: Option<String>,
        data: Value,
    },
    Loss {
        reason: String,
        data_digest: String,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum EmitState {
    /// Admitted and waiting for the outbox writer; `deadline_ms` applies only while the outbox is full.
    Queued {
        deadline_ms: u64,
    },
    RetryPending,
    Durable {
        epoch: u64,
        outbox_seq: u64,
    },
    Halted,
}

#[derive(Debug, Clone)]
struct Emit {
    id: String,
    position: Option<String>,
    state: EmitState,
    payload: Option<Payload>,
}

/// A source position waiting for the barrier: an `EMIT.position` or a `CHECKPOINT` frame.
#[derive(Debug, Clone)]
struct Candidate {
    after_emit_seq: u64,
    state: String,
    checkpoint_id: Option<u64>,
    after_outbox_seq: Option<u64>,
}

#[derive(Debug, Clone)]
struct Stopping {
    terminate_at_ms: u64,
    kill_at_ms: u64,
    terminated: bool,
    killed: bool,
}

#[derive(Debug, Clone)]
struct Process {
    epoch: u64,
    started_ms: u64,
    ready: bool,
    paused: bool,
    last_seen_ms: u64,
    unhealthy_since_ms: Option<u64>,
    stopping: Option<Stopping>,
    /// Why the supervisor killed it; the exit then counts as a failed run.
    failure: Option<&'static str>,
    /// Resyncing after a fence: nothing more is committed from this process.
    frozen: bool,
    next_emit_seq: u64,
    emits: BTreeMap<u64, Emit>,
    queue: VecDeque<u64>,
    /// Highest emitSeq with every earlier emit durable or loss-recorded (the local watermark).
    local_wm: u64,
    local_wm_max_seq: u64,
    /// Highest emitSeq with every earlier emit accepted by Control (`ACCEPTED_UPTO`).
    accepted_wm: u64,
    sent_accepted_wm: u64,
    /// The checkpoint the process started from, and whether it is already accepted (the floor of the
    /// ACCEPTED level for this process).
    start: Option<StoredCheckpoint>,
    base_accepted: bool,
    candidates: Vec<Candidate>,
    local_done: usize,
    accepted_done: usize,
    last_reconcile_ms: u64,
}

impl Process {
    fn new(
        epoch: u64,
        now_ms: u64,
        start: Option<StoredCheckpoint>,
        accepted: Option<&StoredCheckpoint>,
    ) -> Self {
        // Records of this epoch written before the start checkpoint are covered by it.
        let base_after_outbox_seq = start
            .as_ref()
            .filter(|c| c.lease_epoch == epoch)
            .map_or(0, |c| c.after_outbox_seq);
        let base_accepted = match &start {
            None => true,
            Some(start) => accepted.is_some_and(|a| same_position(a, start)),
        };
        Self {
            epoch,
            started_ms: now_ms,
            ready: false,
            paused: false,
            last_seen_ms: now_ms,
            unhealthy_since_ms: None,
            stopping: None,
            failure: None,
            frozen: false,
            next_emit_seq: 1,
            emits: BTreeMap::new(),
            queue: VecDeque::new(),
            local_wm: 0,
            local_wm_max_seq: base_after_outbox_seq,
            accepted_wm: 0,
            sent_accepted_wm: 0,
            start,
            base_accepted,
            candidates: Vec::new(),
            local_done: 0,
            accepted_done: 0,
            last_reconcile_ms: now_ms,
        }
    }

    fn last_candidate_after(&self) -> u64 {
        self.candidates.last().map_or(0, |c| c.after_emit_seq)
    }
}

fn same_position(a: &StoredCheckpoint, b: &StoredCheckpoint) -> bool {
    a.lease_epoch == b.lease_epoch
        && a.after_outbox_seq == b.after_outbox_seq
        && a.state == b.state
        && a.schema_version == b.schema_version
}

fn digest(value: &Value) -> String {
    let bytes = serde_json::to_vec(value).expect("JSON values serialize");
    let hash = Sha256::digest(&bytes);
    let mut out = String::from("sha256:");
    for byte in hash {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

/// The supervisor of one instance on one executor.
pub struct Supervisor<S: Store> {
    cfg: InstanceConfig,
    validator: jsonschema::Validator,
    store: S,
    random: Box<dyn Random>,
    entries: BTreeMap<(u64, u64), OutboxEntry>,
    entry_bytes: BTreeMap<(u64, u64), u64>,
    bytes: u64,
    epochs: BTreeMap<u64, EpochMark>,
    local: Option<StoredCheckpoint>,
    accepted: Option<StoredCheckpoint>,
    halt: Option<HaltRecord>,
    lease: Option<Lease>,
    compromised: bool,
    retry_later: BTreeMap<u64, (u64, u64)>,
    process: Option<Process>,
    phase: Phase,
    desired_running: bool,
    budget: RestartBudget,
    poisoned: Option<String>,
    actions: Vec<Action>,
    counters: Counters,
    backpressure: bool,
    events_rejected: bool,
    events_dropped: bool,
    author_health: Option<(TriggerHealthStatus, Option<String>)>,
    conditions: BTreeMap<
        &'static str,
        (
            TriggerConditionType,
            TriggerConditionStatus,
            String,
            Option<String>,
            u64,
        ),
    >,
    tokens: f64,
    tokens_at_ms: u64,
    last_event_ms: Option<u64>,
    last_reconcile_ms: Option<u64>,
}

impl<S: Store> Supervisor<S> {
    /// Recovers the instance from its store. A new store is empty; a store that is not this crate's current
    /// format, or violates S1, is refused and left untouched.
    pub fn recover(
        cfg: InstanceConfig,
        mut store: S,
        random: Box<dyn Random>,
        now_ms: u64,
    ) -> Result<Self, SupervisorError> {
        let validator = cfg.validate().map_err(SupervisorError::Config)?;
        let recovered = store.load()?;
        let mut supervisor = Self {
            tokens: cfg.max_events_per_hour as f64,
            cfg,
            validator,
            store,
            random,
            entries: BTreeMap::new(),
            entry_bytes: BTreeMap::new(),
            bytes: 0,
            epochs: recovered.epochs,
            local: recovered.local,
            accepted: recovered.accepted,
            phase: if recovered.halt.is_some() {
                Phase::Failed(FailReason::TerminalRejection)
            } else {
                Phase::Pending
            },
            halt: recovered.halt,
            lease: None,
            compromised: false,
            retry_later: BTreeMap::new(),
            process: None,
            desired_running: false,
            budget: RestartBudget::default(),
            poisoned: None,
            actions: Vec::new(),
            counters: Counters::default(),
            backpressure: false,
            events_rejected: false,
            events_dropped: false,
            author_health: None,
            conditions: BTreeMap::new(),
            tokens_at_ms: now_ms,
            last_event_ms: None,
            last_reconcile_ms: None,
        };
        supervisor.compromised = supervisor
            .epochs
            .values()
            .any(|m| m.fenced == Some(Fence::Compromised));
        for entry in recovered.entries {
            supervisor.insert_entry(entry);
        }
        supervisor.refresh(now_ms);
        Ok(supervisor)
    }

    // ------------------------------------------------------------------ accessors

    pub fn config(&self) -> &InstanceConfig {
        &self.cfg
    }

    pub fn phase(&self) -> Phase {
        self.phase
    }

    pub fn lease(&self) -> Option<Lease> {
        self.lease
    }

    pub fn local_checkpoint(&self) -> Option<&StoredCheckpoint> {
        self.local.as_ref()
    }

    pub fn accepted_checkpoint(&self) -> Option<&StoredCheckpoint> {
        self.accepted.as_ref()
    }

    pub fn halt(&self) -> Option<&HaltRecord> {
        self.halt.as_ref()
    }

    pub fn outbox(&self) -> impl Iterator<Item = &OutboxEntry> {
        self.entries.values()
    }

    pub fn epoch_marks(&self) -> &BTreeMap<u64, EpochMark> {
        &self.epochs
    }

    pub fn is_poisoned(&self) -> bool {
        self.poisoned.is_some()
    }

    pub fn has_process(&self) -> bool {
        self.process.is_some()
    }

    /// emitSeqs admitted and waiting for the outbox writer, oldest first.
    pub fn queued(&self) -> Vec<u64> {
        self.process
            .as_ref()
            .map(|p| p.queue.iter().copied().collect())
            .unwrap_or_default()
    }

    pub fn store(&self) -> &S {
        &self.store
    }

    /// Drains the work produced so far.
    pub fn take_actions(&mut self) -> Vec<Action> {
        std::mem::take(&mut self.actions)
    }

    // ------------------------------------------------------------------ lifecycle

    /// The instance's desired state is `Running`.
    pub fn run(&mut self, now_ms: u64) {
        self.desired_running = true;
        self.tick(now_ms);
    }

    /// Admin `RUN` after `Failed (CrashLoop)`, or a changed config revision: resets the restart budget.
    pub fn reset_crash_loop(&mut self, now_ms: u64) {
        self.budget.reset();
        if self.phase == Phase::Failed(FailReason::CrashLoop) {
            self.phase = Phase::Pending;
        }
        self.tick(now_ms);
    }

    /// Replaces the configuration (a new config revision or package). The process is not restarted here;
    /// the executor stops and runs the instance again.
    pub fn reconfigure(&mut self, cfg: InstanceConfig, now_ms: u64) -> Result<(), SupervisorError> {
        let validator = cfg.validate().map_err(SupervisorError::Config)?;
        if cfg.config_revision != self.cfg.config_revision {
            self.budget.reset();
            if self.phase == Phase::Failed(FailReason::CrashLoop) {
                self.phase = Phase::Pending;
            }
        }
        if self.phase == Phase::Failed(FailReason::CheckpointSchemaMismatch) {
            self.phase = Phase::Pending;
        }
        self.cfg = cfg;
        self.validator = validator;
        self.refresh(now_ms);
        Ok(())
    }

    /// Clears a halt after the trigger was fixed (a new package version and `Run`). The instance replays
    /// from its last committed checkpoint.
    pub fn clear_halt(&mut self, now_ms: u64) -> Result<(), SupervisorError> {
        self.check_usable()?;
        if self.halt.is_some() {
            self.persist_result(|s| s.store.write_halt(None))?;
            self.halt = None;
        }
        if self.phase == Phase::Failed(FailReason::TerminalRejection) {
            self.phase = Phase::Pending;
        }
        self.tick(now_ms);
        Ok(())
    }

    /// Stops the process gracefully and keeps it stopped.
    pub fn stop(&mut self, reason: &str, now_ms: u64) {
        self.desired_running = false;
        self.stop_process(reason, None, now_ms);
        if self.process.is_none() && matches!(self.phase, Phase::Pending | Phase::Backoff { .. }) {
            self.phase = Phase::Stopped;
        }
        self.refresh(now_ms);
    }

    pub fn pause(&mut self, now_ms: u64) {
        if let Some(process) = self.process.as_mut().filter(|p| p.stopping.is_none()) {
            process.paused = true;
            self.actions.push(Action::Send(Inbound::pause()));
            self.phase = Phase::Paused;
        }
        self.refresh(now_ms);
    }

    pub fn resume(&mut self, now_ms: u64) {
        if let Some(process) = self
            .process
            .as_mut()
            .filter(|p| p.paused && p.stopping.is_none())
        {
            process.paused = false;
            self.actions.push(Action::Send(Inbound::resume()));
            self.phase = if process.ready {
                Phase::Running
            } else {
                Phase::Starting
            };
        }
        self.refresh(now_ms);
    }

    /// Asks the trigger to run `reconcile()` (start, failover, resync timer, dropped watch notification).
    pub fn reconcile(&mut self, now_ms: u64) {
        if let Some(process) = self
            .process
            .as_mut()
            .filter(|p| p.ready && p.stopping.is_none())
        {
            process.last_reconcile_ms = now_ms;
            self.last_reconcile_ms = Some(now_ms);
            self.actions.push(Action::Send(Inbound::reconcile()));
        }
    }

    /// Grants or renews the run lease. A newer epoch ends the running process's epoch: it is stopped and
    /// started again under the new epoch, without counting as a failure.
    pub fn grant_lease(&mut self, lease: Lease, now_ms: u64) {
        if self.lease.is_some_and(|held| lease.epoch < held.epoch) {
            return;
        }
        if self
            .epochs
            .get(&lease.epoch)
            .is_some_and(|m| m.fenced.is_some())
        {
            return;
        }
        self.lease = Some(lease);
        if self
            .process
            .as_ref()
            .is_some_and(|p| p.epoch != lease.epoch)
        {
            self.stop_process("LeaseEpochChanged", None, now_ms);
        }
        self.tick(now_ms);
    }

    fn lease_valid(&self, epoch: u64, now_ms: u64) -> bool {
        self.lease
            .is_some_and(|l| l.epoch == epoch && now_ms < l.renew_deadline_ms)
    }

    fn try_start(&mut self, now_ms: u64) {
        if !self.desired_running
            || self.process.is_some()
            || self.poisoned.is_some()
            || self.halt.is_some()
        {
            return;
        }
        match self.phase {
            Phase::Pending | Phase::Stopped => {}
            Phase::Backoff { until_ms } if until_ms <= now_ms => {}
            _ => return,
        }
        let Some(lease) = self.lease.filter(|l| now_ms < l.renew_deadline_ms) else {
            return;
        };
        // M: a process never starts on a checkpoint written by another checkpoint schema.
        let version = self.cfg.checkpoint_schema_version;
        if [&self.local, &self.accepted]
            .into_iter()
            .flatten()
            .any(|c| c.schema_version != version)
        {
            self.phase = Phase::Failed(FailReason::CheckpointSchemaMismatch);
            return;
        }
        let start = TriggerStartFrame {
            kind: "TRIGGER_START".into(),
            trigger_id: self.cfg.trigger_id.clone(),
            instance_id: self.cfg.instance_id.clone(),
            config_revision: self.cfg.config_revision,
            lease_epoch: lease.epoch,
            config: self.cfg.config.clone(),
            globals: self.cfg.globals.clone(),
            secret_handles: self.cfg.secret_handles.clone(),
            checkpoint: self.local.as_ref().map(|c| c.state.clone()),
        };
        self.process = Some(Process::new(
            lease.epoch,
            now_ms,
            self.local.clone(),
            self.accepted.as_ref(),
        ));
        self.events_rejected = false;
        self.events_dropped = false;
        self.author_health = None;
        self.phase = Phase::Starting;
        self.actions.push(Action::Spawn(start));
    }

    fn stop_process(&mut self, reason: &str, failure: Option<&'static str>, now_ms: u64) {
        let grace = self.cfg.stop_grace_ms;
        let Some(process) = self.process.as_mut() else {
            return;
        };
        if failure.is_some() && process.failure.is_none() {
            process.failure = failure;
        }
        if process.stopping.is_none() {
            process.stopping = Some(Stopping {
                terminate_at_ms: now_ms + grace,
                kill_at_ms: now_ms + grace + KILL_AFTER_TERMINATE_MS,
                terminated: false,
                killed: false,
            });
            self.actions
                .push(Action::Send(Inbound::stop(reason, grace)));
            if !matches!(self.phase, Phase::Removing { .. } | Phase::Failed(_)) {
                self.phase = Phase::Stopping;
            }
        }
    }

    fn kill_process(&mut self, failure: &'static str) {
        let Some(process) = self.process.as_mut() else {
            return;
        };
        if process.failure.is_none() {
            process.failure = Some(failure);
        }
        let stopping = process.stopping.get_or_insert(Stopping {
            terminate_at_ms: 0,
            kill_at_ms: 0,
            terminated: true,
            killed: false,
        });
        if !stopping.killed {
            stopping.killed = true;
            stopping.terminated = true;
            self.actions.push(Action::Kill);
        }
    }

    /// The process exited (or could not be spawned). Unresolved emits die with it: they were never durable,
    /// so the checkpoint is still before them and the source re-delivers them.
    pub fn on_exit(&mut self, now_ms: u64) {
        let Some(process) = self.process.take() else {
            return;
        };
        self.budget.ran(process.started_ms, now_ms);
        let unresolved = process
            .emits
            .values()
            .filter(|e| !matches!(e.state, EmitState::Durable { .. }))
            .count() as u64;
        let expected = process.stopping.is_some() && process.failure.is_none();
        if !expected {
            if self.cfg.source_replay == TriggerSourceReplay::NonReplayable {
                self.counters.possible_loss_on_crash += unresolved;
            }
            self.counters.restarts += 1;
        }
        self.phase = match self.phase {
            Phase::Removing { .. } | Phase::Removed | Phase::Failed(_) => self.phase,
            _ if self.halt.is_some() => Phase::Failed(FailReason::TerminalRejection),
            _ if expected => {
                if self.desired_running {
                    Phase::Pending
                } else {
                    Phase::Stopped
                }
            }
            _ => match self.budget.failed(now_ms, self.random.as_mut()) {
                RestartDecision::RetryAt(until_ms) => Phase::Backoff { until_ms },
                RestartDecision::CrashLoop => Phase::Failed(FailReason::CrashLoop),
            },
        };
        self.tick(now_ms);
    }

    // ------------------------------------------------------------------ frames

    /// Handles one stdout frame of the running process.
    pub fn on_frame(&mut self, frame: Outbound, now_ms: u64) {
        let Some(process) = self.process.as_mut() else {
            return;
        };
        process.last_seen_ms = now_ms;
        match frame {
            Outbound::Ready(_) => {
                process.ready = true;
                if process.stopping.is_none() && !process.paused {
                    self.phase = Phase::Running;
                }
            }
            Outbound::Heartbeat(_) | Outbound::Exit(_) => {}
            Outbound::Health(health) => {
                process.unhealthy_since_ms = match health.status {
                    TriggerHealthStatus::Unhealthy => {
                        Some(process.unhealthy_since_ms.unwrap_or(now_ms))
                    }
                    _ => None,
                };
                self.author_health = match health.status {
                    TriggerHealthStatus::Healthy => None,
                    status => Some((status, health.reason)),
                };
            }
            Outbound::Emit(emit) => self.on_emit(emit, now_ms),
            Outbound::Checkpoint(checkpoint) => self.on_checkpoint(checkpoint),
            Outbound::Passthrough { kind, frame } => {
                self.actions.push(Action::Forward { kind, frame })
            }
        }
        self.refresh(now_ms);
    }

    fn reply(
        &mut self,
        emit_seq: u64,
        status: TriggerEmitStatus,
        reason: Option<&str>,
        outbox_seq: Option<u64>,
    ) {
        self.actions.push(Action::Send(Inbound::emit_ack(
            emit_seq, status, reason, outbox_seq,
        )));
    }

    fn retryable(&mut self, emit_seq: u64, reason: &str) {
        if let Some(emit) = self
            .process
            .as_mut()
            .and_then(|p| p.emits.get_mut(&emit_seq))
        {
            emit.state = EmitState::RetryPending;
            emit.payload = None;
        }
        if let Some(process) = self.process.as_mut() {
            process.queue.retain(|s| *s != emit_seq);
        }
        self.reply(
            emit_seq,
            TriggerEmitStatus::RetryableRejection,
            Some(reason),
            None,
        );
    }

    fn on_emit(&mut self, frame: TriggerEmitFrame, now_ms: u64) {
        let max_state = self.cfg.max_state_chars();
        let Some(process) = self.process.as_mut() else {
            return;
        };
        let seq = frame.emit_seq;
        let id = if seq == process.next_emit_seq {
            if frame.position.as_ref().is_some_and(|p| p.len() > max_state) {
                return self.kill_process("ProtocolViolation");
            }
            let id = frame.id.clone().unwrap_or_else(|| {
                format!(
                    "evt-{:016x}{:016x}",
                    self.random.next_u64(),
                    self.random.next_u64()
                )
            });
            process.next_emit_seq += 1;
            if let Some(position) = &frame.position {
                process.candidates.push(Candidate {
                    after_emit_seq: seq,
                    state: position.clone(),
                    checkpoint_id: None,
                    after_outbox_seq: None,
                });
            }
            process.emits.insert(
                seq,
                Emit {
                    id: id.clone(),
                    position: frame.position.clone(),
                    state: EmitState::RetryPending,
                    payload: None,
                },
            );
            id
        } else {
            // The SDK retries a retryable rejection with the same emitSeq and the same event id.
            match process.emits.get(&seq) {
                Some(emit)
                    if emit.state == EmitState::RetryPending
                        && frame.id.as_ref().is_none_or(|i| *i == emit.id) =>
                {
                    emit.id.clone()
                }
                _ => return self.kill_process("ProtocolViolation"),
            }
        };
        if process.stopping.is_some() || matches!(self.phase, Phase::Removing { .. }) {
            return self.retryable(seq, "Stopping");
        }
        if self.halt.is_some() {
            return self.retryable(seq, "Halted");
        }
        let epoch = process.epoch;
        if !self.lease_valid(epoch, now_ms)
            || self.epochs.get(&epoch).is_some_and(|m| m.fenced.is_some())
        {
            return self.retryable(seq, "LeaseExpired");
        }
        // Terminal classes: the signed schema and the size limit.
        let data_bytes = serde_json::to_vec(&frame.data).map_or(u64::MAX, |b| b.len() as u64);
        let envelope_ok = id.len() <= 256
            && frame
                .subject
                .as_ref()
                .is_none_or(|s| !s.is_empty() && s.len() <= 256);
        let terminal = if data_bytes > self.cfg.max_event_bytes {
            Some("EventTooLarge")
        } else if !envelope_ok {
            Some("InvalidEnvelope")
        } else if !self.validator.is_valid(&frame.data) {
            Some("SchemaInvalid")
        } else {
            None
        };
        if let Some(reason) = terminal {
            let digest = digest(&frame.data);
            return self.terminal(seq, reason, digest, now_ms);
        }
        // Retryable class: the rate limit.
        self.refill(now_ms);
        if self.tokens < 1.0 {
            return self.retryable(seq, "RateLimited");
        }
        self.tokens -= 1.0;
        let bytes = data_bytes + 512;
        if !self.has_capacity(bytes, false) && self.cfg.overflow != TriggerOverflow::Block {
            // A declared drop policy: a terminal rejection with a loss record (SKIP_AND_RECORD is mandatory).
            self.counters.dropped += 1;
            self.events_dropped = true;
            let digest = digest(&frame.data);
            return self.terminal(seq, "Dropped", digest, now_ms);
        }
        let deadline_ms = now_ms + self.cfg.emit_timeout_ms;
        let process = self.process.as_mut().expect("checked above");
        let emit = process.emits.get_mut(&seq).expect("inserted above");
        emit.state = EmitState::Queued { deadline_ms };
        emit.payload = Some(Payload::Event {
            subject: frame.subject,
            time: frame.time,
            data: frame.data,
        });
        process.queue.push_back(seq);
    }

    /// A terminal rejection: `HALT` (T4) or a loss record (T3).
    fn terminal(&mut self, seq: u64, reason: &str, data_digest: String, now_ms: u64) {
        self.counters.rejected += 1;
        let deadline_ms = now_ms + self.cfg.emit_timeout_ms;
        let process = self.process.as_mut().expect("terminal needs a process");
        let epoch = process.epoch;
        let emit = process.emits.get_mut(&seq).expect("terminal needs an emit");
        match self.cfg.policy {
            TriggerTerminalRejectionPolicy::Halt => {
                emit.state = EmitState::Halted;
                emit.payload = None;
                let halt = HaltRecord {
                    side: HaltSide::Executor,
                    lease_epoch: epoch,
                    event_id: emit.id.clone(),
                    reason: reason.into(),
                    position: emit.position.clone(),
                    outbox_seq: None,
                };
                process.queue.retain(|s| *s != seq);
                match self.store.write_halt(Some(&halt)) {
                    Ok(()) | Err(StoreError::NotWritten(_)) => {
                        // Without a durable condition the restarted process re-emits the event and halts again.
                        self.halt = Some(halt);
                        self.reply(
                            seq,
                            TriggerEmitStatus::TerminalRejection,
                            Some(reason),
                            None,
                        );
                        self.phase = Phase::Failed(FailReason::TerminalRejection);
                        self.stop_process("TerminalRejection", None, now_ms);
                    }
                    Err(error) => self.poison(error),
                }
            }
            TriggerTerminalRejectionPolicy::SkipAndRecord => {
                self.events_rejected = true;
                emit.state = EmitState::Queued { deadline_ms };
                emit.payload = Some(Payload::Loss {
                    reason: reason.into(),
                    data_digest,
                });
                process.queue.push_back(seq);
            }
        }
    }

    fn on_checkpoint(&mut self, frame: TriggerCheckpointFrame) {
        let max_state = self.cfg.max_state_chars();
        let Some(process) = self.process.as_mut() else {
            return;
        };
        let valid = frame.after_emit_seq < process.next_emit_seq
            && frame.after_emit_seq >= process.last_candidate_after()
            && frame.state.len() <= max_state
            && process
                .candidates
                .iter()
                .all(|c| c.checkpoint_id.is_none_or(|id| id < frame.checkpoint_id));
        if !valid {
            return self.kill_process("ProtocolViolation");
        }
        process.candidates.push(Candidate {
            after_emit_seq: frame.after_emit_seq,
            state: frame.state,
            checkpoint_id: Some(frame.checkpoint_id),
            after_outbox_seq: None,
        });
        self.advance();
    }

    fn refill(&mut self, now_ms: u64) {
        let capacity = self.cfg.max_events_per_hour as f64;
        let elapsed = now_ms.saturating_sub(self.tokens_at_ms) as f64;
        self.tokens = (self.tokens + elapsed * capacity / HOUR_MS).min(capacity);
        self.tokens_at_ms = now_ms;
    }

    fn has_capacity(&self, bytes: u64, loss: bool) -> bool {
        let bounds = self.cfg.outbox;
        let (records, max_bytes) = if loss {
            (
                bounds.max_records + bounds.reserve_records(),
                bounds.max_bytes + bounds.reserve_bytes(),
            )
        } else {
            (bounds.max_records, bounds.max_bytes)
        };
        (self.entries.len() as u64) < records && self.bytes + bytes <= max_bytes
    }

    // ------------------------------------------------------------------ outbox writer

    /// Appends every queued emit the outbox has room for, in emit order.
    pub fn pump(&mut self, now_ms: u64) {
        loop {
            let Some(seq) = self.process.as_ref().and_then(|p| p.queue.front().copied()) else {
                break;
            };
            if !self.persist(seq, now_ms) {
                break;
            }
        }
        self.refresh(now_ms);
    }

    /// Appends one queued emit (T1 for an event, T3 for a loss record). Returns false when it stays queued
    /// (the outbox is full) or the store failed. Executors use [`Supervisor::pump`]; tests call this to
    /// complete emits out of order.
    pub fn persist(&mut self, emit_seq: u64, now_ms: u64) -> bool {
        if self.poisoned.is_some() {
            return false;
        }
        let Some(process) = self.process.as_ref() else {
            return false;
        };
        let Some(emit) = process
            .emits
            .get(&emit_seq)
            .filter(|e| matches!(e.state, EmitState::Queued { .. }))
        else {
            return false;
        };
        let epoch = process.epoch;
        let payload = emit.payload.clone().expect("queued emits carry a payload");
        let (id, position) = (emit.id.clone(), emit.position.clone());
        let loss_reason = match &payload {
            Payload::Loss { reason, .. } => Some(reason.clone()),
            Payload::Event { .. } => None,
        };
        if self.epochs.get(&epoch).is_some_and(|m| m.fenced.is_some())
            || !self.lease_valid(epoch, now_ms)
        {
            self.retryable(emit_seq, "LeaseExpired");
            return false;
        }
        let outbox_seq = self.epochs.get(&epoch).map_or(0, |m| m.high_outbox_seq) + 1;
        let loss = matches!(payload, Payload::Loss { .. });
        let event = match payload {
            Payload::Event {
                subject,
                time,
                data,
            } => TriggerCloudEvent {
                specversion: "1.0".into(),
                id,
                source: self.cfg.source.clone(),
                r#type: self.cfg.event_type.clone(),
                subject,
                time: Some(time.unwrap_or_else(|| rfc3339(now_ms))),
                datacontenttype: "application/json".into(),
                dataschema: self.cfg.dataschema.clone(),
                data,
                tgleaseepoch: epoch,
                tgsequence: outbox_seq,
                tgkind: TriggerEventKind::Fired,
                traceparent: None,
            },
            Payload::Loss {
                reason,
                data_digest,
            } => {
                let mut data =
                    json!({ "id": id.clone(), "reason": reason, "dataDigest": data_digest });
                if let Some(position) = &position {
                    data["position"] = Value::String(position.clone());
                }
                TriggerCloudEvent {
                    specversion: "1.0".into(),
                    id: format!("{id}:rejected"),
                    source: self.cfg.source.clone(),
                    r#type: LOSS_RECORD_TYPE.into(),
                    subject: None,
                    time: Some(rfc3339(now_ms)),
                    datacontenttype: "application/json".into(),
                    dataschema: None,
                    data,
                    tgleaseepoch: epoch,
                    tgsequence: outbox_seq,
                    tgkind: TriggerEventKind::Rejected,
                    traceparent: None,
                }
            }
        };
        let entry = OutboxEntry {
            lease_epoch: epoch,
            outbox_seq,
            written_at_unix_ms: now_ms,
            event,
        };
        let bytes = serde_json::to_vec(&entry.event).map_or(0, |b| b.len() as u64);
        if !self.has_capacity(bytes, loss) {
            return false;
        }
        match self.store.append(&entry) {
            Ok(()) => {}
            Err(StoreError::NotWritten(_)) => {
                self.retryable(emit_seq, "LocalStorageError");
                return false;
            }
            Err(error) => {
                self.poison(error);
                return false;
            }
        }
        self.insert_entry(entry);
        let process = self.process.as_mut().expect("checked above");
        process.queue.retain(|s| *s != emit_seq);
        let emit = process.emits.get_mut(&emit_seq).expect("checked above");
        emit.state = EmitState::Durable { epoch, outbox_seq };
        emit.payload = None;
        match loss_reason {
            None => {
                self.counters.emitted += 1;
                self.last_event_ms = Some(now_ms);
                self.reply(
                    emit_seq,
                    TriggerEmitStatus::LocalDurable,
                    None,
                    Some(outbox_seq),
                );
            }
            Some(reason) => self.reply(
                emit_seq,
                TriggerEmitStatus::TerminalRejection,
                Some(&reason),
                Some(outbox_seq),
            ),
        }
        self.advance();
        true
    }

    fn insert_entry(&mut self, entry: OutboxEntry) {
        let key = (entry.lease_epoch, entry.outbox_seq);
        let bytes = serde_json::to_vec(&entry.event).map_or(0, |b| b.len() as u64);
        let mark = self.epochs.entry(entry.lease_epoch).or_insert(EpochMark {
            lease_epoch: entry.lease_epoch,
            high_outbox_seq: 0,
            acked_outbox_seq: 0,
            fenced: None,
        });
        mark.high_outbox_seq = mark.high_outbox_seq.max(entry.outbox_seq);
        self.bytes += bytes;
        self.entry_bytes.insert(key, bytes);
        self.entries.insert(key, entry);
    }

    fn remove_entries(&mut self, epoch: u64, upto: Option<u64>) {
        let keys: Vec<(u64, u64)> = self
            .entries
            .keys()
            .copied()
            .filter(|(ep, seq)| *ep == epoch && upto.is_none_or(|u| *seq <= u))
            .collect();
        for key in keys {
            self.entries.remove(&key);
            self.bytes -= self.entry_bytes.remove(&key).unwrap_or(0);
        }
    }

    // ------------------------------------------------------------------ the barrier

    fn acked(&self, epoch: u64) -> u64 {
        self.epochs
            .get(&epoch)
            .filter(|m| m.fenced.is_none())
            .map_or(0, |m| m.acked_outbox_seq)
    }

    /// Every record at or before `(checkpoint epoch, afterOutboxSeq)` is acknowledged by Control, in every
    /// epoch, so every event before the checkpoint's position is accepted.
    fn promotable(&self, checkpoint: &StoredCheckpoint) -> bool {
        self.epochs.values().all(|mark| {
            if mark.lease_epoch < checkpoint.lease_epoch {
                mark.fenced.is_none() && mark.acked_outbox_seq >= mark.high_outbox_seq
            } else if mark.lease_epoch == checkpoint.lease_epoch {
                mark.fenced.is_none() && mark.acked_outbox_seq >= checkpoint.after_outbox_seq
            } else {
                true
            }
        }) && (checkpoint.after_outbox_seq == 0
            || self.epochs.contains_key(&checkpoint.lease_epoch))
    }

    /// Recomputes both watermarks and commits what they allow. Never advances a checkpoint past an emit
    /// that is not durable (LOCAL) or not accepted (ACCEPTED).
    fn advance(&mut self) {
        if self.poisoned.is_some() {
            return;
        }
        let version = self.cfg.checkpoint_schema_version;
        let acked: BTreeMap<u64, u64> = self
            .epochs
            .keys()
            .map(|ep| (*ep, self.acked(*ep)))
            .collect();
        let Some(process) = self.process.as_mut() else {
            self.promote_local();
            return;
        };
        if process.frozen {
            return;
        }
        // Local watermark: contiguous durable or loss-recorded emits.
        while let Some(Emit {
            state: EmitState::Durable { outbox_seq, .. },
            ..
        }) = process.emits.get(&(process.local_wm + 1))
        {
            process.local_wm_max_seq = process.local_wm_max_seq.max(*outbox_seq);
            process.local_wm += 1;
        }
        for candidate in &mut process.candidates[process.local_done..] {
            if candidate.after_emit_seq <= process.local_wm && candidate.after_outbox_seq.is_none()
            {
                candidate.after_outbox_seq = Some(process.local_wm_max_seq);
            }
        }
        // T5: the newest position at or below the local watermark.
        if let Some(index) = process.candidates[process.local_done..]
            .iter()
            .rposition(|c| c.after_emit_seq <= process.local_wm)
        {
            let index = process.local_done + index;
            let candidate = &process.candidates[index];
            let checkpoint = StoredCheckpoint {
                lease_epoch: process.epoch,
                after_outbox_seq: candidate.after_outbox_seq.expect("assigned above"),
                level: CheckpointLevel::Local,
                schema_version: version,
                state: candidate.state.clone(),
            };
            match self.store.write_checkpoint(&checkpoint) {
                Ok(()) => {
                    let process = self.process.as_mut().expect("present");
                    let ids: Vec<u64> = process.candidates[process.local_done..=index]
                        .iter()
                        .filter_map(|c| c.checkpoint_id)
                        .collect();
                    process.local_done = index + 1;
                    self.local = Some(checkpoint);
                    for id in ids {
                        self.actions.push(Action::Send(Inbound::checkpoint_ack(
                            id,
                            CheckpointLevel::Local.into(),
                        )));
                    }
                }
                Err(StoreError::NotWritten(_)) => {} // retried on the next tick
                Err(error) => return self.poison(error),
            }
        }
        // Accepted watermark: contiguous emits whose records Control acknowledged (T8 translation).
        let process = self.process.as_mut().expect("present");
        while let Some(Emit {
            state: EmitState::Durable { epoch, outbox_seq },
            ..
        }) = process.emits.get(&(process.accepted_wm + 1))
        {
            if acked.get(epoch).copied().unwrap_or(0) < *outbox_seq {
                break;
            }
            process.accepted_wm += 1;
        }
        if process.accepted_wm > process.sent_accepted_wm {
            process.sent_accepted_wm = process.accepted_wm;
            let upto = process.accepted_wm;
            self.actions
                .push(Action::Send(Inbound::accepted_up_to(upto)));
        }
        // The ACCEPTED level starts only once the process's start checkpoint itself is accepted.
        let process = self.process.as_ref().expect("present");
        if !process.base_accepted {
            let start = process
                .start
                .clone()
                .expect("a missing start checkpoint is always accepted");
            if !self.promotable(&start) {
                return self.compact();
            }
            let accepted = StoredCheckpoint {
                level: CheckpointLevel::Accepted,
                ..start
            };
            if !self
                .accepted
                .as_ref()
                .is_some_and(|a| same_position(a, &accepted))
            {
                match self.store.write_checkpoint(&accepted) {
                    Ok(()) => self.accepted = Some(accepted),
                    Err(StoreError::NotWritten(_)) => return,
                    Err(error) => return self.poison(error),
                }
            }
            self.process.as_mut().expect("present").base_accepted = true;
        }
        let process = self.process.as_ref().expect("present");
        let upto = process.accepted_wm;
        if let Some(index) = process.candidates[process.accepted_done..]
            .iter()
            .rposition(|c| c.after_emit_seq <= upto && c.after_outbox_seq.is_some())
        {
            let index = process.accepted_done + index;
            let candidate = &process.candidates[index];
            let checkpoint = StoredCheckpoint {
                lease_epoch: process.epoch,
                after_outbox_seq: candidate.after_outbox_seq.expect("filtered"),
                level: CheckpointLevel::Accepted,
                schema_version: version,
                state: candidate.state.clone(),
            };
            match self.store.write_checkpoint(&checkpoint) {
                Ok(()) => {
                    let process = self.process.as_mut().expect("present");
                    let ids: Vec<u64> = process.candidates[process.accepted_done..=index]
                        .iter()
                        .filter_map(|c| c.checkpoint_id)
                        .collect();
                    process.accepted_done = index + 1;
                    self.accepted = Some(checkpoint);
                    for id in ids {
                        self.actions.push(Action::Send(Inbound::checkpoint_ack(
                            id,
                            CheckpointLevel::Accepted.into(),
                        )));
                    }
                }
                Err(StoreError::NotWritten(_)) => {}
                Err(error) => return self.poison(error),
            }
        }
        self.compact();
    }

    /// With no process, a LOCAL checkpoint whose records are all acknowledged becomes the ACCEPTED one.
    fn promote_local(&mut self) {
        let Some(local) = self.local.clone() else {
            return;
        };
        if self
            .accepted
            .as_ref()
            .is_some_and(|a| same_position(a, &local))
            || !self.promotable(&local)
        {
            return;
        }
        let accepted = StoredCheckpoint {
            level: CheckpointLevel::Accepted,
            ..local
        };
        match self.store.write_checkpoint(&accepted) {
            Ok(()) => self.accepted = Some(accepted),
            Err(StoreError::NotWritten(_)) => {}
            Err(error) => self.poison(error),
        }
    }

    /// Forgets emits and positions both levels have passed; memory stays bounded by the unacknowledged outbox.
    fn compact(&mut self) {
        let Some(process) = self.process.as_mut() else {
            return;
        };
        let done = process.local_done.min(process.accepted_done);
        if done > 0 {
            process.candidates.drain(..done);
            process.local_done -= done;
            process.accepted_done -= done;
        }
        let floor = process.local_wm.min(process.accepted_wm);
        process.emits.retain(|seq, _| *seq > floor);
    }

    // ------------------------------------------------------------------ Control

    /// The records for the next check-in: every unacknowledged, sendable record, oldest first, within
    /// `max_bytes` (at least one record). Records held by a `retryLater` wait for its delay.
    pub fn sync_events(&self, max_bytes: u64, now_ms: u64) -> SyncBatch {
        let mut events = Vec::new();
        let mut used = 0;
        let mut pending = 0;
        for ((epoch, seq), entry) in &self.entries {
            let mark = self.epochs.get(epoch);
            if mark.is_some_and(|m| m.fenced.is_some()) {
                continue;
            }
            if self
                .retry_later
                .get(epoch)
                .is_some_and(|(from, until)| *seq >= *from && now_ms < *until)
            {
                pending += 1;
                continue;
            }
            let size = self.entry_bytes.get(&(*epoch, *seq)).copied().unwrap_or(0) + 64;
            if !events.is_empty() && used + size > max_bytes {
                pending += 1;
                continue;
            }
            used += size;
            events.push(TriggerOutboxRecord {
                instance_id: self.cfg.instance_id.clone(),
                event: entry.event.clone(),
            });
        }
        SyncBatch { events, pending }
    }

    /// Applies Control's `TriggerSyncAck` for this instance.
    pub fn on_sync_ack(&mut self, ack: &TriggerSyncAck, now_ms: u64) {
        if self.poisoned.is_some() {
            return;
        }
        // `leases` are applied by the executor through grant_lease: it derives the renew deadline from the
        // lease duration of its executor kind (plan 7) and verifies the lease token.
        let me = self.cfg.instance_id.clone();
        for acked in ack.acked.iter().flatten().filter(|a| a.instance_id == me) {
            self.on_acked(acked.lease_epoch, acked.outbox_seq);
        }
        for disposition in ack
            .dispositions
            .iter()
            .flatten()
            .filter(|d| d.instance_id == me)
        {
            self.counters.rejected += 1;
            match self.cfg.policy {
                TriggerTerminalRejectionPolicy::SkipAndRecord => self.events_rejected = true,
                TriggerTerminalRejectionPolicy::Halt => {
                    if self.halt.is_none() {
                        let halt = HaltRecord {
                            side: HaltSide::Control,
                            lease_epoch: disposition.lease_epoch,
                            event_id: disposition.id.clone(),
                            reason: disposition.reason.clone(),
                            position: None,
                            outbox_seq: Some(disposition.outbox_seq),
                        };
                        match self.store.write_halt(Some(&halt)) {
                            Ok(()) | Err(StoreError::NotWritten(_)) => self.halt = Some(halt),
                            Err(error) => return self.poison(error),
                        }
                        self.phase = Phase::Failed(FailReason::TerminalRejection);
                        self.stop_process("TerminalRejection", None, now_ms);
                    }
                }
            }
        }
        for retry in ack
            .retry_later
            .iter()
            .flatten()
            .filter(|r| r.instance_id == me)
        {
            self.retry_later.insert(
                retry.lease_epoch,
                (retry.from_outbox_seq, now_ms + retry.retry_after_ms),
            );
        }
        for revoked in ack.revoked.iter().flatten().filter(|r| r.instance_id == me) {
            if revoked.reason == TriggerRevocationReason::Compromised {
                self.compromise();
            }
            self.lease = None;
            self.stop_process("LeaseRevoked", None, now_ms);
        }
        for rejected in ack
            .rejected
            .iter()
            .flatten()
            .filter(|r| r.instance_id == me)
        {
            self.fence(rejected.lease_epoch, now_ms);
        }
        self.advance();
        self.tick(now_ms);
    }

    /// T8: truncate first (fsync), then translate. A failed truncation leaves the records to be re-sent and
    /// acknowledged again as duplicates.
    fn on_acked(&mut self, epoch: u64, outbox_seq: u64) {
        let Some(mark) = self.epochs.get(&epoch).cloned() else {
            return;
        };
        if mark.fenced.is_some()
            || outbox_seq <= mark.acked_outbox_seq
            || outbox_seq > mark.high_outbox_seq
        {
            return;
        }
        let next = EpochMark {
            acked_outbox_seq: outbox_seq,
            ..mark
        };
        match self.store.mark_epoch(&next) {
            Ok(()) => {
                self.remove_entries(epoch, Some(outbox_seq));
                self.epochs.insert(epoch, next);
                if self
                    .retry_later
                    .get(&epoch)
                    .is_some_and(|(from, _)| *from <= outbox_seq)
                {
                    self.retry_later.remove(&epoch);
                }
            }
            Err(StoreError::NotWritten(_)) => {}
            Err(error) => self.poison(error),
        }
    }

    /// T9 / T10: Control fenced an epoch. Its records are pruned unless the producer is distrusted.
    ///
    /// Pruning unaccepted records would leave the LOCAL checkpoint past events that no outbox holds any more
    /// (L), so the instance resyncs first: the process is stopped without committing anything further, and
    /// the LOCAL checkpoint falls back to the ACCEPTED one (or the beginning) before the records go. A crash
    /// in between only replays more.
    fn fence(&mut self, epoch: u64, now_ms: u64) {
        let fence = if self.compromised {
            Fence::Compromised
        } else {
            Fence::Pruned
        };
        let mark = self.epochs.get(&epoch).cloned().unwrap_or(EpochMark {
            lease_epoch: epoch,
            high_outbox_seq: 0,
            acked_outbox_seq: 0,
            fenced: None,
        });
        if mark.fenced.is_some() {
            return;
        }
        if fence == Fence::Pruned && mark.high_outbox_seq > mark.acked_outbox_seq {
            if let Some(process) = self.process.as_mut() {
                process.frozen = true;
            }
            self.stop_process("Resync", None, now_ms);
            let fallback = self.accepted.clone().map(|a| StoredCheckpoint {
                level: CheckpointLevel::Local,
                ..a
            });
            if fallback != self.local {
                let written = match &fallback {
                    Some(checkpoint) => self.store.write_checkpoint(checkpoint),
                    None => self.store.discard_checkpoint(CheckpointLevel::Local),
                };
                match written {
                    Ok(()) => self.local = fallback,
                    Err(StoreError::NotWritten(_)) => return,
                    Err(error) => return self.poison(error),
                }
            }
        }
        let next = EpochMark {
            fenced: Some(fence),
            ..mark
        };
        match self.store.mark_epoch(&next) {
            Ok(()) => {
                if fence == Fence::Pruned {
                    self.remove_entries(epoch, None);
                }
                self.epochs.insert(epoch, next);
            }
            Err(StoreError::NotWritten(_)) => return,
            Err(error) => return self.poison(error),
        }
        if self.lease.is_some_and(|l| l.epoch == epoch) {
            self.lease = None;
        }
        if self.process.as_ref().is_some_and(|p| p.epoch == epoch) {
            self.stop_process("Fenced", None, now_ms);
        }
    }

    /// Compromise revocation: every queued record is fenced and kept, never sent and never trusted.
    fn compromise(&mut self) {
        self.compromised = true;
        let epochs: Vec<EpochMark> = self
            .epochs
            .values()
            .filter(|m| m.fenced.is_none())
            .cloned()
            .collect();
        for mark in epochs {
            let next = EpochMark {
                fenced: Some(Fence::Compromised),
                ..mark
            };
            match self.store.mark_epoch(&next) {
                Ok(()) => {
                    self.epochs.insert(next.lease_epoch, next);
                }
                Err(StoreError::NotWritten(_)) => {}
                Err(error) => return self.poison(error),
            }
        }
    }

    // ------------------------------------------------------------------ recovery and lifecycle edges

    /// T14 (reinstall) and failover onto this executor: start from Control's last `ACCEPTED` checkpoint, or
    /// from the beginning when Control has none (I). Both levels are set to it, so it is accepted by
    /// construction. Queued records of older epochs stay in the outbox: Control drains or fences them.
    ///
    /// The executor calls this before [`Supervisor::run`] for a lease it received by failover or after a
    /// reinstall, and again after a crash until it returns `Ok`: an interrupted resume never runs the
    /// trigger from a stale local position.
    pub fn resume_from_control(
        &mut self,
        checkpoint: Option<StoredCheckpoint>,
        now_ms: u64,
    ) -> Result<(), SupervisorError> {
        self.check_usable()?;
        if self.process.is_some() {
            return Err(SupervisorError::ProcessRunning);
        }
        match checkpoint {
            Some(checkpoint) => {
                for level in [CheckpointLevel::Accepted, CheckpointLevel::Local] {
                    let copy = StoredCheckpoint {
                        level,
                        ..checkpoint.clone()
                    };
                    self.persist_result(|s| s.store.write_checkpoint(&copy))?;
                    match level {
                        CheckpointLevel::Local => self.local = Some(copy),
                        CheckpointLevel::Accepted => self.accepted = Some(copy),
                    }
                }
            }
            None => self.drop_checkpoints()?,
        }
        self.refresh(now_ms);
        Ok(())
    }

    fn drop_checkpoints(&mut self) -> Result<(), SupervisorError> {
        for level in [CheckpointLevel::Accepted, CheckpointLevel::Local] {
            self.persist_result(|s| s.store.discard_checkpoint(level))?;
            match level {
                CheckpointLevel::Local => self.local = None,
                CheckpointLevel::Accepted => self.accepted = None,
            }
        }
        Ok(())
    }

    /// T12 discard: the new package cannot migrate its checkpoint, so it starts from the beginning and runs
    /// a full `reconcile()`. The process must be stopped.
    pub fn discard_checkpoints(&mut self, now_ms: u64) -> Result<(), SupervisorError> {
        self.check_usable()?;
        if self.process.is_some() {
            return Err(SupervisorError::ProcessRunning);
        }
        self.drop_checkpoints()?;
        if self.phase == Phase::Failed(FailReason::CheckpointSchemaMismatch) {
            self.phase = Phase::Pending;
        }
        self.refresh(now_ms);
        Ok(())
    }

    /// T12 migration: `migrate` is the package's `migrateCheckpoint(old) → new`; each checkpoint is rewritten
    /// with `schema_version`. Until every level is rewritten, the instance refuses to start (M).
    pub fn migrate_checkpoints(
        &mut self,
        schema_version: u64,
        migrate: impl Fn(&str) -> String,
        now_ms: u64,
    ) -> Result<(), SupervisorError> {
        self.check_usable()?;
        if self.process.is_some() {
            return Err(SupervisorError::ProcessRunning);
        }
        for level in [CheckpointLevel::Accepted, CheckpointLevel::Local] {
            let current = match level {
                CheckpointLevel::Local => self.local.clone(),
                CheckpointLevel::Accepted => self.accepted.clone(),
            };
            let Some(current) = current.filter(|c| c.schema_version != schema_version) else {
                continue;
            };
            let next = StoredCheckpoint {
                schema_version,
                state: migrate(&current.state),
                ..current
            };
            self.persist_result(|s| s.store.write_checkpoint(&next))?;
            match level {
                CheckpointLevel::Local => self.local = Some(next),
                CheckpointLevel::Accepted => self.accepted = Some(next),
            }
        }
        self.refresh(now_ms);
        Ok(())
    }

    /// T13: stop the process and drain the outbox for up to 24 hours.
    pub fn begin_removal(&mut self, now_ms: u64) {
        self.desired_running = false;
        self.phase = Phase::Removing {
            deadline_ms: now_ms + REMOVAL_DRAIN_MS,
        };
        self.stop_process("Removed", None, now_ms);
        self.refresh(now_ms);
    }

    /// T13: once the outbox is drained (or the drain deadline passed) and the process is gone, delete local
    /// state and report the records that were not drained. Removal never discards a record silently.
    pub fn finish_removal(
        &mut self,
        now_ms: u64,
    ) -> Result<Option<RemovalReport>, SupervisorError> {
        self.check_usable()?;
        let Phase::Removing { deadline_ms } = self.phase else {
            return Ok(None);
        };
        let sendable = self
            .entries
            .keys()
            .any(|(ep, _)| self.epochs.get(ep).is_none_or(|m| m.fenced.is_none()));
        if self.process.is_some() || (sendable && now_ms < deadline_ms) {
            return Ok(None);
        }
        let undrained = self
            .entries
            .values()
            .filter(|e| !e.is_loss_record())
            .count() as u64;
        self.persist_result(|s| s.store.destroy())?;
        self.entries.clear();
        self.entry_bytes.clear();
        self.bytes = 0;
        self.epochs.clear();
        self.local = None;
        self.accepted = None;
        self.halt = None;
        self.lease = None;
        self.phase = Phase::Removed;
        self.refresh(now_ms);
        Ok(Some(RemovalReport { undrained }))
    }

    fn check_usable(&self) -> Result<(), SupervisorError> {
        match &self.poisoned {
            Some(detail) => Err(SupervisorError::Poisoned(detail.clone())),
            None => Ok(()),
        }
    }

    fn persist_result(
        &mut self,
        write: impl FnOnce(&mut Self) -> Result<(), StoreError>,
    ) -> Result<(), SupervisorError> {
        match write(self) {
            Ok(()) => Ok(()),
            Err(StoreError::NotWritten(detail)) => {
                Err(SupervisorError::Store(StoreError::NotWritten(detail)))
            }
            Err(error) => {
                self.poison(error.clone());
                Err(SupervisorError::Poisoned(error.to_string()))
            }
        }
    }

    fn poison(&mut self, error: StoreError) {
        if self.poisoned.is_none() {
            self.poisoned = Some(error.to_string());
        }
        self.phase = Phase::Failed(FailReason::StorageError);
        self.kill_process("StorageError");
    }

    // ------------------------------------------------------------------ time

    /// Drives every timer: start timeout, heartbeats, author health, stop grace, lease deadline, backpressure
    /// timeouts, the resync timer, deferred checkpoint writes, restarts and the removal drain.
    pub fn tick(&mut self, now_ms: u64) {
        if self.poisoned.is_none() {
            self.tick_process(now_ms);
            self.advance();
            self.try_start(now_ms);
            if matches!(self.phase, Phase::Removing { .. }) && self.process.is_none() {
                let _ = self.finish_removal(now_ms);
            }
        }
        self.refresh(now_ms);
    }

    fn tick_process(&mut self, now_ms: u64) {
        let Some(process) = self.process.as_ref() else {
            return;
        };
        let epoch = process.epoch;
        if let Some(stopping) = process.stopping.clone() {
            if now_ms >= stopping.terminate_at_ms && !stopping.terminated {
                self.process
                    .as_mut()
                    .expect("present")
                    .stopping
                    .as_mut()
                    .expect("present")
                    .terminated = true;
                self.actions.push(Action::Terminate);
            }
            if now_ms >= stopping.kill_at_ms && !stopping.killed {
                self.process
                    .as_mut()
                    .expect("present")
                    .stopping
                    .as_mut()
                    .expect("present")
                    .killed = true;
                self.actions.push(Action::Kill);
            }
            return;
        }
        if !process.ready && now_ms.saturating_sub(process.started_ms) >= START_TIMEOUT_MS {
            return self.kill_process("StartTimeout");
        }
        if process.ready
            && now_ms.saturating_sub(process.last_seen_ms)
                >= HEARTBEAT_INTERVAL_MS * MISSED_HEARTBEATS
        {
            return self.kill_process("Hung");
        }
        if process
            .unhealthy_since_ms
            .is_some_and(|since| now_ms.saturating_sub(since) >= UNHEALTHY_RESTART_MS)
        {
            return self.stop_process("Unhealthy", Some("Unhealthy"), now_ms);
        }
        if !self.lease_valid(epoch, now_ms) {
            return self.stop_process("LeaseExpired", None, now_ms);
        }
        // Backpressure timeout (BLOCK): emits still queued behind a full outbox fail retryably.
        let full = !self.has_capacity(1, false);
        let expired: Vec<u64> = process
            .queue
            .iter()
            .copied()
            .filter(|seq| match process.emits.get(seq) {
                Some(Emit {
                    state: EmitState::Queued { deadline_ms },
                    payload: Some(Payload::Event { .. }),
                    ..
                }) => full && now_ms >= *deadline_ms,
                _ => false,
            })
            .collect();
        for seq in expired {
            self.counters.emit_timeouts += 1;
            self.retryable(seq, "BackpressureTimeout");
        }
        let process = self.process.as_ref().expect("present");
        if let Some(interval) = self
            .cfg
            .resync_interval_ms
            .filter(|_| self.cfg.mode == TriggerMode::Push)
        {
            if process.ready
                && !process.paused
                && now_ms.saturating_sub(process.last_reconcile_ms) >= interval
            {
                self.reconcile(now_ms);
            }
        }
    }

    // ------------------------------------------------------------------ status

    fn refresh(&mut self, now_ms: u64) {
        let fill_records = self.entries.len() as u64 * 100 / self.cfg.outbox.max_records.max(1);
        let fill_bytes = self.bytes * 100 / self.cfg.outbox.max_bytes.max(1);
        let fill = fill_records.max(fill_bytes);
        if fill >= 80 {
            self.backpressure = true;
        } else if fill < 50 {
            self.backpressure = false;
        }
        let leased = self.lease.is_some_and(|l| now_ms < l.renew_deadline_ms);
        self.set_condition(
            "Leased",
            TriggerConditionType::Leased,
            leased,
            if leased { "LeaseHeld" } else { "NoLease" },
            None,
            now_ms,
        );
        let ready = self
            .process
            .as_ref()
            .is_some_and(|p| p.ready && p.stopping.is_none());
        let ready_reason = match (ready, self.phase) {
            (true, _) => "Started",
            (false, Phase::Failed(FailReason::CrashLoop)) => "CrashLoop",
            (false, Phase::Failed(FailReason::TerminalRejection)) => "TerminalRejection",
            (false, Phase::Failed(FailReason::StorageError)) => "StorageError",
            (false, Phase::Failed(FailReason::CheckpointSchemaMismatch)) => {
                "CheckpointSchemaMismatch"
            }
            (false, _) => "NotRunning",
        };
        self.set_condition(
            "Ready",
            TriggerConditionType::Ready,
            ready,
            ready_reason,
            None,
            now_ms,
        );
        let (degraded, reason, message) = if self.backpressure {
            (true, "Backpressure", None)
        } else if self.events_dropped {
            (true, "EventsDropped", None)
        } else if self.events_rejected {
            (true, "EventsRejected", None)
        } else if let Some((_, reason)) = &self.author_health {
            (
                true,
                "AuthorReported",
                reason.clone().map(|r| r.chars().take(256).collect()),
            )
        } else {
            (false, "AsExpected", None)
        };
        self.set_condition(
            "Degraded",
            TriggerConditionType::Degraded,
            degraded,
            reason,
            message,
            now_ms,
        );
    }

    fn set_condition(
        &mut self,
        key: &'static str,
        kind: TriggerConditionType,
        on: bool,
        reason: &str,
        message: Option<String>,
        now_ms: u64,
    ) {
        let status = if on {
            TriggerConditionStatus::True
        } else {
            TriggerConditionStatus::False
        };
        let changed = self
            .conditions
            .get(key)
            .is_none_or(|(_, s, r, m, _)| *s != status || r != reason || *m != message);
        if changed {
            self.conditions
                .insert(key, (kind, status, reason.to_owned(), message, now_ms));
        }
    }

    /// The executor snapshot entry for this instance (plan 8.2).
    pub fn report(&self, now_ms: u64) -> TriggerInstanceReport {
        let phase = match self.phase {
            Phase::Pending | Phase::Backoff { .. } => TriggerPhase::Pending,
            Phase::Starting => TriggerPhase::Starting,
            Phase::Running => TriggerPhase::Running,
            Phase::Paused => TriggerPhase::Paused,
            Phase::Stopping | Phase::Stopped => TriggerPhase::Stopped,
            Phase::Failed(_) => TriggerPhase::Failed,
            Phase::Removing { .. } | Phase::Removed => TriggerPhase::Removed,
        };
        let conditions = self
            .conditions
            .values()
            .map(|(kind, status, reason, message, since)| TriggerCondition {
                r#type: kind.clone(),
                status: status.clone(),
                reason: reason.clone(),
                message: message.clone(),
                last_transition_time: rfc3339(*since),
            })
            .collect();
        let oldest = self.entries.values().map(|e| e.written_at_unix_ms).min();
        TriggerInstanceReport {
            instance_id: self.cfg.instance_id.clone(),
            config_revision: self.cfg.config_revision,
            lease_epoch: self.lease.map(|l| l.epoch),
            phase,
            conditions,
            last_event_at: self.last_event_ms.map(rfc3339),
            last_reconcile_at: self.last_reconcile_ms.map(rfc3339),
            counters: TriggerCounters {
                emitted: self.counters.emitted,
                rejected: self.counters.rejected,
                dropped: self.counters.dropped,
                emit_timeouts: Some(self.counters.emit_timeouts),
                restarts: self.counters.restarts,
                broker_calls: None,
                possible_loss_on_crash: (self.cfg.source_replay
                    == TriggerSourceReplay::NonReplayable)
                    .then_some(self.counters.possible_loss_on_crash),
            },
            outbox: TriggerOutboxStatus {
                depth: self.entries.len() as u64,
                oldest_age_ms: oldest.map_or(0, |at| now_ms.saturating_sub(at)),
            },
        }
    }
}
