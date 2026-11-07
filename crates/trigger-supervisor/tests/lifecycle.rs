// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Process lifecycle, health (6.8), restarts and crash loop (6.9), leases (7), backpressure and limits (6.7),
//! checkpoint migration (T12), removal (T13), `retryLater` and the instance report.

mod common;

use common::{config, INSTANCE};
use olo_toolgate_contracts::v2::{
    TriggerCheckpointLevel, TriggerConditionStatus, TriggerConditionType, TriggerEmitFrame,
    TriggerEmitStatus, TriggerHealthFrame, TriggerHealthStatus, TriggerHeartbeatFrame, TriggerMode,
    TriggerOverflow, TriggerPhase, TriggerReadyFrame, TriggerRetryLater, TriggerSyncAck,
    TriggerTerminalRejectionPolicy,
};
use olo_toolgate_trigger_supervisor::memory::MemStore;
use olo_toolgate_trigger_supervisor::restart::SeededRandom;
use olo_toolgate_trigger_supervisor::store::CheckpointLevel;
use olo_toolgate_trigger_supervisor::supervisor::{
    Action, ConfigError, FailReason, InstanceConfig, Lease, Phase, SupervisorError,
    KILL_AFTER_TERMINATE_MS, REMOVAL_DRAIN_MS, START_TIMEOUT_MS, UNHEALTHY_RESTART_MS,
};
use olo_toolgate_trigger_supervisor::{Inbound, Outbound, StoredCheckpoint, Supervisor};
use serde_json::json;

const T0: u64 = 10_000_000;
const SKIP: TriggerTerminalRejectionPolicy = TriggerTerminalRejectionPolicy::SkipAndRecord;

struct Rig {
    sup: Supervisor<MemStore>,
    store: MemStore,
    now: u64,
}

impl Rig {
    fn new(cfg: InstanceConfig) -> Self {
        let store = MemStore::new();
        let sup = Supervisor::recover(cfg, store.clone(), Box::new(SeededRandom(5)), T0).unwrap();
        Self {
            sup,
            store,
            now: T0,
        }
    }

    fn leased(cfg: InstanceConfig) -> Self {
        let mut rig = Self::new(cfg);
        rig.sup.grant_lease(
            Lease {
                epoch: 1,
                renew_deadline_ms: u64::MAX,
            },
            rig.now,
        );
        rig.sup.run(rig.now);
        rig
    }

    fn actions(&mut self) -> Vec<Action> {
        self.sup.take_actions()
    }

    fn sends(&mut self) -> Vec<Inbound> {
        self.actions()
            .into_iter()
            .filter_map(|a| {
                if let Action::Send(f) = a {
                    Some(f)
                } else {
                    None
                }
            })
            .collect()
    }

    fn at(&mut self, now: u64) {
        self.now = now;
        self.sup.tick(now);
    }

    fn frame(&mut self, frame: Outbound) {
        self.sup.on_frame(frame, self.now);
    }

    fn ready(&mut self) {
        self.frame(Outbound::Ready(TriggerReadyFrame {
            kind: "READY".into(),
        }));
    }

    fn emit(
        &mut self,
        emit_seq: u64,
        id: Option<&str>,
        data: serde_json::Value,
        position: Option<&str>,
    ) {
        self.frame(Outbound::Emit(TriggerEmitFrame {
            kind: "EMIT".into(),
            emit_seq,
            id: id.map(str::to_owned),
            subject: None,
            time: None,
            data,
            position: position.map(str::to_owned),
        }));
    }

    fn spawned(&mut self) -> bool {
        self.actions().iter().any(|a| matches!(a, Action::Spawn(_)))
    }
}

fn emit_ack(frames: &[Inbound]) -> Option<(TriggerEmitStatus, Option<String>, Option<u64>)> {
    frames.iter().find_map(|f| match f {
        Inbound::EmitAck(a) => Some((a.status.clone(), a.reason.clone(), a.outbox_seq)),
        _ => None,
    })
}

#[test]
fn the_start_frame_carries_the_lease_epoch_and_the_local_checkpoint() {
    let mut rig = Rig::new(config(SKIP, 64));
    rig.sup.run(rig.now);
    assert!(!rig.spawned(), "no lease, no process");
    rig.sup.grant_lease(
        Lease {
            epoch: 3,
            renew_deadline_ms: u64::MAX,
        },
        rig.now,
    );
    let start = rig
        .actions()
        .into_iter()
        .find_map(|a| {
            if let Action::Spawn(s) = a {
                Some(s)
            } else {
                None
            }
        })
        .unwrap();
    assert_eq!(
        (
            start.kind.as_str(),
            start.instance_id.as_str(),
            start.lease_epoch,
            start.checkpoint
        ),
        ("TRIGGER_START", INSTANCE, 3, None)
    );
    assert_eq!(rig.sup.phase(), Phase::Starting);
    rig.ready();
    assert_eq!(rig.sup.phase(), Phase::Running);
}

#[test]
fn readiness_timeout_hung_process_and_unhealthy_author_each_restart_with_backoff() {
    // No READY within startTimeoutMs.
    let mut rig = Rig::leased(config(SKIP, 64));
    assert!(rig.spawned());
    rig.at(T0 + START_TIMEOUT_MS);
    assert!(rig.actions().contains(&Action::Kill));
    rig.sup.on_exit(rig.now);
    assert!(matches!(rig.sup.phase(), Phase::Backoff { .. }));
    assert_eq!(rig.sup.report(rig.now).counters.restarts, 1);

    // Three missed heartbeats.
    let mut rig = Rig::leased(config(SKIP, 64));
    rig.ready();
    rig.actions();
    rig.at(T0 + 20_000);
    rig.frame(Outbound::Heartbeat(TriggerHeartbeatFrame {
        kind: "HEARTBEAT".into(),
    }));
    rig.at(T0 + 49_999);
    assert!(
        !rig.actions().contains(&Action::Kill),
        "heartbeats keep it alive"
    );
    rig.at(T0 + 50_000);
    assert!(
        rig.actions().contains(&Action::Kill),
        "30 s without a frame is hung"
    );

    // UNHEALTHY for five minutes: a graceful stop that counts as a failure.
    let mut rig = Rig::leased(config(SKIP, 64));
    rig.ready();
    rig.frame(Outbound::Health(TriggerHealthFrame {
        kind: "HEALTH".into(),
        status: TriggerHealthStatus::Unhealthy,
        reason: Some("imap down".into()),
    }));
    rig.actions();
    let mut now = T0;
    while now < T0 + UNHEALTHY_RESTART_MS {
        now += 10_000;
        rig.now = now;
        rig.frame(Outbound::Heartbeat(TriggerHeartbeatFrame {
            kind: "HEARTBEAT".into(),
        }));
        rig.sup.tick(now);
    }
    assert!(rig
        .sends()
        .iter()
        .any(|f| matches!(f, Inbound::Stop(s) if s.reason == "Unhealthy")));
    rig.sup.on_exit(rig.now);
    assert!(matches!(rig.sup.phase(), Phase::Backoff { .. }));
}

#[test]
fn author_degraded_health_maps_to_the_degraded_condition() {
    let mut rig = Rig::leased(config(SKIP, 64));
    rig.ready();
    rig.frame(Outbound::Health(TriggerHealthFrame {
        kind: "HEALTH".into(),
        status: TriggerHealthStatus::Degraded,
        reason: Some("imap reconnecting".into()),
    }));
    let report = rig.sup.report(rig.now);
    let degraded = report
        .conditions
        .iter()
        .find(|c| c.r#type == TriggerConditionType::Degraded)
        .unwrap();
    assert_eq!(
        (
            &degraded.status,
            degraded.reason.as_str(),
            degraded.message.as_deref()
        ),
        (
            &TriggerConditionStatus::True,
            "AuthorReported",
            Some("imap reconnecting")
        )
    );
    assert_eq!(report.phase, TriggerPhase::Running);
}

#[test]
fn stop_sends_stop_then_sigterm_after_the_grace_then_sigkill() {
    let mut rig = Rig::leased(config(SKIP, 64));
    rig.ready();
    rig.actions();
    rig.sup.stop("AssignmentStopped", rig.now);
    assert!(matches!(rig.sends().as_slice(), [Inbound::Stop(s)] if s.grace_ms == 10_000));
    rig.at(T0 + 9_999);
    assert!(rig.actions().is_empty());
    rig.at(T0 + 10_000);
    assert_eq!(rig.actions(), vec![Action::Terminate]);
    rig.at(T0 + 10_000 + KILL_AFTER_TERMINATE_MS);
    assert_eq!(rig.actions(), vec![Action::Kill]);
    rig.sup.on_exit(rig.now);
    assert_eq!(rig.sup.phase(), Phase::Stopped);
    assert_eq!(
        rig.sup.report(rig.now).counters.restarts,
        0,
        "a requested stop is not a failure"
    );
    // Events emitted after STOP are rejected.
}

#[test]
fn five_failed_starts_in_ten_minutes_are_a_crash_loop_until_run_resets_it() {
    let mut rig = Rig::leased(config(SKIP, 64));
    let mut spawns = 0;
    for _ in 0..10 {
        if rig.spawned() {
            spawns += 1;
            rig.sup.on_exit(rig.now);
        }
        if rig.sup.phase() == Phase::Failed(FailReason::CrashLoop) {
            break;
        }
        rig.at(rig.now + 30_000); // past any jittered delay of the first attempts
    }
    assert_eq!(spawns, 5);
    assert_eq!(rig.sup.phase(), Phase::Failed(FailReason::CrashLoop));
    rig.at(rig.now + 60 * 60_000);
    assert!(!rig.spawned(), "a crash loop does not restart by itself");
    rig.sup.reset_crash_loop(rig.now);
    assert!(rig.spawned());
}

#[test]
fn lease_expiry_stops_the_process_and_a_new_epoch_restarts_it_without_a_failure() {
    let mut rig = Rig::new(config(SKIP, 64));
    rig.sup.grant_lease(
        Lease {
            epoch: 1,
            renew_deadline_ms: T0 + 40_000,
        },
        rig.now,
    );
    rig.sup.run(rig.now);
    rig.ready();
    rig.actions();
    rig.now = T0 + 39_000;
    rig.frame(Outbound::Heartbeat(TriggerHeartbeatFrame {
        kind: "HEARTBEAT".into(),
    }));
    rig.at(T0 + 40_000);
    assert!(rig
        .sends()
        .iter()
        .any(|f| matches!(f, Inbound::Stop(s) if s.reason == "LeaseExpired")));
    rig.sup.on_exit(rig.now);
    assert!(!rig.spawned(), "no restart without a lease");
    rig.sup.grant_lease(
        Lease {
            epoch: 2,
            renew_deadline_ms: T0 + 120_000,
        },
        rig.now,
    );
    let start = rig
        .actions()
        .into_iter()
        .find_map(|a| {
            if let Action::Spawn(s) = a {
                Some(s)
            } else {
                None
            }
        })
        .unwrap();
    assert_eq!(start.lease_epoch, 2);
    rig.ready();
    rig.actions();
    rig.sup.grant_lease(
        Lease {
            epoch: 3,
            renew_deadline_ms: T0 + 120_000,
        },
        rig.now,
    );
    assert!(rig
        .sends()
        .iter()
        .any(|f| matches!(f, Inbound::Stop(s) if s.reason == "LeaseEpochChanged")));
    rig.sup.on_exit(rig.now);
    assert!(rig.spawned(), "restarted at once under the new epoch");
    assert_eq!(rig.sup.report(rig.now).counters.restarts, 0);
    rig.sup.grant_lease(
        Lease {
            epoch: 2,
            renew_deadline_ms: u64::MAX,
        },
        rig.now,
    );
    assert_eq!(
        rig.sup.lease().unwrap().epoch,
        3,
        "an older epoch never replaces a newer one"
    );
}

#[test]
fn emit_protocol_violations_kill_the_process() {
    let mut rig = Rig::leased(config(SKIP, 64));
    rig.ready();
    rig.actions();
    rig.emit(2, Some("a"), json!({"item": 1}), None);
    assert!(
        rig.actions().contains(&Action::Kill),
        "emitSeq must start at 1 and have no gap"
    );
}

#[test]
fn missing_ids_are_generated_once_and_kept_across_retries() {
    let mut cfg = config(SKIP, 64);
    cfg.max_events_per_hour = 1;
    let mut rig = Rig::leased(cfg);
    rig.ready();
    rig.emit(1, None, json!({"item": 1}), None);
    rig.emit(2, None, json!({"item": 2}), None);
    let frames = rig.sends();
    assert!(frames.iter().any(|f| matches!(f, Inbound::EmitAck(a) if a.emit_seq == 2 && a.status == TriggerEmitStatus::RetryableRejection && a.reason.as_deref() == Some("RateLimited"))));
    rig.now += 3_600_000; // the bucket refills
    rig.emit(2, None, json!({"item": 2}), None);
    rig.sup.pump(rig.now);
    let ids: Vec<String> = rig.sup.outbox().map(|e| e.event.id.clone()).collect();
    assert_eq!(ids.len(), 2);
    assert!(
        ids.iter()
            .all(|id| id.starts_with("evt-") && id.len() == 36)
            && ids[0] != ids[1]
    );
    // A retry must not change the id.
    rig.emit(3, Some("x"), json!({"item": 3}), None);
    rig.emit(3, Some("y"), json!({"item": 3}), None);
    assert!(rig.actions().contains(&Action::Kill));
}

#[test]
fn terminal_classes_and_the_cloud_event_envelope() {
    let mut rig = Rig::leased(config(SKIP, 64));
    rig.ready();
    rig.actions();
    rig.emit(1, Some("big"), json!({"item": "x".repeat(17_000)}), None);
    assert_eq!(emit_ack(&rig.sends()), None, "queued for its loss record");
    rig.sup.pump(rig.now);
    assert_eq!(
        emit_ack(&rig.sends()),
        Some((
            TriggerEmitStatus::TerminalRejection,
            Some("EventTooLarge".into()),
            Some(1)
        ))
    );
    rig.emit(2, Some("ok"), json!({"item": 2}), Some("3"));
    rig.sup.pump(rig.now);
    assert_eq!(
        emit_ack(&rig.sends()),
        Some((TriggerEmitStatus::LocalDurable, None, Some(2)))
    );
    let event = &rig.sup.outbox().last().unwrap().event;
    assert_eq!(
        event.source,
        format!("toolgate:tenant:t1:trigger-instance:{INSTANCE}")
    );
    assert_eq!(
        (event.r#type.as_str(), event.tgleaseepoch, event.tgsequence),
        ("examples.mail.message.received.v1", 1, 2)
    );
    assert!(event.time.is_some() && event.dataschema.is_some());
    let report = rig.sup.report(rig.now);
    assert_eq!((report.counters.emitted, report.counters.rejected), (1, 1));
}

#[test]
fn a_drop_policy_needs_skip_and_record_and_drops_into_a_loss_record() {
    let mut cfg = config(TriggerTerminalRejectionPolicy::Halt, 1);
    cfg.overflow = TriggerOverflow::DropNewest;
    let refused = Supervisor::recover(cfg.clone(), MemStore::new(), Box::new(SeededRandom(1)), T0);
    assert!(matches!(
        refused,
        Err(SupervisorError::Config(
            ConfigError::DropPolicyNeedsSkipAndRecord
        ))
    ));
    cfg.policy = SKIP;
    let mut rig = Rig::leased(cfg);
    rig.ready();
    rig.emit(1, Some("a"), json!({"item": 1}), None);
    rig.sup.pump(rig.now);
    rig.actions();
    rig.emit(2, Some("b"), json!({"item": 2}), None);
    rig.sup.pump(rig.now);
    assert_eq!(
        emit_ack(&rig.sends()),
        Some((
            TriggerEmitStatus::TerminalRejection,
            Some("Dropped".into()),
            Some(2)
        ))
    );
    let report = rig.sup.report(rig.now);
    assert_eq!(report.counters.dropped, 1);
    let degraded = report
        .conditions
        .iter()
        .find(|c| c.r#type == TriggerConditionType::Degraded)
        .unwrap();
    assert_eq!(
        degraded.reason, "Backpressure",
        "a full outbox reports backpressure first"
    );
    assert!(
        rig.sup.outbox().last().unwrap().is_loss_record(),
        "the loss record used the reserved slice"
    );
}

#[test]
fn retry_later_defers_resending_and_the_size_budget_pages_events() {
    let mut rig = Rig::leased(config(SKIP, 64));
    rig.ready();
    for seq in 1..=3 {
        rig.emit(seq, Some(&format!("e{seq}")), json!({"item": seq}), None);
    }
    rig.sup.pump(rig.now);
    assert_eq!(
        rig.sup.sync_events(1, rig.now).events.len(),
        1,
        "at least one record, then the budget"
    );
    assert_eq!(rig.sup.sync_events(1, rig.now).pending, 2);
    let ack = TriggerSyncAck {
        generation: 1,
        max_check_in_bytes: 196_608,
        check_in_now: None,
        acked: None,
        dispositions: None,
        retry_later: Some(vec![TriggerRetryLater {
            instance_id: INSTANCE.into(),
            lease_epoch: 1,
            from_outbox_seq: 2,
            retry_after_ms: 5_000,
        }]),
        leases: None,
        revoked: None,
        rejected: None,
        snapshot_pages: None,
        snapshot_requested: None,
    };
    rig.sup.on_sync_ack(&ack, rig.now);
    let batch = rig.sup.sync_events(1 << 20, rig.now);
    assert_eq!(
        (
            batch
                .events
                .iter()
                .map(|r| r.event.tgsequence)
                .collect::<Vec<_>>(),
            batch.pending
        ),
        (vec![1], 2)
    );
    assert_eq!(
        rig.sup.sync_events(1 << 20, rig.now + 5_000).events.len(),
        3
    );
}

#[test]
fn push_triggers_get_reconcile_on_the_resync_timer() {
    let mut cfg = config(SKIP, 64);
    cfg.mode = TriggerMode::Push;
    cfg.resync_interval_ms = Some(60_000);
    let mut rig = Rig::leased(cfg);
    rig.ready();
    rig.actions();
    rig.frame(Outbound::Heartbeat(TriggerHeartbeatFrame {
        kind: "HEARTBEAT".into(),
    }));
    rig.at(T0 + 20_000);
    assert!(rig.sends().is_empty());
    for t in [30_000, 40_000, 50_000, 60_000] {
        rig.now = T0 + t;
        rig.frame(Outbound::Heartbeat(TriggerHeartbeatFrame {
            kind: "HEARTBEAT".into(),
        }));
    }
    rig.at(T0 + 60_000);
    assert!(rig
        .sends()
        .iter()
        .any(|f| matches!(f, Inbound::Reconcile(_))));
    assert!(rig.sup.report(rig.now).last_reconcile_at.is_some());
}

#[test]
fn t12_a_checkpoint_of_another_schema_never_starts_until_migrated_or_discarded() {
    let store = MemStore::new();
    store.disk().local = Some(StoredCheckpoint {
        lease_epoch: 1,
        after_outbox_seq: 0,
        level: CheckpointLevel::Local,
        schema_version: 1,
        state: "old".into(),
    });
    let mut cfg = config(SKIP, 64);
    cfg.checkpoint_schema_version = 2;
    let mut sup = Supervisor::recover(cfg, store.clone(), Box::new(SeededRandom(1)), T0).unwrap();
    sup.grant_lease(
        Lease {
            epoch: 1,
            renew_deadline_ms: u64::MAX,
        },
        T0,
    );
    sup.run(T0);
    assert_eq!(
        sup.phase(),
        Phase::Failed(FailReason::CheckpointSchemaMismatch),
        "M"
    );
    assert!(sup.take_actions().is_empty());
    sup.migrate_checkpoints(2, |old| format!("{old}-v2"), T0)
        .unwrap();
    sup.reset_crash_loop(T0);
    let mut cfg = config(SKIP, 64);
    cfg.checkpoint_schema_version = 2;
    sup.reconfigure(cfg, T0).unwrap();
    sup.tick(T0);
    let start = sup
        .take_actions()
        .into_iter()
        .find_map(|a| {
            if let Action::Spawn(s) = a {
                Some(s)
            } else {
                None
            }
        })
        .unwrap();
    assert_eq!(start.checkpoint.as_deref(), Some("old-v2"));
    assert_eq!(store.disk().local.as_ref().unwrap().schema_version, 2);
    // Discarding instead starts from the beginning.
    let store = MemStore::new();
    store.disk().local = Some(StoredCheckpoint {
        lease_epoch: 1,
        after_outbox_seq: 0,
        level: CheckpointLevel::Local,
        schema_version: 1,
        state: "old".into(),
    });
    let mut cfg = config(SKIP, 64);
    cfg.checkpoint_schema_version = 2;
    let mut sup = Supervisor::recover(cfg, store.clone(), Box::new(SeededRandom(1)), T0).unwrap();
    sup.discard_checkpoints(T0).unwrap();
    sup.grant_lease(
        Lease {
            epoch: 1,
            renew_deadline_ms: u64::MAX,
        },
        T0,
    );
    sup.run(T0);
    let start = sup
        .take_actions()
        .into_iter()
        .find_map(|a| {
            if let Action::Spawn(s) = a {
                Some(s)
            } else {
                None
            }
        })
        .unwrap();
    assert_eq!(start.checkpoint, None);
    assert!(store.disk().local.is_none());
}

#[test]
fn t13_removal_drains_then_reports_what_was_not_drained() {
    let mut rig = Rig::leased(config(SKIP, 64));
    rig.ready();
    for seq in 1..=2 {
        rig.emit(seq, Some(&format!("e{seq}")), json!({"item": seq}), None);
    }
    rig.sup.pump(rig.now);
    rig.actions();
    rig.sup.begin_removal(rig.now);
    assert!(rig
        .sends()
        .iter()
        .any(|f| matches!(f, Inbound::Stop(s) if s.reason == "Removed")));
    rig.sup.on_exit(rig.now);
    assert_eq!(
        rig.sup.finish_removal(rig.now).unwrap(),
        None,
        "still draining"
    );
    assert_eq!(
        rig.sup.sync_events(1 << 20, rig.now).events.len(),
        2,
        "records still go to Control while removing"
    );
    let report = rig
        .sup
        .finish_removal(rig.now + REMOVAL_DRAIN_MS)
        .unwrap()
        .unwrap();
    assert_eq!(
        report.undrained, 2,
        "X: undrained records are counted, never dropped silently"
    );
    assert_eq!(rig.sup.phase(), Phase::Removed);
    assert!(rig.store.disk().entries.is_empty());
    assert_eq!(rig.sup.report(rig.now).phase, TriggerPhase::Removed);
}

#[test]
fn checkpoint_acks_name_each_level() {
    let mut rig = Rig::leased(config(SKIP, 64));
    rig.ready();
    rig.emit(1, Some("a"), json!({"item": 1}), None);
    rig.frame(Outbound::Checkpoint(
        olo_toolgate_contracts::v2::TriggerCheckpointFrame {
            kind: "CHECKPOINT".into(),
            checkpoint_id: 7,
            after_emit_seq: 1,
            state: "2".into(),
        },
    ));
    assert!(
        !rig.sends()
            .iter()
            .any(|f| matches!(f, Inbound::CheckpointAck(_))),
        "not before the emit is durable"
    );
    rig.sup.pump(rig.now);
    assert!(rig.sends().iter().any(|f| matches!(f, Inbound::CheckpointAck(a) if a.checkpoint_id == 7 && a.level == TriggerCheckpointLevel::Local)));
    let ack = TriggerSyncAck {
        generation: 1,
        max_check_in_bytes: 196_608,
        check_in_now: None,
        acked: Some(vec![olo_toolgate_contracts::v2::TriggerOutboxAck {
            instance_id: INSTANCE.into(),
            lease_epoch: 1,
            outbox_seq: 1,
        }]),
        dispositions: None,
        retry_later: None,
        leases: None,
        revoked: None,
        rejected: None,
        snapshot_pages: None,
        snapshot_requested: None,
    };
    rig.sup.on_sync_ack(&ack, rig.now);
    let frames = rig.sends();
    assert!(frames
        .iter()
        .any(|f| matches!(f, Inbound::AcceptedUpTo(a) if a.emit_seq == 1)));
    assert!(frames.iter().any(|f| matches!(f, Inbound::CheckpointAck(a) if a.checkpoint_id == 7 && a.level == TriggerCheckpointLevel::Accepted)));
    let accepted = rig.sup.accepted_checkpoint().unwrap();
    assert_eq!(
        (
            accepted.lease_epoch,
            accepted.after_outbox_seq,
            accepted.state.as_str()
        ),
        (1, 1, "2")
    );
}
