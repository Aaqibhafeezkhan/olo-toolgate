// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Client triggers (ADR 058, issue #274 module 5). One task owns every instance's supervisor
//! (`olo-toolgate-trigger-supervisor`) and its trigger container. The service hands it the verified desired state, asks
//! it for the `TriggerSync` block of each check-in and gives it each `TriggerSyncAck`.
//!
//! An instance runs only when the newest signed desired document lists it, a released package on this device declares
//! its trigger with the same descriptor digest (Installed), and it holds an unexpired lease. Its container has the same
//! hardening as a tool run, with no network; the only broker it gets is the read-only device files area its signed
//! registration grants.
use crate::execution::{adapters, TriggerContext};
use crate::{Failure, Result};
use base64::Engine as _;
use olo_toolgate_contracts::v2::{
    TriggerCheckpointLevel, TriggerCheckpointReport, TriggerCondition, TriggerConditionStatus,
    TriggerConditionType, TriggerDescriptor, TriggerDesiredDocument, TriggerDesiredInstance,
    TriggerDesiredState, TriggerExecutor, TriggerPhase, TriggerSnapshotPage, TriggerSync,
    TriggerSyncAck,
};
use olo_toolgate_contracts::{FleetTriggerRegistration, LocalFileAccess, ManagedRuntime};
use olo_toolgate_trigger_supervisor::frames::{encode_inbound, Outbound};
use olo_toolgate_trigger_supervisor::process::TriggerProcess;
use olo_toolgate_trigger_supervisor::restart::SystemRandom;
use olo_toolgate_trigger_supervisor::store::FsStore;
use olo_toolgate_trigger_supervisor::supervisor::{
    Action, InstanceConfig, Lease, OutboxBounds, Phase,
};
use olo_toolgate_trigger_supervisor::Supervisor;
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::time::Duration;
use tokio::sync::{mpsc, oneshot};

/// Event bytes per check-in (plan 8.2: the `RunEventBatch` bound of a client).
pub const CHECK_IN_EVENT_BYTES: u64 = 48 * 1024;
/// A client stops a trigger 5 minutes before its lease expires unless it was renewed (plan 7).
const RENEW_MARGIN_MS: u64 = 5 * 60_000;
const MEMORY_MI_B: u64 = 512;
const INLINE_CHECKPOINT_BYTES: usize = 4096;

/// One instance of the desired document, resolved on this device.
#[derive(Clone)]
pub struct Resolved {
    pub instance: TriggerDesiredInstance,
    pub descriptor: TriggerDescriptor,
    /// The active release's registration matching the trigger, package, and descriptor digests.
    pub registration: Option<FleetTriggerRegistration>,
    pub runtime: Option<ManagedRuntime>,
}

/// The verified desired state handed to the runner.
pub struct Desired {
    pub document: TriggerDesiredDocument,
    pub resolved: Vec<Resolved>,
}

enum Command {
    Sync(oneshot::Sender<TriggerSync>),
    Ack(TriggerSyncAck),
    Desired(Desired),
    Context(Option<TriggerContext>),
    Observed(oneshot::Sender<u64>),
}

enum Event {
    Frame(String, u64, Outbound),
    Exit(String, u64),
}

/// The handle the service keeps.
#[derive(Clone)]
pub struct Triggers {
    tx: mpsc::Sender<Command>,
}

impl Triggers {
    /// Starts the runner; trigger state lives under `root/<instanceId>`.
    pub fn start(root: PathBuf, tenant_id: String) -> Self {
        let (tx, rx) = mpsc::channel(32);
        tokio::spawn(Runner::new(root, tenant_id).run(rx));
        Self { tx }
    }

    /// The `triggers` block of the next check-in.
    pub async fn sync(&self) -> Result<TriggerSync> {
        let (reply, answer) = oneshot::channel();
        self.tx
            .send(Command::Sync(reply))
            .await
            .map_err(|_| Failure::Unavailable)?;
        answer.await.map_err(|_| Failure::Unavailable)
    }

    pub async fn acknowledge(&self, ack: TriggerSyncAck) -> Result<()> {
        self.tx
            .send(Command::Ack(ack))
            .await
            .map_err(|_| Failure::Unavailable)
    }

    pub async fn desired(&self, desired: Desired) -> Result<()> {
        self.tx
            .send(Command::Desired(desired))
            .await
            .map_err(|_| Failure::Unavailable)
    }

    pub async fn context(&self, context: Option<TriggerContext>) -> Result<()> {
        self.tx
            .send(Command::Context(context))
            .await
            .map_err(|_| Failure::Unavailable)
    }

    /// The generation of the newest desired document applied (zero before the first).
    pub async fn observed(&self) -> Result<u64> {
        let (reply, answer) = oneshot::channel();
        self.tx
            .send(Command::Observed(reply))
            .await
            .map_err(|_| Failure::Unavailable)?;
        answer.await.map_err(|_| Failure::Unavailable)
    }
}

/// The `triggerDescriptorDigest` of a descriptor: NFC and RFC 8785, as `toolgate pack` computes it.
pub fn descriptor_digest(descriptor: &serde_json::Value) -> Result<String> {
    olo_toolgate_trigger_supervisor::canonical::digest(descriptor).map_err(|_| Failure::Validation)
}

struct Process {
    generation: u64,
    stdin: mpsc::UnboundedSender<Vec<u8>>,
    kill: Option<oneshot::Sender<()>>,
}

struct Instance {
    supervisor: Supervisor<FsStore>,
    resolved: Resolved,
    process: Option<Process>,
    reported_checkpoint: Option<(u64, u64)>,
}

struct Runner {
    root: PathBuf,
    tenant: String,
    context: Option<TriggerContext>,
    observed: u64,
    snapshot: u64,
    spawned: u64,
    instances: BTreeMap<String, Instance>,
    events: (
        mpsc::UnboundedSender<Event>,
        Option<mpsc::UnboundedReceiver<Event>>,
    ),
}

fn now() -> u64 {
    crate::now()
}

impl Runner {
    fn new(root: PathBuf, tenant: String) -> Self {
        let (tx, rx) = mpsc::unbounded_channel();
        Self {
            root,
            tenant,
            context: None,
            observed: 0,
            snapshot: 0,
            spawned: 0,
            instances: BTreeMap::new(),
            events: (tx, Some(rx)),
        }
    }

    async fn run(mut self, mut commands: mpsc::Receiver<Command>) {
        let mut events = self
            .events
            .1
            .take()
            .expect("the event receiver is taken once");
        let mut tick = tokio::time::interval(Duration::from_secs(1));
        loop {
            tokio::select! {
                command = commands.recv() => match command {
                    None => break,
                    Some(command) => self.command(command),
                },
                Some(event) = events.recv() => self.event(event),
                _ = tick.tick() => self.tick(),
            }
        }
        for instance in self.instances.values_mut() {
            if let Some(process) = instance.process.as_mut() {
                if let Some(kill) = process.kill.take() {
                    let _ = kill.send(());
                }
            }
        }
    }

    fn command(&mut self, command: Command) {
        match command {
            Command::Sync(reply) => {
                let _ = reply.send(self.sync());
            }
            Command::Ack(ack) => self.acknowledge(&ack),
            Command::Desired(desired) => self.desired(desired),
            Command::Context(context) => self.context = context,
            Command::Observed(reply) => {
                let _ = reply.send(self.observed);
            }
        }
    }

    // ---- desired state ----

    fn desired(&mut self, desired: Desired) {
        if desired.document.generation < self.observed {
            return;
        }
        let at = now();
        let mut present = std::collections::BTreeSet::new();
        for resolved in desired.resolved {
            let id = resolved.instance.instance_id.clone();
            present.insert(id.clone());
            let cfg = match InstanceConfig::from_descriptor(
                &self.tenant,
                &resolved.descriptor,
                &resolved.instance,
                OutboxBounds::CLIENT,
            ) {
                Ok(cfg) => cfg,
                Err(error) => {
                    tracing::warn!(event = "trigger_instance", instance = %id, result = "refused", error = %error);
                    continue;
                }
            };
            match self.instances.get_mut(&id) {
                Some(existing) => {
                    let changed = existing.resolved.instance.config_revision
                        != resolved.instance.config_revision
                        || existing.resolved.instance.trigger_descriptor_digest
                            != resolved.instance.trigger_descriptor_digest;
                    if changed && existing.supervisor.reconfigure(cfg, at).is_ok() {
                        existing.supervisor.stop("ConfigChanged", at);
                    }
                    existing.resolved = resolved;
                }
                None => {
                    let store = match FsStore::open(self.root.join(&id), &id) {
                        Ok(store) => store,
                        Err(error) => {
                            tracing::warn!(event = "trigger_instance", instance = %id, result = "store_unavailable", error = %error);
                            continue;
                        }
                    };
                    match Supervisor::recover(cfg, store, Box::new(SystemRandom::default()), at) {
                        Ok(supervisor) => {
                            self.instances.insert(
                                id.clone(),
                                Instance {
                                    supervisor,
                                    resolved,
                                    process: None,
                                    reported_checkpoint: None,
                                },
                            );
                        }
                        Err(error) => {
                            tracing::warn!(event = "trigger_instance", instance = %id, result = "recovery_refused", error = %error);
                            continue;
                        }
                    }
                }
            }
            self.apply_state(&id, at);
        }
        // An instance missing from a newer generation is stopped and removed (plan 8.1): it drains, then its state goes.
        let gone: Vec<String> = self
            .instances
            .keys()
            .filter(|id| !present.contains(*id))
            .cloned()
            .collect();
        for id in gone {
            if let Some(instance) = self.instances.get_mut(&id) {
                if !matches!(
                    instance.supervisor.phase(),
                    Phase::Removing { .. } | Phase::Removed
                ) {
                    instance.supervisor.begin_removal(at);
                }
            }
            self.drain(&id);
        }
        self.observed = desired.document.generation;
    }

    fn installed(&self, resolved: &Resolved) -> bool {
        resolved.registration.as_ref().is_some_and(|registration| {
            registration.package_digest == resolved.instance.package_digest
        }) && resolved.runtime.is_some()
            && self.context.is_some()
    }

    fn apply_state(&mut self, id: &str, at: u64) {
        let installed = match self.instances.get(id) {
            Some(instance) => self.installed(&instance.resolved),
            None => return,
        };
        let instance = self.instances.get_mut(id).expect("present");
        match instance.resolved.instance.desired_state {
            TriggerDesiredState::Running if installed => {
                instance.supervisor.resume(at);
                instance.supervisor.run(at);
            }
            TriggerDesiredState::Running => instance.supervisor.stop("PackageNotInstalled", at),
            TriggerDesiredState::Paused if !installed => {
                instance.supervisor.stop("PackageNotInstalled", at);
                instance.supervisor.pause(at);
            }
            TriggerDesiredState::Paused => instance.supervisor.pause(at),
            TriggerDesiredState::Stopped => instance.supervisor.stop("AssignmentStopped", at),
        }
        self.drain(id);
    }

    // ---- Control ----

    fn acknowledge(&mut self, ack: &TriggerSyncAck) {
        let at = now();
        let ids: Vec<String> = self.instances.keys().cloned().collect();
        for id in ids {
            let instance = self.instances.get_mut(&id).expect("present");
            for lease in ack.leases.iter().flatten().filter(|l| l.instance_id == id) {
                instance.supervisor.grant_lease(
                    Lease {
                        epoch: lease.lease_epoch,
                        renew_deadline_ms: lease.expires_at_unix_ms.saturating_sub(RENEW_MARGIN_MS),
                    },
                    at,
                );
            }
            instance.supervisor.on_sync_ack(ack, at);
            self.drain(&id);
        }
    }

    fn sync(&mut self) -> TriggerSync {
        let at = now();
        self.snapshot += 1;
        let mut reports = Vec::new();
        let mut checkpoints = Vec::new();
        let mut events = Vec::new();
        let mut pending = 0;
        let mut budget = CHECK_IN_EVENT_BYTES;
        let installed: BTreeMap<String, bool> = self
            .instances
            .iter()
            .map(|(id, i)| (id.clone(), self.installed(&i.resolved)))
            .collect();
        for (id, instance) in self.instances.iter_mut() {
            let mut report = instance.supervisor.report(at);
            let installed = installed[id];
            if !installed && report.phase != TriggerPhase::Removed {
                report.phase = TriggerPhase::Pending;
            }
            report.conditions.push(TriggerCondition {
                r#type: TriggerConditionType::Installed,
                status: if installed {
                    TriggerConditionStatus::True
                } else {
                    TriggerConditionStatus::False
                },
                reason: if installed {
                    "Installed".into()
                } else {
                    "PackageNotInstalled".into()
                },
                message: None,
                last_transition_time: olo_toolgate_trigger_supervisor::time::rfc3339(at),
            });
            reports.push(report);
            if let Some(checkpoint) = inline_checkpoint(id, instance) {
                checkpoints.push(checkpoint);
            }
            let batch = instance.supervisor.sync_events(budget.max(1), at);
            for record in &batch.events {
                budget =
                    budget.saturating_sub(serde_json::to_vec(record).map_or(0, |b| b.len() as u64));
            }
            pending += batch.pending;
            events.extend(batch.events);
            if budget == 0 {
                budget = 1;
            }
        }
        let digest = format!(
            "sha256:{}",
            crate::digest(&serde_json::to_vec(&reports).unwrap_or_default())
        );
        let snapshot = (!reports.is_empty()).then(|| TriggerSnapshotPage {
            snapshot_generation: self.snapshot,
            snapshot_digest: digest.clone(),
            page: 1,
            page_count: 1,
            page_digest: digest,
            instances: reports,
        });
        TriggerSync {
            observed_generation: self.observed,
            snapshot,
            checkpoints: (!checkpoints.is_empty()).then_some(checkpoints),
            events: (!events.is_empty()).then_some(events),
            pending,
        }
    }

    // ---- processes ----

    fn tick(&mut self) {
        let at = now();
        let ids: Vec<String> = self.instances.keys().cloned().collect();
        for id in ids {
            self.instances
                .get_mut(&id)
                .expect("present")
                .supervisor
                .tick(at);
            self.drain(&id);
        }
    }

    fn event(&mut self, event: Event) {
        let at = now();
        match event {
            Event::Frame(id, generation, frame) => {
                let Some(instance) = self.instances.get_mut(&id) else {
                    return;
                };
                if instance
                    .process
                    .as_ref()
                    .is_none_or(|p| p.generation != generation)
                {
                    return;
                }
                instance.supervisor.on_frame(frame, at);
                instance.supervisor.pump(at);
                self.drain(&id);
            }
            Event::Exit(id, generation) => {
                let Some(instance) = self.instances.get_mut(&id) else {
                    return;
                };
                if instance
                    .process
                    .as_ref()
                    .is_none_or(|p| p.generation != generation)
                {
                    return;
                }
                instance.process = None;
                instance.supervisor.on_exit(at);
                self.drain(&id);
            }
        }
    }

    /// Carries out what a supervisor asked for, and deletes a removed instance's state.
    fn drain(&mut self, id: &str) {
        let Some(instance) = self.instances.get_mut(id) else {
            return;
        };
        for action in instance.supervisor.take_actions() {
            match action {
                Action::Spawn(start) => {
                    self.spawned += 1;
                    let generation = self.spawned;
                    let instance = self.instances.get_mut(id).expect("present");
                    match spawn(
                        self.context.as_ref(),
                        &instance.resolved,
                        start,
                        id.to_owned(),
                        generation,
                        self.events.0.clone(),
                    ) {
                        Ok(process) => instance.process = Some(process),
                        Err(failure) => {
                            tracing::warn!(event = "trigger_process", instance = %id, result = "spawn_failed", error = ?failure);
                            instance.supervisor.on_exit(now());
                        }
                    }
                }
                Action::Send(frame) => self.write(id, encode_inbound(&frame).into_bytes()),
                Action::Forward { kind, frame } => {
                    if kind == "BROKER_REQUEST" {
                        let instance = self.instances.get(id).expect("present");
                        let access = instance
                            .resolved
                            .registration
                            .as_ref()
                            .map_or(LocalFileAccess::None, |r| r.file_access.clone());
                        let files = self.context.as_ref().and_then(|c| c.files.clone());
                        let line = serde_json::to_vec(&frame).unwrap_or_default();
                        if let Some(answer) =
                            crate::execution::trigger_broker(files.as_deref(), &access, &line)
                        {
                            self.write(id, answer);
                        }
                    } else {
                        // Author log frames are not forwarded verbatim: they may carry data the device must not log.
                        tracing::debug!(event = "trigger_log", instance = %id);
                    }
                }
                Action::Terminate | Action::Kill => {
                    let instance = self.instances.get_mut(id).expect("present");
                    if let Some(process) = instance.process.as_mut() {
                        if let Some(kill) = process.kill.take() {
                            let _ = kill.send(());
                        }
                    }
                }
            }
        }
        let instance = self.instances.get_mut(id).expect("present");
        // The supervisor's own tick may already have finished the removal (T13); either way the instance goes.
        if instance.supervisor.phase() == Phase::Removed {
            tracing::info!(event = "trigger_removed", instance = %id);
            self.instances.remove(id);
            return;
        }
        if matches!(instance.supervisor.phase(), Phase::Removing { .. })
            && instance.process.is_none()
        {
            match instance.supervisor.finish_removal(now()) {
                Ok(Some(report)) => {
                    tracing::info!(event = "trigger_removed", instance = %id, undrained = report.undrained);
                    self.instances.remove(id);
                }
                Ok(None) => {}
                Err(error) => {
                    tracing::warn!(event = "trigger_removed", instance = %id, error = %error)
                }
            }
        }
    }

    fn write(&mut self, id: &str, line: Vec<u8>) {
        if let Some(process) = self.instances.get(id).and_then(|i| i.process.as_ref()) {
            let _ = process.stdin.send(line);
        }
    }
}

/// The `ACCEPTED` checkpoint, inline when it is at most 4 KiB, reported once per change (plan 8.2, 8.6).
fn inline_checkpoint(id: &str, instance: &mut Instance) -> Option<TriggerCheckpointReport> {
    let accepted = instance.supervisor.accepted_checkpoint()?.clone();
    let key = (accepted.lease_epoch, accepted.after_outbox_seq);
    if instance.reported_checkpoint == Some(key) {
        return None;
    }
    let body = base64::engine::general_purpose::STANDARD
        .decode(&accepted.state)
        .ok()?;
    if body.len() > INLINE_CHECKPOINT_BYTES {
        return None;
    }
    instance.reported_checkpoint = Some(key);
    Some(TriggerCheckpointReport {
        instance_id: id.to_owned(),
        lease_epoch: accepted.lease_epoch,
        after_outbox_seq: accepted.after_outbox_seq,
        level: TriggerCheckpointLevel::Accepted,
        // Monotone in (epoch, outboxSeq): Control keeps only a newer version.
        version: (accepted.lease_epoch << 32) | (accepted.after_outbox_seq & 0xffff_ffff),
        digest: format!("sha256:{}", crate::digest(&body)),
        size_bytes: body.len() as u64,
        inline: Some(accepted.state),
    })
}

/// Creates the trigger's container and starts its process; frames and its end arrive as events.
fn spawn(
    context: Option<&TriggerContext>,
    resolved: &Resolved,
    start: olo_toolgate_contracts::v2::TriggerStartFrame,
    id: String,
    generation: u64,
    events: mpsc::UnboundedSender<Event>,
) -> Result<Process> {
    let context = context.ok_or(Failure::Unavailable)?.clone();
    let registration = resolved.registration.clone().ok_or(Failure::Unavailable)?;
    let runtime = resolved.runtime.clone().ok_or(Failure::Unavailable)?;
    let memory = resolved
        .descriptor
        .limits
        .memory_mi_b
        .unwrap_or(MEMORY_MI_B)
        .clamp(128, 2048);
    let command = adapters::trigger_arguments(&runtime.kind, &registration.entry_point, memory)?;
    let (stdin, mut lines) = mpsc::unbounded_channel::<Vec<u8>>();
    let (kill, mut killed) = oneshot::channel::<()>();
    tokio::spawn(async move {
        let created = context
            .engine
            .trigger_container(&runtime.image, &command, memory)
            .await;
        let (name, start_command) = match created {
            Ok(created) => created,
            Err(failure) => {
                tracing::warn!(event = "trigger_process", instance = %id, result = "create_failed", error = ?failure);
                let _ = events.send(Event::Exit(id, generation));
                return;
            }
        };
        let process =
            match TriggerProcess::spawn(start_command, TriggerExecutor::Client, start).await {
                Ok(process) => process,
                Err(_) => {
                    let _ = context.engine.remove_container(&name).await;
                    let _ = events.send(Event::Exit(id, generation));
                    return;
                }
            };
        let (mut child, mut writer, mut reader) = process.into_parts();
        let writing = tokio::spawn(async move {
            while let Some(line) = lines.recv().await {
                if writer.send_line(&line).await.is_err() {
                    break;
                }
            }
        });
        loop {
            tokio::select! {
                frame = reader.next_frame() => match frame {
                    Ok(Some(frame)) => {
                        if events.send(Event::Frame(id.clone(), generation, frame)).is_err() { break; }
                    }
                    Ok(None) | Err(_) => break,
                },
                _ = &mut killed => break,
            }
        }
        let _ = child.start_kill();
        let _ = child.wait().await;
        writing.abort();
        // The container outlives the attached CLI; it is always removed.
        let _ = context.engine.remove_container(&name).await;
        let _ = events.send(Event::Exit(id, generation));
    });
    Ok(Process {
        generation,
        stdin,
        kill: Some(kill),
    })
}

#[cfg(all(test, unix))]
#[path = "triggers_tests.rs"]
mod tests;
