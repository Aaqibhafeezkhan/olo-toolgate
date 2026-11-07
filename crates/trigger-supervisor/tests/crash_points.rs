// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Crash-point tests for transitions T1–T5 and T10 of plan 6.6.1, the crash and failure table that follows
//! it, and the review fixture of plan 6.6 ("A accepted, B rejected retryable, C accepted, then restart: B
//! must be re-emitted"). The in-memory disk injects a crash before or after each persistence point; the file
//! store is checked against the on-disk artifacts such a crash leaves (a torn append, a temp file that never
//! reached its rename).

mod common;

use common::{item_of, position, World, INSTANCE};
use olo_toolgate_contracts::v2::{
    TriggerCloudEvent, TriggerEventKind, TriggerTerminalRejectionPolicy,
};
use olo_toolgate_trigger_supervisor::memory::{Fault, OpKind};
use olo_toolgate_trigger_supervisor::store::{
    CheckpointLevel, EpochMark, Fence, FsStore, HaltRecord, HaltSide, OutboxEntry, Store,
    StoreError, StoredCheckpoint,
};
use olo_toolgate_trigger_supervisor::supervisor::{FailReason, Phase, LOSS_RECORD_TYPE};
use std::collections::BTreeSet;
use std::fs;
use std::path::PathBuf;

const HALT: TriggerTerminalRejectionPolicy = TriggerTerminalRejectionPolicy::Halt;
const SKIP: TriggerTerminalRejectionPolicy = TriggerTerminalRejectionPolicy::SkipAndRecord;

fn world(policy: TriggerTerminalRejectionPolicy, exec_bad: &[u64]) -> World {
    World::new(
        policy,
        4,
        exec_bad.iter().copied().collect(),
        BTreeSet::new(),
        64,
    )
}

/// Every in-flight record is ingested and every acknowledgement delivered.
fn round_trip(w: &mut World) {
    for e in 0..2 {
        w.send(e);
    }
    while !w.net.is_empty() {
        w.ingest(0);
    }
    while !w.acks.is_empty() {
        w.deliver_ack(0, false);
    }
    w.check();
}

fn entries(w: &World, e: usize) -> Vec<(u64, u64, String)> {
    w.execs[e]
        .store
        .disk()
        .entries
        .values()
        .map(|r| (r.lease_epoch, r.outbox_seq, r.event.id.clone()))
        .collect()
}

fn local_pos(w: &World, e: usize) -> u64 {
    w.execs[e]
        .store
        .disk()
        .local
        .as_ref()
        .map_or(1, |c| position(&c.state))
}

fn accepted_pos(w: &World, e: usize) -> u64 {
    w.execs[e]
        .store
        .disk()
        .accepted
        .as_ref()
        .map_or(1, |c| position(&c.state))
}

// ---------------------------------------------------------------------- T1

#[test]
fn t1_crash_before_the_append_leaves_no_record_and_no_sequence() {
    let mut w = world(SKIP, &[]);
    w.emit(0, true);
    w.arm(0, Some(OpKind::Append), Fault::CrashBefore);
    w.persist(0, 0);
    assert!(w.execs[0].sup.is_none(), "the executor crashed");
    assert!(
        entries(&w, 0).is_empty(),
        "S1: no outboxSeq exists without its record"
    );
    w.recover(0);
    // The checkpoint is still before the event, so the new process re-emits it under the same id.
    assert_eq!(w.execs[0].runtime.as_ref().unwrap().read_pos, 1);
    w.emit(0, true);
    w.pump(0);
    assert_eq!(
        entries(&w, 0),
        vec![(1, 1, "item-1".into())],
        "the sequence was never consumed"
    );
    w.check();
}

#[test]
fn t1_crash_after_the_fsync_before_emit_ack_is_re_emitted_and_deduplicated() {
    let mut w = world(SKIP, &[]);
    w.emit(0, true);
    w.arm(0, Some(OpKind::Append), Fault::CrashAfter);
    w.persist(0, 0);
    assert!(w.execs[0].sup.is_none());
    assert_eq!(
        entries(&w, 0),
        vec![(1, 1, "item-1".into())],
        "the record is durable"
    );
    assert_eq!(
        local_pos(&w, 0),
        1,
        "the SDK never saw it durable, so the checkpoint is before it"
    );
    w.recover(0);
    w.emit(0, true);
    w.pump(0);
    // A new emitSeq and a new outboxSeq, the same stable id.
    assert_eq!(
        entries(&w, 0),
        vec![(1, 1, "item-1".into()), (1, 2, "item-1".into())]
    );
    round_trip(&mut w);
    assert_eq!(w.ctrl.store_count.get(&1), Some(&1), "D: stored once");
    assert!(
        entries(&w, 0).is_empty(),
        "both copies acknowledged and truncated"
    );
}

#[test]
fn t1_sequences_are_gap_free_and_survive_truncation_and_restart() {
    let mut w = world(SKIP, &[]);
    for _ in 0..3 {
        w.emit(0, true);
    }
    w.pump(0);
    round_trip(&mut w);
    assert!(entries(&w, 0).is_empty());
    // There is no separate counter: the truncation's epoch mark carries the high outboxSeq.
    assert_eq!(
        w.execs[0].store.disk().epochs[&1],
        EpochMark {
            lease_epoch: 1,
            high_outbox_seq: 3,
            acked_outbox_seq: 3,
            fenced: None
        }
    );
    w.crash(0);
    w.recover(0);
    w.emit(0, true);
    w.pump(0);
    assert_eq!(entries(&w, 0), vec![(1, 4, "item-4".into())]);
}

// ---------------------------------------------------------------------- T2

#[test]
fn t2_a_retryable_rejection_persists_nothing_and_holds_the_barrier() {
    let mut w = world(SKIP, &[]);
    w.emit(0, true); // item 1
    w.emit(0, true); // item 2
    let before = w.execs[0].store.disk().log.len();
    w.arm(0, Some(OpKind::Append), Fault::FailClean);
    w.persist(0, 0); // item 1: disk full, rolled back
    assert_eq!(
        w.execs[0].store.disk().log.len(),
        before,
        "nothing was persisted"
    );
    assert!(w.execs[0].runtime.as_ref().unwrap().retry.contains(&1));
    w.pump(0); // item 2 durable
    w.checkpoint(0);
    assert_eq!(
        local_pos(&w, 0),
        1,
        "S2: no checkpoint passes the retryable emit"
    );
    w.retry(0, 0);
    w.pump(0);
    assert_eq!(
        local_pos(&w, 0),
        3,
        "the barrier advances once the retry is durable"
    );
    w.check();
}

#[test]
fn review_fixture_a_accepted_b_retryable_c_accepted_then_restart_re_emits_b() {
    let mut w = world(SKIP, &[]);
    for _ in 0..3 {
        w.emit(0, true); // A, B, C
    }
    w.persist(0, 0); // A
    w.arm(0, Some(OpKind::Append), Fault::FailClean);
    w.persist(0, 0); // B: retryable
    w.persist(0, 0); // C
    round_trip(&mut w);
    assert_eq!(w.ctrl.stored, BTreeSet::from([1, 3]));
    assert_eq!(
        (local_pos(&w, 0), accepted_pos(&w, 0)),
        (2, 2),
        "both levels stop before B"
    );
    w.crash(0);
    w.recover(0);
    assert_eq!(
        w.execs[0].runtime.as_ref().unwrap().read_pos,
        2,
        "the restart resumes at B"
    );
    for _ in 0..2 {
        w.emit(0, true); // B and C again
    }
    w.pump(0);
    round_trip(&mut w);
    assert_eq!(w.ctrl.stored, BTreeSet::from([1, 2, 3]));
    assert_eq!(w.ctrl.store_count[&3], 1, "C is deduplicated");
    assert_eq!(accepted_pos(&w, 0), 4);
}

// ---------------------------------------------------------------------- T3

#[test]
fn t3_a_terminal_rejection_under_skip_resolves_only_through_a_durable_loss_record() {
    let mut w = world(SKIP, &[2]);
    for _ in 0..3 {
        w.emit(0, true);
    }
    w.persist(0, 0); // item 1
    w.arm(0, Some(OpKind::Append), Fault::CrashBefore);
    w.persist(0, 0); // the loss record of item 2
    assert!(w.execs[0].sup.is_none());
    assert_eq!(
        local_pos(&w, 0),
        2,
        "S3: without a durable loss record the barrier stays before item 2"
    );
    w.recover(0);
    for _ in 0..3 {
        w.emit(0, true);
    }
    w.pump(0);
    let disk = w.execs[0].store.disk().clone();
    let loss: Vec<&OutboxEntry> = disk
        .entries
        .values()
        .filter(|r| r.is_loss_record())
        .collect();
    assert_eq!(loss.len(), 1);
    let event: &TriggerCloudEvent = &loss[0].event;
    assert_eq!(
        (event.r#type.as_str(), &event.tgkind, event.id.as_str()),
        (
            LOSS_RECORD_TYPE,
            &TriggerEventKind::Rejected,
            "item-2:rejected"
        )
    );
    assert_eq!(event.data["id"], "item-2");
    assert_eq!(event.data["reason"], "SchemaInvalid");
    assert_eq!(event.data["position"], "3");
    assert!(event.data["dataDigest"]
        .as_str()
        .unwrap()
        .starts_with("sha256:"));
    assert!(
        event.data.get("bad").is_none() && event.data.get("item").is_none(),
        "a loss record never carries the data"
    );
    assert_eq!(
        local_pos(&w, 0),
        5,
        "the loss record resolves the barrier at LOCAL"
    );
    round_trip(&mut w);
    assert_eq!(
        accepted_pos(&w, 0),
        5,
        "and at ACCEPTED once Control stored the disposition"
    );
    assert!(!w.ctrl.stored.contains(&2));
}

// ---------------------------------------------------------------------- T4

#[test]
fn t4_halt_persists_the_condition_appends_nothing_and_never_checkpoints_past_it() {
    let mut w = world(HALT, &[2]);
    for _ in 0..3 {
        w.emit(0, true);
    }
    w.pump(0);
    // The SDK races a checkpoint after item 3 before it handles STOP.
    w.checkpoint(0);
    let disk = w.execs[0].store.disk().clone();
    let halt = disk.halt.clone().expect("the condition is durable");
    assert_eq!(
        (
            halt.side,
            halt.event_id.as_str(),
            halt.reason.as_str(),
            halt.position.as_deref()
        ),
        (HaltSide::Executor, "item-2", "SchemaInvalid", Some("3"))
    );
    assert!(
        disk.entries.values().all(|r| item_of(&r.event.id) != 2),
        "T4 appends no record"
    );
    assert_eq!(
        local_pos(&w, 0),
        2,
        "S4: nothing after the halted event is checkpointed"
    );
    assert_eq!(
        w.execs[0].sup.as_ref().unwrap().phase(),
        Phase::Failed(FailReason::TerminalRejection)
    );
    w.check();
    // After a restart the instance stays failed: it does not run past the halted event.
    w.crash(0);
    w.recover(0);
    assert_eq!(
        w.execs[0].sup.as_ref().unwrap().phase(),
        Phase::Failed(FailReason::TerminalRejection)
    );
    assert!(w.execs[0].runtime.is_none());
    // Fixing the trigger and Run replays from the last committed checkpoint.
    w.exec_bad.clear();
    w.halted_items.clear();
    w.with_sup(0, |s, now| s.clear_halt(now).unwrap());
    assert_eq!(w.execs[0].runtime.as_ref().unwrap().read_pos, 2);
    for _ in 0..3 {
        w.emit(0, true); // items 2, 3 and 4
    }
    w.pump(0);
    round_trip(&mut w);
    assert_eq!(w.ctrl.stored, BTreeSet::from([1, 2, 3, 4]));
}

#[test]
fn t4_a_crash_before_the_halt_is_durable_halts_again_after_restart() {
    let mut w = world(HALT, &[1]);
    w.arm(0, Some(OpKind::WriteHalt), Fault::CrashBefore);
    w.emit(0, true);
    assert!(w.execs[0].sup.is_none());
    assert!(w.execs[0].store.disk().halt.is_none());
    w.recover(0);
    w.emit(0, true);
    assert!(w.execs[0].store.disk().halt.is_some());
    assert_eq!(local_pos(&w, 0), 1);
}

// ---------------------------------------------------------------------- T5

#[test]
fn t5_a_checkpoint_write_is_atomic_at_every_crash_point() {
    for fault in [Fault::CrashBefore, Fault::CrashAfter, Fault::FailClean] {
        let mut w = world(SKIP, &[]);
        w.emit(0, true);
        w.pump(0);
        assert_eq!(local_pos(&w, 0), 2);
        w.emit(0, false);
        w.arm(0, Some(OpKind::WriteLocal), fault);
        w.pump(0);
        w.checkpoint(0); // after item 2
        let expected = if fault == Fault::CrashAfter { 3 } else { 2 };
        assert_eq!(
            local_pos(&w, 0),
            expected,
            "{fault:?}: the old or the new checkpoint, never a mix"
        );
        w.check();
        if fault == Fault::FailClean {
            // A clean failure is retried on the next tick.
            w.tick(1_000);
            assert_eq!(local_pos(&w, 0), 3);
        }
    }
}

#[test]
fn t5_the_local_checkpoint_never_passes_an_earlier_unresolved_emit_completed_out_of_order() {
    let mut w = world(SKIP, &[]);
    for _ in 0..3 {
        w.emit(0, true);
    }
    w.persist(0, 2); // item 3 first
    w.persist(0, 1); // item 2
    assert_eq!(local_pos(&w, 0), 1, "L: item 1 is still unresolved");
    w.persist(0, 0); // item 1
    assert_eq!(local_pos(&w, 0), 4);
    let local = w.execs[0].store.disk().local.clone().unwrap();
    assert_eq!(
        (local.lease_epoch, local.after_outbox_seq, local.level),
        (1, 3, CheckpointLevel::Local)
    );
}

// ---------------------------------------------------------------------- T10

#[test]
fn t10_overflow_never_prunes_it_applies_backpressure() {
    let mut w = World::new(SKIP, 4, BTreeSet::new(), BTreeSet::new(), 2);
    for _ in 0..3 {
        w.emit(0, true);
    }
    w.pump(0);
    assert_eq!(entries(&w, 0).len(), 2);
    assert_eq!(
        w.execs[0].sup.as_ref().unwrap().queued(),
        vec![3],
        "the third emit waits (BLOCK)"
    );
    w.tick(31_000);
    assert!(
        w.execs[0].runtime.as_ref().unwrap().retry.contains(&3),
        "BackpressureTimeout is retryable"
    );
    assert_eq!(
        entries(&w, 0).len(),
        2,
        "P2: nothing was pruned to make room"
    );
    assert_eq!(local_pos(&w, 0), 3);
    round_trip(&mut w);
    w.retry(0, 0);
    w.pump(0);
    round_trip(&mut w);
    assert_eq!(w.ctrl.stored, BTreeSet::from([1, 2, 3]));
}

#[test]
fn t10_truncation_comes_before_accepted_upto_and_a_crash_between_ack_and_truncation_redelivers() {
    let mut w = world(SKIP, &[]);
    w.emit(0, true);
    w.pump(0);
    w.send(0);
    w.ingest(0);
    w.arm(0, Some(OpKind::MarkEpoch), Fault::FailClean);
    w.deliver_ack(0, false);
    assert_eq!(
        w.execs[0].runtime.as_ref().unwrap().accepted_upto,
        0,
        "no ACCEPTED_UPTO before the truncation is durable"
    );
    assert_eq!(entries(&w, 0).len(), 1);
    // Crash between Control's commit and the truncation: the record is re-sent and acknowledged as a duplicate.
    w.send(0);
    w.ingest(0);
    w.arm(0, Some(OpKind::MarkEpoch), Fault::CrashBefore);
    w.deliver_ack(0, false);
    assert!(w.execs[0].sup.is_none());
    w.recover(0);
    round_trip(&mut w);
    assert_eq!(w.ctrl.store_count[&1], 1);
    assert!(entries(&w, 0).is_empty());
}

#[test]
fn t10_a_fenced_epoch_is_pruned_and_only_that_epoch() {
    let mut w = world(SKIP, &[]);
    w.emit(0, true);
    w.pump(0);
    w.renew_epoch(9); // epoch 2 for the same executor; epoch 1 still drains
    w.tick(0);
    w.emit(0, true);
    w.pump(0);
    assert_eq!(
        entries(&w, 0)
            .iter()
            .map(|(ep, seq, _)| (*ep, *seq))
            .collect::<Vec<_>>(),
        vec![(1, 1), (2, 1)]
    );
    // The drain window of epoch 1 expires; Control fences it and only it.
    w.expire_drain();
    w.send(0);
    let fenced = w
        .net
        .iter()
        .position(|(_, r)| r.event.tgleaseepoch == 1)
        .unwrap();
    w.ingest(fenced);
    w.deliver_ack(0, false);
    let disk = w.execs[0].store.disk().clone();
    assert_eq!(disk.epochs[&1].fenced, Some(Fence::Pruned));
    assert_eq!(
        disk.entries.keys().copied().collect::<Vec<_>>(),
        vec![(2, 1)],
        "the current epoch's records stay queued"
    );
    // Item 1 was pruned unaccepted, so the instance resyncs from its ACCEPTED checkpoint (here: the start).
    assert_eq!(
        local_pos(&w, 0),
        1,
        "L: the LOCAL checkpoint falls back before the pruned record"
    );
    // The stopping process races a checkpoint after its durable emit; nothing more is committed from it.
    w.checkpoint(0);
    assert_eq!(local_pos(&w, 0), 1, "a resyncing process commits nothing");
    w.check();
    w.tick(1_000);
    for _ in 0..2 {
        w.emit(0, true);
    }
    w.pump(0);
    round_trip(&mut w);
    assert_eq!(w.ctrl.stored, BTreeSet::from([1, 2]));
}

#[test]
fn t10_compromised_records_are_fenced_kept_and_never_sent() {
    let mut w = world(SKIP, &[]);
    w.emit(0, true);
    w.pump(0);
    let mut ack = olo_toolgate_contracts::v2::TriggerSyncAck {
        generation: 1,
        max_check_in_bytes: 196_608,
        check_in_now: None,
        acked: None,
        dispositions: None,
        retry_later: None,
        leases: None,
        revoked: Some(vec![olo_toolgate_contracts::v2::TriggerRevocation {
            instance_id: INSTANCE.into(),
            reason: olo_toolgate_contracts::v2::TriggerRevocationReason::Compromised,
        }]),
        rejected: None,
        snapshot_pages: None,
        snapshot_requested: None,
    };
    w.with_sup(0, |s, now| s.on_sync_ack(&ack, now));
    ack.revoked = None;
    ack.rejected = Some(vec![olo_toolgate_contracts::v2::TriggerRejection {
        instance_id: INSTANCE.into(),
        lease_epoch: 1,
        reason: olo_toolgate_contracts::v2::TriggerRejectionReason::Staleleaseepoch,
    }]);
    w.with_sup(0, |s, now| s.on_sync_ack(&ack, now));
    let disk = w.execs[0].store.disk().clone();
    assert_eq!(disk.epochs[&1].fenced, Some(Fence::Compromised));
    assert_eq!(
        disk.entries.len(),
        1,
        "T10 prunes only for a non-compromise reason"
    );
    assert!(
        w.execs[0]
            .sup
            .as_ref()
            .unwrap()
            .sync_events(1 << 20, w.now)
            .events
            .is_empty(),
        "never sent again"
    );
    assert!(w.execs[0].sup.as_ref().unwrap().lease().is_none());
}

// ---------------------------------------------------------------------- the file store

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!(
        "toolgate-trigger-supervisor-{name}-{}",
        std::process::id()
    ));
    let _ = fs::remove_dir_all(&dir);
    dir
}

fn entry(epoch: u64, seq: u64) -> OutboxEntry {
    OutboxEntry {
        lease_epoch: epoch,
        outbox_seq: seq,
        written_at_unix_ms: 1,
        event: TriggerCloudEvent {
            specversion: "1.0".into(),
            id: format!("item-{seq}"),
            source: format!("toolgate:tenant:t1:trigger-instance:{INSTANCE}"),
            r#type: "examples.mail.message.received.v1".into(),
            subject: None,
            time: None,
            datacontenttype: "application/json".into(),
            dataschema: None,
            data: serde_json::json!({"item": seq}),
            tgleaseepoch: epoch,
            tgsequence: seq,
            tgkind: TriggerEventKind::Fired,
            traceparent: None,
        },
    }
}

fn checkpoint(level: CheckpointLevel, state: &str) -> StoredCheckpoint {
    StoredCheckpoint {
        lease_epoch: 1,
        after_outbox_seq: 2,
        level,
        schema_version: 1,
        state: state.into(),
    }
}

#[test]
fn fs_t1_a_torn_append_is_not_durable_and_its_sequence_is_reused() {
    let dir = temp_dir("torn");
    let mut store = FsStore::open(&dir, INSTANCE).unwrap();
    store.load().unwrap();
    store.append(&entry(1, 1)).unwrap();
    store.append(&entry(1, 2)).unwrap();
    // A crash in the middle of the third append: part of a line, no newline, never fsynced as a whole.
    let line = serde_json::to_string(&serde_json::json!({"entry": entry(1, 3)})).unwrap();
    let mut bytes = fs::read(dir.join("outbox.jsonl")).unwrap();
    bytes.extend_from_slice(&line.as_bytes()[..line.len() / 2]);
    fs::write(dir.join("outbox.jsonl"), bytes).unwrap();
    let mut store = FsStore::open(&dir, INSTANCE).unwrap();
    let recovered = store.load().unwrap();
    assert_eq!(
        recovered
            .entries
            .iter()
            .map(|e| e.outbox_seq)
            .collect::<Vec<_>>(),
        vec![1, 2]
    );
    assert_eq!(
        recovered.epochs[&1].high_outbox_seq, 2,
        "the next outboxSeq is the last durable one + 1"
    );
    store.append(&entry(1, 3)).unwrap();
    let mut store = FsStore::open(&dir, INSTANCE).unwrap();
    assert_eq!(
        store.load().unwrap().entries.len(),
        3,
        "the log is clean after recovery"
    );
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn fs_t1_t10_truncation_keeps_the_high_sequence_and_fencing_prunes_one_epoch() {
    let dir = temp_dir("truncate");
    let mut store = FsStore::open(&dir, INSTANCE).unwrap();
    store.load().unwrap();
    for seq in 1..=3 {
        store.append(&entry(1, seq)).unwrap();
    }
    store.append(&entry(2, 1)).unwrap();
    store
        .mark_epoch(&EpochMark {
            lease_epoch: 1,
            high_outbox_seq: 3,
            acked_outbox_seq: 3,
            fenced: None,
        })
        .unwrap();
    let mut reopened = FsStore::open(&dir, INSTANCE).unwrap();
    let recovered = reopened.load().unwrap();
    assert_eq!(
        recovered
            .entries
            .iter()
            .map(|e| (e.lease_epoch, e.outbox_seq))
            .collect::<Vec<_>>(),
        vec![(2, 1)]
    );
    assert_eq!(recovered.epochs[&1].high_outbox_seq, 3);
    reopened
        .mark_epoch(&EpochMark {
            lease_epoch: 2,
            high_outbox_seq: 1,
            acked_outbox_seq: 0,
            fenced: Some(Fence::Pruned),
        })
        .unwrap();
    let recovered = FsStore::open(&dir, INSTANCE).unwrap().load().unwrap();
    assert!(recovered.entries.is_empty());
    assert_eq!(recovered.epochs[&2].fenced, Some(Fence::Pruned));
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn fs_t5_a_checkpoint_that_never_reached_its_rename_is_ignored() {
    let dir = temp_dir("checkpoint");
    let mut store = FsStore::open(&dir, INSTANCE).unwrap();
    store.load().unwrap();
    store
        .write_checkpoint(&checkpoint(CheckpointLevel::Local, "2"))
        .unwrap();
    store
        .write_checkpoint(&checkpoint(CheckpointLevel::Accepted, "2"))
        .unwrap();
    // A crash after the temp file was written, before the rename.
    fs::write(
        dir.join("checkpoint-local.json.tmp"),
        b"{\"format\":\"toolgate.trigger-checkpoint.v1\",\"instanceId\":\"inst",
    )
    .unwrap();
    let mut store = FsStore::open(&dir, INSTANCE).unwrap();
    let recovered = store.load().unwrap();
    assert_eq!(recovered.local.unwrap().state, "2");
    assert_eq!(recovered.accepted.unwrap().level, CheckpointLevel::Accepted);
    assert!(!dir.join("checkpoint-local.json.tmp").exists());
    store
        .write_checkpoint(&checkpoint(CheckpointLevel::Local, "3"))
        .unwrap();
    assert_eq!(
        FsStore::open(&dir, INSTANCE)
            .unwrap()
            .load()
            .unwrap()
            .local
            .unwrap()
            .state,
        "3"
    );
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn fs_t4_the_halt_condition_survives_and_clears() {
    let dir = temp_dir("halt");
    let mut store = FsStore::open(&dir, INSTANCE).unwrap();
    store.load().unwrap();
    let halt = HaltRecord {
        side: HaltSide::Executor,
        lease_epoch: 1,
        event_id: "item-2".into(),
        reason: "SchemaInvalid".into(),
        position: Some("3".into()),
        outbox_seq: None,
    };
    store.write_halt(Some(&halt)).unwrap();
    assert_eq!(
        FsStore::open(&dir, INSTANCE).unwrap().load().unwrap().halt,
        Some(halt)
    );
    store.write_halt(None).unwrap();
    assert_eq!(
        FsStore::open(&dir, INSTANCE).unwrap().load().unwrap().halt,
        None
    );
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn fs_stores_of_another_format_or_instance_are_refused_and_left_untouched() {
    let dir = temp_dir("refuse");
    fs::create_dir_all(&dir).unwrap();
    fs::write(
        dir.join("outbox.jsonl"),
        b"{\"format\":\"toolgate.trigger-outbox.v0\",\"instanceId\":\"inst-1\"}\n",
    )
    .unwrap();
    assert!(matches!(
        FsStore::open(&dir, INSTANCE).unwrap().load(),
        Err(StoreError::Unsupported(_))
    ));
    assert!(fs::read(dir.join("outbox.jsonl"))
        .unwrap()
        .starts_with(b"{\"format\":\"toolgate.trigger-outbox.v0\""));
    fs::write(
        dir.join("outbox.jsonl"),
        b"{\"format\":\"toolgate.trigger-outbox.v1\",\"instanceId\":\"other\"}\n",
    )
    .unwrap();
    assert!(matches!(
        FsStore::open(&dir, INSTANCE).unwrap().load(),
        Err(StoreError::Unsupported(_))
    ));
    // A gap in the outbox sequences violates S1.
    let mut text =
        String::from("{\"format\":\"toolgate.trigger-outbox.v1\",\"instanceId\":\"inst-1\"}\n");
    for seq in [1, 3] {
        text.push_str(
            &serde_json::to_string(&serde_json::json!({"entry": entry(1, seq)})).unwrap(),
        );
        text.push('\n');
    }
    fs::write(dir.join("outbox.jsonl"), text).unwrap();
    assert!(matches!(
        FsStore::open(&dir, INSTANCE).unwrap().load(),
        Err(StoreError::Corrupt(_))
    ));
    fs::remove_dir_all(&dir).unwrap();
}
