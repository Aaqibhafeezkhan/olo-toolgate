// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! A world for the supervisor tests, following `docs/specs/trigger_delivery.tla`: a replayable source of
//! items 1..=N, a fake SDK runtime per process, two executors with in-memory disks, a fake Control with
//! dedupe, contiguous `acceptedOutboxSeq`, fencing and dispositions, and lossy, duplicating channels.
//! [`World::check`] asserts the model's invariants after every step.
#![allow(dead_code)]

use olo_toolgate_contracts::v2::{
    TriggerCheckpointFrame, TriggerDisposition, TriggerDispositionKind, TriggerEmitFrame,
    TriggerEmitStatus, TriggerEventKind, TriggerMode, TriggerOutboxAck, TriggerOutboxRecord,
    TriggerOverflow, TriggerReadyFrame, TriggerRejection, TriggerRejectionReason,
    TriggerRevocation, TriggerRevocationReason, TriggerSourceReplay, TriggerStartFrame,
    TriggerSyncAck, TriggerTerminalRejectionPolicy,
};
use olo_toolgate_trigger_supervisor::memory::{Fault, MemStore, OpKind};
use olo_toolgate_trigger_supervisor::restart::SeededRandom;
use olo_toolgate_trigger_supervisor::store::{check_recovered, Fence, Recovered, StoredCheckpoint};
use olo_toolgate_trigger_supervisor::supervisor::{Action, InstanceConfig, Lease, OutboxBounds};
use olo_toolgate_trigger_supervisor::{Inbound, Outbound, Supervisor};
use serde_json::json;
use std::collections::{BTreeMap, BTreeSet};

pub const INSTANCE: &str = "inst-1";
pub const LEASE_MS: u64 = 40_000;

pub fn config(policy: TriggerTerminalRejectionPolicy, max_records: u64) -> InstanceConfig {
    InstanceConfig {
        instance_id: INSTANCE.into(),
        trigger_id: "examples.mail/new-message".into(),
        config_revision: 1,
        source: format!("toolgate:tenant:t1:trigger-instance:{INSTANCE}"),
        event_type: "examples.mail.message.received.v1".into(),
        dataschema: Some("toolgate:package:sha256:00:trigger:new-message:event".into()),
        event_schema: json!({"type": "object", "properties": {"item": {"type": "integer"}}, "required": ["item"], "additionalProperties": false}),
        mode: TriggerMode::Push,
        policy,
        overflow: TriggerOverflow::Block,
        source_replay: TriggerSourceReplay::StateSnapshot,
        checkpoint_schema_version: 1,
        max_event_bytes: 16_384,
        max_checkpoint_bytes: 4_096,
        max_events_per_hour: 1_000_000,
        emit_timeout_ms: 30_000,
        stop_grace_ms: 10_000,
        resync_interval_ms: None,
        outbox: OutboxBounds {
            max_records,
            max_bytes: 1 << 20,
        },
        config: BTreeMap::new(),
        globals: None,
        secret_handles: None,
    }
}

pub fn item_of(id: &str) -> u64 {
    id.trim_start_matches("item-")
        .trim_end_matches(":rejected")
        .parse()
        .expect("item id")
}

pub fn position(state: &str) -> u64 {
    state.parse().expect("position")
}

/// The fake SDK runtime of one trigger process.
#[derive(Debug, Clone)]
pub struct Runtime {
    pub read_pos: u64,
    pub next_emit: u64,
    /// emitSeq -> item
    pub emits: BTreeMap<u64, u64>,
    pub retry: BTreeSet<u64>,
    pub resolved: BTreeSet<u64>,
    pub accepted_upto: u64,
    /// checkpoint id -> position
    pub checkpoints: BTreeMap<u64, u64>,
    pub next_checkpoint: u64,
    pub start_pos: u64,
    /// STOP received; the SDK exits at the next tick and may still race frames until then.
    pub stopping: bool,
}

impl Runtime {
    pub fn start(frame: &TriggerStartFrame) -> Self {
        let start = frame.checkpoint.as_deref().map_or(1, position);
        Self {
            read_pos: start,
            next_emit: 1,
            emits: BTreeMap::new(),
            retry: BTreeSet::new(),
            resolved: BTreeSet::new(),
            accepted_upto: 0,
            checkpoints: BTreeMap::new(),
            next_checkpoint: 1,
            start_pos: start,
            stopping: false,
        }
    }
}

#[derive(Debug, Default)]
pub struct Control {
    pub epoch: u64,
    pub holder: BTreeMap<u64, usize>,
    pub keys: BTreeSet<String>,
    pub stored: BTreeSet<u64>,
    pub store_count: BTreeMap<u64, u32>,
    pub disposed: BTreeSet<u64>,
    pub seqs: BTreeMap<u64, BTreeSet<u64>>,
    pub checkpoint: Option<StoredCheckpoint>,
    pub halted: Option<u64>,
    /// (executor, epoch) pairs Control has fenced.
    pub fenced: BTreeSet<(usize, u64)>,
    /// Epochs below this are no longer drained, even from the current holder (the 7-day drain window).
    pub drain_floor: u64,
}

impl Control {
    pub fn prefix(&self, epoch: u64) -> u64 {
        let seqs = self.seqs.get(&epoch);
        let mut n = 0;
        while seqs.is_some_and(|s| s.contains(&(n + 1))) {
            n += 1;
        }
        n
    }

    pub fn current_holder(&self) -> usize {
        self.holder[&self.epoch]
    }

    pub fn is_fenced(&self, epoch: u64, from: usize) -> bool {
        epoch < self.drain_floor
            || self
                .holder
                .get(&epoch)
                .is_none_or(|h| *h != self.current_holder() || *h != from)
    }
}

pub struct Exec {
    pub store: MemStore,
    pub sup: Option<Supervisor<MemStore>>,
    pub runtime: Option<Runtime>,
    /// Durable records seen so far: (epoch, seq) -> id. A reuse of an outboxSeq for another record breaks S1.
    pub ghost: BTreeMap<(u64, u64), String>,
    pub last_entries: BTreeMap<(u64, u64), String>,
    pub wiped: bool,
    pub seed: u64,
}

pub struct World {
    pub policy: TriggerTerminalRejectionPolicy,
    pub n: u64,
    pub exec_bad: BTreeSet<u64>,
    pub ctrl_bad: BTreeSet<u64>,
    pub max_records: u64,
    pub now: u64,
    pub execs: Vec<Exec>,
    pub ctrl: Control,
    pub net: Vec<(usize, TriggerOutboxRecord)>,
    pub acks: Vec<(usize, TriggerSyncAck)>,
    pub halted_items: BTreeSet<u64>,
    /// The executor that must still adopt Control's checkpoint before it runs (failover or reinstall).
    pub pending_resume: Option<usize>,
}

fn empty_ack() -> TriggerSyncAck {
    TriggerSyncAck {
        generation: 1,
        max_check_in_bytes: 196_608,
        check_in_now: None,
        acked: None,
        dispositions: None,
        retry_later: None,
        leases: None,
        revoked: None,
        rejected: None,
        snapshot_pages: None,
        snapshot_requested: None,
    }
}

impl World {
    pub fn new(
        policy: TriggerTerminalRejectionPolicy,
        n: u64,
        exec_bad: BTreeSet<u64>,
        ctrl_bad: BTreeSet<u64>,
        max_records: u64,
    ) -> Self {
        let mut world = Self {
            policy,
            n,
            exec_bad,
            ctrl_bad,
            max_records,
            now: 1_000_000,
            execs: (0..2)
                .map(|i| Exec {
                    store: MemStore::new(),
                    sup: None,
                    runtime: None,
                    ghost: BTreeMap::new(),
                    last_entries: BTreeMap::new(),
                    wiped: false,
                    seed: 11 + i as u64,
                })
                .collect(),
            ctrl: Control {
                epoch: 1,
                holder: BTreeMap::from([(1, 0)]),
                ..Control::default()
            },
            net: Vec::new(),
            acks: Vec::new(),
            halted_items: BTreeSet::new(),
            pending_resume: None,
        };
        world.recover(0);
        world.recover(1);
        world
    }

    pub fn cfg(&self) -> InstanceConfig {
        config(self.policy.clone(), self.max_records)
    }

    pub fn done(&self, item: u64) -> bool {
        self.ctrl.stored.contains(&item)
            || (self.policy == TriggerTerminalRejectionPolicy::SkipAndRecord
                && self.ctrl.disposed.contains(&item))
    }

    // ------------------------------------------------------------------ executor plumbing

    /// Runs `f` on executor `e`'s supervisor, then carries out its actions; a crash fault kills the executor.
    pub fn with_sup(&mut self, e: usize, f: impl FnOnce(&mut Supervisor<MemStore>, u64)) {
        let now = self.now;
        let Some(sup) = self.execs[e].sup.as_mut() else {
            return;
        };
        f(sup, now);
        self.settle_actions(e);
    }

    fn crashed(&self, e: usize) -> bool {
        self.execs[e].store.disk().crashed
    }

    pub fn crash(&mut self, e: usize) {
        self.execs[e].sup = None;
        self.execs[e].runtime = None;
    }

    pub fn recover(&mut self, e: usize) {
        if self.execs[e].sup.is_some() {
            return;
        }
        self.execs[e].store.disk().disarm();
        self.execs[e].seed += 1000;
        let seed = self.execs[e].seed;
        let sup = Supervisor::recover(
            self.cfg(),
            self.execs[e].store.clone(),
            Box::new(SeededRandom(seed)),
            self.now,
        )
        .expect("recover");
        self.execs[e].sup = Some(sup);
        self.execs[e].runtime = None;
        if self.pending_resume == Some(e) && !self.resume(e) {
            return;
        }
        if self.ctrl.current_holder() == e {
            let lease = Lease {
                epoch: self.ctrl.epoch,
                renew_deadline_ms: self.now + LEASE_MS,
            };
            self.with_sup(e, |s, now| {
                s.grant_lease(lease, now);
                s.run(now);
            });
        }
    }

    /// Adopts Control's ACCEPTED checkpoint; a crash fault leaves the resume pending for the next recovery.
    fn resume(&mut self, e: usize) -> bool {
        let checkpoint = self.ctrl.checkpoint.clone();
        let now = self.now;
        let resumed = self.execs[e]
            .sup
            .as_mut()
            .expect("present")
            .resume_from_control(checkpoint, now)
            .is_ok();
        if resumed {
            self.pending_resume = None;
        } else {
            self.crash(e);
        }
        resumed
    }

    /// Carries out the supervisor's actions until there are none: spawn, frames to the runtime, kills.
    pub fn settle_actions(&mut self, e: usize) {
        for _ in 0..1000 {
            if self.crashed(e) {
                return self.crash(e);
            }
            let Some(sup) = self.execs[e].sup.as_mut() else {
                return;
            };
            let actions = sup.take_actions();
            if actions.is_empty() {
                return;
            }
            for action in actions {
                match action {
                    Action::Spawn(start) => {
                        assert!(
                            self.execs[e].runtime.is_none(),
                            "spawn while a process runs"
                        );
                        self.execs[e].runtime = Some(Runtime::start(&start));
                        let now = self.now;
                        self.execs[e].sup.as_mut().expect("present").on_frame(
                            Outbound::Ready(TriggerReadyFrame {
                                kind: "READY".into(),
                            }),
                            now,
                        );
                    }
                    Action::Send(frame) => self.deliver_frame(e, frame),
                    Action::Forward { .. } => {}
                    Action::Terminate | Action::Kill => self.process_exit(e),
                }
                if self.crashed(e) {
                    return self.crash(e);
                }
                if self.execs[e].sup.is_none() {
                    return;
                }
            }
        }
        panic!("supervisor actions did not settle");
    }

    fn process_exit(&mut self, e: usize) {
        if self.execs[e].runtime.take().is_some() {
            let now = self.now;
            self.execs[e].sup.as_mut().expect("present").on_exit(now);
        }
    }

    fn deliver_frame(&mut self, e: usize, frame: Inbound) {
        let Some(rt) = self.execs[e].runtime.as_mut() else {
            return;
        };
        match frame {
            Inbound::EmitAck(ack) => {
                let item = rt.emits[&ack.emit_seq];
                match ack.status {
                    TriggerEmitStatus::LocalDurable => {
                        assert!(ack.outbox_seq.is_some(), "LOCAL_DURABLE without outboxSeq");
                        rt.resolved.insert(ack.emit_seq);
                    }
                    TriggerEmitStatus::RetryableRejection => {
                        rt.retry.insert(ack.emit_seq);
                    }
                    TriggerEmitStatus::TerminalRejection => match self.policy {
                        TriggerTerminalRejectionPolicy::SkipAndRecord => {
                            assert!(
                                ack.outbox_seq.is_some(),
                                "a loss record has its own outboxSeq"
                            );
                            rt.resolved.insert(ack.emit_seq);
                        }
                        TriggerTerminalRejectionPolicy::Halt => {
                            assert!(ack.outbox_seq.is_none(), "HALT appends no record");
                            self.halted_items.insert(item);
                        }
                    },
                }
            }
            Inbound::AcceptedUpTo(upto) => {
                assert!(
                    upto.emit_seq >= rt.accepted_upto,
                    "ACCEPTED_UPTO went backwards"
                );
                assert!(
                    upto.emit_seq < rt.next_emit,
                    "ACCEPTED_UPTO past the last emit"
                );
                rt.accepted_upto = upto.emit_seq;
                let items: Vec<u64> = rt.emits.range(..=upto.emit_seq).map(|(_, i)| *i).collect();
                for item in items {
                    assert!(
                        self.done(item),
                        "ACCEPTED_UPTO {} covers item {item} that Control has not resolved",
                        upto.emit_seq
                    );
                }
            }
            Inbound::CheckpointAck(ack) => {
                let pos = rt.checkpoints[&ack.checkpoint_id];
                let accepted =
                    ack.level == olo_toolgate_contracts::v2::TriggerCheckpointLevel::Accepted;
                for item in 1..pos {
                    let durable = self.execs[e]
                        .store
                        .disk()
                        .entries
                        .values()
                        .any(|r| item_of(&r.event.id) == item);
                    assert!(
                        self.done(item) || (!accepted && durable),
                        "CHECKPOINT_ACK {:?} at {pos} passes unresolved item {item}",
                        ack.level
                    );
                }
            }
            Inbound::Stop(_) => rt.stopping = true,
            Inbound::Start(_) | Inbound::Reconcile(_) | Inbound::Pause(_) | Inbound::Resume(_) => {}
        }
    }

    fn data(&self, item: u64) -> serde_json::Value {
        if self.exec_bad.contains(&item) {
            json!({"bad": item})
        } else {
            json!({"item": item})
        }
    }

    // ------------------------------------------------------------------ steps

    /// The runtime emits the next source item (with its position, or without one as a POLL batch does).
    pub fn emit(&mut self, e: usize, with_position: bool) {
        let n = self.n;
        let Some(rt) = self.execs[e].runtime.as_mut() else {
            return;
        };
        if rt.read_pos > n {
            return;
        }
        let item = rt.read_pos;
        rt.read_pos += 1;
        let emit_seq = rt.next_emit;
        rt.next_emit += 1;
        rt.emits.insert(emit_seq, item);
        let frame = TriggerEmitFrame {
            kind: "EMIT".into(),
            emit_seq,
            id: Some(format!("item-{item}")),
            subject: None,
            time: None,
            data: self.data(item),
            position: with_position.then(|| (item + 1).to_string()),
        };
        self.with_sup(e, |s, now| s.on_frame(Outbound::Emit(frame), now));
    }

    /// An explicit checkpoint after the last emit.
    pub fn checkpoint(&mut self, e: usize) {
        let Some(rt) = self.execs[e].runtime.as_mut() else {
            return;
        };
        let id = rt.next_checkpoint;
        rt.next_checkpoint += 1;
        rt.checkpoints.insert(id, rt.read_pos);
        let frame = TriggerCheckpointFrame {
            kind: "CHECKPOINT".into(),
            checkpoint_id: id,
            after_emit_seq: rt.next_emit - 1,
            state: rt.read_pos.to_string(),
        };
        self.with_sup(e, |s, now| s.on_frame(Outbound::Checkpoint(frame), now));
    }

    /// The SDK retries one retryable rejection with the same emitSeq and id.
    pub fn retry(&mut self, e: usize, pick: usize) {
        let Some(rt) = self.execs[e].runtime.as_mut() else {
            return;
        };
        let Some(emit_seq) = rt.retry.iter().copied().nth(pick % rt.retry.len().max(1)) else {
            return;
        };
        rt.retry.remove(&emit_seq);
        let item = rt.emits[&emit_seq];
        let frame = TriggerEmitFrame {
            kind: "EMIT".into(),
            emit_seq,
            id: Some(format!("item-{item}")),
            subject: None,
            time: None,
            data: self.data(item),
            position: None,
        };
        self.with_sup(e, |s, now| s.on_frame(Outbound::Emit(frame), now));
    }

    /// The outbox writer completes one queued emit, in any order.
    pub fn persist(&mut self, e: usize, pick: usize) {
        let Some(queued) = self.execs[e].sup.as_ref().map(|s| s.queued()) else {
            return;
        };
        let Some(seq) = queued.get(pick % queued.len().max(1)).copied() else {
            return;
        };
        self.with_sup(e, |s, now| {
            s.persist(seq, now);
        });
    }

    pub fn pump(&mut self, e: usize) {
        self.with_sup(e, |s, now| s.pump(now));
    }

    /// A check-in: every sendable record goes on the wire (re-sending duplicates).
    pub fn send(&mut self, e: usize) {
        let now = self.now;
        let Some(sup) = self.execs[e].sup.as_ref() else {
            return;
        };
        let batch = sup.sync_events(1 << 20, now);
        for record in batch.events {
            let text = serde_json::to_string(&record).expect("record");
            assert!(
                !text.contains("emitSeq"),
                "emitSeq left the process: {text}"
            );
            if !self.net.iter().any(|(from, r)| *from == e && *r == record) {
                self.net.push((e, record));
            }
        }
    }

    /// Control ingests one record (T6, or T9 when fenced) and acknowledges.
    pub fn ingest(&mut self, pick: usize) {
        if self.net.is_empty() {
            return;
        }
        let (from, record) = self.net.remove(pick % self.net.len());
        let epoch = record.event.tgleaseepoch;
        let seq = record.event.tgsequence;
        let mut ack = empty_ack();
        if self.ctrl.is_fenced(epoch, from) {
            self.ctrl.fenced.insert((from, epoch));
            ack.rejected = Some(vec![TriggerRejection {
                instance_id: INSTANCE.into(),
                lease_epoch: epoch,
                reason: TriggerRejectionReason::Staleleaseepoch,
            }]);
            self.acks.push((from, ack));
            return;
        }
        let item = item_of(&record.event.id);
        let skip = self.policy == TriggerTerminalRejectionPolicy::SkipAndRecord;
        if record.event.tgkind == TriggerEventKind::Rejected {
            assert!(skip, "a loss record under HALT");
            self.ctrl.disposed.insert(item);
            self.ctrl.seqs.entry(epoch).or_default().insert(seq);
        } else if self.ctrl_bad.contains(&item) {
            // Control-side terminal rejection on re-validation: a disposition, counted only under SKIP.
            self.ctrl.disposed.insert(item);
            if skip {
                self.ctrl.seqs.entry(epoch).or_default().insert(seq);
            } else {
                self.ctrl.halted.get_or_insert(item);
            }
            ack.dispositions = Some(vec![TriggerDisposition {
                instance_id: INSTANCE.into(),
                lease_epoch: epoch,
                outbox_seq: seq,
                id: record.event.id.clone(),
                disposition: TriggerDispositionKind::RejectedTerminal,
                reason: "SchemaInvalid".into(),
            }]);
        } else {
            assert!(
                !self.exec_bad.contains(&item),
                "S3: a schema-invalid event reached Control"
            );
            if self.ctrl.keys.insert(record.event.id.clone()) {
                self.ctrl.stored.insert(item);
                *self.ctrl.store_count.entry(item).or_default() += 1;
            }
            self.ctrl.seqs.entry(epoch).or_default().insert(seq);
        }
        let prefix = self.ctrl.prefix(epoch);
        if prefix > 0 {
            ack.acked = Some(vec![TriggerOutboxAck {
                instance_id: INSTANCE.into(),
                lease_epoch: epoch,
                outbox_seq: prefix,
            }]);
        }
        self.acks.push((from, ack));
    }

    /// Delivers one acknowledgement (keeping a copy to deliver it again when `duplicate`).
    pub fn deliver_ack(&mut self, pick: usize, duplicate: bool) {
        if self.acks.is_empty() {
            return;
        }
        let index = pick % self.acks.len();
        let (to, ack) = if duplicate {
            self.acks[index].clone()
        } else {
            self.acks.remove(index)
        };
        self.with_sup(to, |s, now| s.on_sync_ack(&ack, now));
    }

    pub fn drop_message(&mut self, pick: usize) {
        let total = self.net.len() + self.acks.len();
        if total == 0 {
            return;
        }
        let index = pick % total;
        if index < self.net.len() {
            self.net.remove(index);
        } else {
            self.acks.remove(index - self.net.len());
        }
    }

    /// Time passes; the current holder checks in and renews its lease; every supervisor's timers run.
    pub fn tick(&mut self, ms: u64) {
        self.now += ms;
        for e in 0..self.execs.len() {
            self.exit_stopped(e);
        }
        for e in 0..self.execs.len() {
            // The SDK runtime's heartbeat thread keeps a live process from looking hung.
            if self.execs[e].runtime.is_some() {
                let beat = Outbound::Heartbeat(olo_toolgate_contracts::v2::TriggerHeartbeatFrame {
                    kind: "HEARTBEAT".into(),
                });
                self.with_sup(e, |s, now| s.on_frame(beat, now));
            }
        }
        let holder = self.ctrl.current_holder();
        let lease = Lease {
            epoch: self.ctrl.epoch,
            renew_deadline_ms: self.now + LEASE_MS,
        };
        self.with_sup(holder, |s, now| s.grant_lease(lease, now));
        for e in 0..self.execs.len() {
            self.with_sup(e, |s, now| s.tick(now));
        }
    }

    /// A process that received STOP sends EXIT and exits.
    pub fn exit_stopped(&mut self, e: usize) {
        if self.execs[e].runtime.as_ref().is_some_and(|r| r.stopping) {
            let exit = Outbound::Exit(olo_toolgate_contracts::v2::TriggerExitFrame {
                kind: "EXIT".into(),
                code: 0,
            });
            self.with_sup(e, |s, now| s.on_frame(exit, now));
            self.process_exit(e);
            self.settle_actions(e);
        }
    }

    /// The drain window of the current holder's older epochs expires: Control fences them too.
    pub fn expire_drain(&mut self) {
        self.ctrl.drain_floor = self.ctrl.epoch;
    }

    pub fn kill_process(&mut self, e: usize) {
        if self.execs[e].runtime.is_some() {
            self.process_exit(e);
            self.settle_actions(e);
        }
    }

    pub fn arm(&mut self, e: usize, kind: Option<OpKind>, fault: Fault) {
        if self.execs[e].sup.is_some() {
            self.execs[e].store.disk().arm(kind, fault);
        }
    }

    /// T7: the current holder uploads its ACCEPTED checkpoint; Control refuses one past `acceptedOutboxSeq`.
    pub fn control_checkpoint(&mut self, e: usize) {
        if self.ctrl.current_holder() != e {
            return;
        }
        let Some(accepted) = self.execs[e]
            .sup
            .as_ref()
            .and_then(|s| s.accepted_checkpoint().cloned())
        else {
            return;
        };
        let current = self
            .ctrl
            .checkpoint
            .as_ref()
            .map_or(1, |c| position(&c.state));
        if position(&accepted.state) > current
            && accepted.after_outbox_seq <= self.ctrl.prefix(accepted.lease_epoch)
        {
            self.ctrl.checkpoint = Some(accepted);
        }
    }

    /// A new epoch for the same executor (config change, Stop then Run): older records still drain.
    pub fn renew_epoch(&mut self, max_epoch: u64) {
        if self.ctrl.epoch >= max_epoch {
            return;
        }
        let holder = self.ctrl.current_holder();
        self.ctrl.epoch += 1;
        self.ctrl.holder.insert(self.ctrl.epoch, holder);
        let lease = Lease {
            epoch: self.ctrl.epoch,
            renew_deadline_ms: self.now + LEASE_MS,
        };
        self.with_sup(holder, |s, now| s.grant_lease(lease, now));
    }

    /// Server failover to `e` at a new epoch, from Control's ACCEPTED checkpoint (I). The old holder keeps
    /// running until its lease runs out or is revoked.
    pub fn failover(&mut self, e: usize, max_epoch: u64) {
        if self.ctrl.epoch >= max_epoch
            || self.ctrl.current_holder() == e
            || self.execs[e].runtime.is_some()
        {
            return;
        }
        self.ctrl.epoch += 1;
        self.ctrl.holder.insert(self.ctrl.epoch, e);
        self.pending_resume = Some(e);
        if self.execs[e].sup.is_none() {
            return self.recover(e);
        }
        if self.resume(e) {
            let lease = Lease {
                epoch: self.ctrl.epoch,
                renew_deadline_ms: self.now + LEASE_MS,
            };
            self.with_sup(e, |s, now| {
                s.grant_lease(lease, now);
                s.run(now);
            });
        }
    }

    /// The old holder's lease is revoked in an ack.
    pub fn revoke(&mut self, e: usize) {
        if self.ctrl.current_holder() == e {
            return;
        }
        let mut ack = empty_ack();
        ack.revoked = Some(vec![TriggerRevocation {
            instance_id: INSTANCE.into(),
            reason: TriggerRevocationReason::Reassigned,
        }]);
        self.with_sup(e, |s, now| s.on_sync_ack(&ack, now));
    }

    /// T14: the holder loses its disk and is reinstalled at a new epoch.
    pub fn reinstall(&mut self, max_epoch: u64) {
        if self.ctrl.epoch >= max_epoch {
            return;
        }
        let e = self.ctrl.current_holder();
        self.crash(e);
        self.execs[e].store.disk().wipe();
        self.execs[e].ghost.clear();
        self.execs[e].last_entries.clear();
        self.ctrl.epoch += 1;
        self.ctrl.holder.insert(self.ctrl.epoch, e);
        self.pending_resume = Some(e);
        self.recover(e);
    }

    // ------------------------------------------------------------------ invariants

    /// The invariants of `trigger_delivery.tla` that the executor side can break, checked on durable state.
    pub fn check(&mut self) {
        let skip = self.policy == TriggerTerminalRejectionPolicy::SkipAndRecord;
        for e in 0..self.execs.len() {
            let disk = self.execs[e].store.disk().clone();
            let mut recovered = Recovered {
                entries: disk.entries.values().cloned().collect(),
                epochs: disk.epochs.clone(),
                local: disk.local.clone(),
                accepted: disk.accepted.clone(),
                halt: disk.halt.clone(),
            };
            // S1: gap-free outbox sequences per epoch, and an outboxSeq never names two records.
            check_recovered(&mut recovered)
                .unwrap_or_else(|err| panic!("S1 on executor {e}: {err}"));
            for ((ep, seq), entry) in &disk.entries {
                let previous = self.execs[e]
                    .ghost
                    .insert((*ep, *seq), entry.event.id.clone());
                assert!(
                    previous.as_ref().is_none_or(|p| *p == entry.event.id),
                    "S1: outboxSeq {ep}:{seq} reused on executor {e}"
                );
            }
            // P and P2 (T8, T10): a record leaves the outbox only when acknowledged for a resolved item, or
            // fenced for a non-compromise reason. Overflow never prunes.
            let current: BTreeMap<(u64, u64), String> = disk
                .entries
                .iter()
                .map(|(k, v)| (*k, v.event.id.clone()))
                .collect();
            for ((ep, seq), id) in &self.execs[e].last_entries {
                if current.contains_key(&(*ep, *seq)) {
                    continue;
                }
                let mark = disk
                    .epochs
                    .get(ep)
                    .expect("a pruned record leaves its epoch mark");
                let fenced =
                    mark.fenced == Some(Fence::Pruned) && self.ctrl.fenced.contains(&(e, *ep));
                let acked = *seq <= mark.acked_outbox_seq
                    && *seq <= self.ctrl.prefix(*ep)
                    && self.done(item_of(id));
                assert!(
                    fenced || acked,
                    "P2: executor {e} pruned {ep}:{seq} ({id}) without acknowledgement or fence"
                );
            }
            self.execs[e].last_entries = current;
            let text = serde_json::to_string(&(
                disk.entries.values().collect::<Vec<_>>(),
                &disk.local,
                &disk.accepted,
                &disk.halt,
            ))
            .expect("disk");
            assert!(
                !text.contains("emitSeq"),
                "a persisted value carries an emitSeq"
            );
            // L and I: the current holder's LOCAL checkpoint never passes an item that is neither durable in
            // its outbox nor resolved at Control.
            if self.ctrl.current_holder() == e {
                if let Some(local) = &disk.local {
                    for item in 1..position(&local.state) {
                        let durable = disk.entries.values().any(|r| item_of(&r.event.id) == item);
                        assert!(
                            durable || self.done(item),
                            "L: executor {e} LOCAL checkpoint {} passes item {item}",
                            local.state
                        );
                    }
                }
            }
            // P2 (accepted position): an ACCEPTED checkpoint never passes an unresolved item.
            if let Some(accepted) = &disk.accepted {
                for item in 1..position(&accepted.state) {
                    assert!(
                        self.done(item),
                        "P2: executor {e} ACCEPTED checkpoint {} passes item {item}",
                        accepted.state
                    );
                }
            }
            // S4: under HALT nothing passes the halted item.
            if let Some(halted) = self.halted_items.iter().next().copied() {
                for checkpoint in [&disk.local, &disk.accepted].into_iter().flatten() {
                    assert!(
                        position(&checkpoint.state) <= halted,
                        "S4: a checkpoint passes halted item {halted}"
                    );
                }
            }
            if let Some(halted) = self.ctrl.halted {
                if let Some(accepted) = &disk.accepted {
                    assert!(
                        position(&accepted.state) <= halted,
                        "S4: ACCEPTED passes Control-halted item {halted}"
                    );
                }
            }
        }
        // C: Control's checkpoint never passes an unresolved item.
        if let Some(checkpoint) = &self.ctrl.checkpoint {
            for item in 1..position(&checkpoint.state) {
                assert!(
                    self.done(item),
                    "C: Control checkpoint {} passes item {item}",
                    checkpoint.state
                );
            }
            for halted in self.halted_items.iter().chain(self.ctrl.halted.iter()) {
                assert!(
                    position(&checkpoint.state) <= *halted,
                    "S4: Control checkpoint passes halted item {halted}"
                );
            }
        }
        // S3 and D.
        for item in self.exec_bad.iter().chain(self.ctrl_bad.iter()) {
            assert!(
                !self.ctrl.stored.contains(item),
                "S3: rejected item {item} stored"
            );
        }
        assert!(
            self.ctrl.store_count.values().all(|c| *c <= 1),
            "D: an item stored twice"
        );
        let _ = skip;
    }

    /// Fair scheduling with every fault healed: the remaining work runs to completion.
    pub fn settle(&mut self, rounds: usize) {
        for e in 0..self.execs.len() {
            self.execs[e].store.disk().disarm();
            self.recover(e);
            self.with_sup(e, |s, now| s.reset_crash_loop(now));
        }
        for _ in 0..rounds {
            self.tick(31_000);
            let holder = self.ctrl.current_holder();
            for _ in 0..(self.n + 2) {
                self.emit(holder, true);
                for pick in 0..4 {
                    self.retry(holder, pick);
                }
                self.pump(holder);
            }
            self.checkpoint(holder);
            for e in 0..self.execs.len() {
                self.revoke(e);
                self.pump(e);
                self.send(e);
            }
            while !self.net.is_empty() {
                self.ingest(0);
            }
            while !self.acks.is_empty() {
                self.deliver_ack(0, false);
            }
            self.control_checkpoint(holder);
            self.check();
        }
    }
}
