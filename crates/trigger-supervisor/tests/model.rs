// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Gate G-STATE item 2 (plan 6.6.1): stateful property tests of the supervisor against
//! `docs/specs/trigger_delivery.tla`, with a fake SDK runtime and a fake Control.
//!
//! Each case draws a policy, the items the supervisor rejects (schema-invalid) and the items Control rejects
//! on re-validation, then a sequence of steps: emits with and without positions, explicit checkpoints,
//! out-of-order outbox completions, retries, check-ins, ingest in any order, lost and duplicated records and
//! acknowledgements, crash faults armed at every persistence point (before and after the write), clean
//! storage failures, executor and process crashes, epoch renewals with queued records, failover,
//! revocation, reinstallation, expiry of the same-executor drain window, a process that keeps racing frames
//! between `STOP` and its exit, Control checkpoints and time. After every step [`World::check`] asserts
//! S1, S3, S4, L, I, P, P2, C, D and the sequence-namespace rule; acknowledgements and `ACCEPTED_UPTO`
//! frames are checked as they are delivered. Each case then heals every fault and runs fairly to check the
//! liveness property `EventuallyStored`.

mod common;

use common::World;
use olo_toolgate_contracts::v2::TriggerTerminalRejectionPolicy;
use olo_toolgate_trigger_supervisor::memory::{Fault, OpKind};
use proptest::prelude::*;
use proptest::test_runner::Config;
use proptest_state_machine::{prop_state_machine, ReferenceStateMachine, StateMachineTest};
use std::collections::BTreeSet;

const N: u64 = 4;
const MAX_EPOCH: u64 = 4;
const MAX_CRASH: u32 = 4;
const MAX_DROP: u32 = 4;

#[derive(Debug, Clone)]
struct Reference {
    policy: TriggerTerminalRejectionPolicy,
    exec_bad: BTreeSet<u64>,
    ctrl_bad: BTreeSet<u64>,
    max_records: u64,
    crashes: u32,
    drops: u32,
}

#[derive(Debug, Clone)]
enum Step {
    Emit {
        e: usize,
        with_position: bool,
    },
    Checkpoint {
        e: usize,
    },
    Retry {
        e: usize,
        pick: usize,
    },
    Persist {
        e: usize,
        pick: usize,
    },
    Pump {
        e: usize,
    },
    Send {
        e: usize,
    },
    Ingest {
        pick: usize,
    },
    DeliverAck {
        pick: usize,
        duplicate: bool,
    },
    Drop {
        pick: usize,
    },
    Tick {
        ms: u64,
    },
    Arm {
        e: usize,
        kind: Option<OpKind>,
        fault: Fault,
    },
    CrashExecutor {
        e: usize,
    },
    Recover {
        e: usize,
    },
    KillProcess {
        e: usize,
    },
    ControlCheckpoint {
        e: usize,
    },
    RenewEpoch,
    Failover {
        e: usize,
    },
    Revoke {
        e: usize,
    },
    Reinstall,
    ExitStopped {
        e: usize,
    },
    ExpireDrain,
}

fn op_kind() -> impl Strategy<Value = Option<OpKind>> {
    prop_oneof![
        Just(None),
        Just(Some(OpKind::Append)),
        Just(Some(OpKind::MarkEpoch)),
        Just(Some(OpKind::WriteLocal)),
        Just(Some(OpKind::WriteAccepted)),
        Just(Some(OpKind::WriteHalt)),
    ]
}

impl ReferenceStateMachine for Reference {
    type State = Reference;
    type Transition = Step;

    fn init_state() -> BoxedStrategy<Self::State> {
        (
            prop_oneof![
                Just(TriggerTerminalRejectionPolicy::Halt),
                Just(TriggerTerminalRejectionPolicy::SkipAndRecord)
            ],
            proptest::collection::btree_set(1..=N, 0..=1),
            proptest::collection::btree_set(1..=N, 0..=1),
            prop_oneof![Just(2u64), Just(3), Just(64)],
        )
            .prop_map(|(policy, exec_bad, ctrl_bad, max_records)| {
                let ctrl_bad = ctrl_bad.difference(&exec_bad).copied().collect();
                Reference {
                    policy,
                    exec_bad,
                    ctrl_bad,
                    max_records,
                    crashes: 0,
                    drops: 0,
                }
            })
            .boxed()
    }

    fn transitions(state: &Self::State) -> BoxedStrategy<Self::Transition> {
        let e = 0..2usize;
        let pick = 0..8usize;
        let mut steps = vec![
            (
                8,
                (e.clone(), any::<bool>())
                    .prop_map(|(e, with_position)| Step::Emit { e, with_position })
                    .boxed(),
            ),
            (3, e.clone().prop_map(|e| Step::Checkpoint { e }).boxed()),
            (
                3,
                (e.clone(), pick.clone())
                    .prop_map(|(e, pick)| Step::Retry { e, pick })
                    .boxed(),
            ),
            (
                8,
                (e.clone(), pick.clone())
                    .prop_map(|(e, pick)| Step::Persist { e, pick })
                    .boxed(),
            ),
            (2, e.clone().prop_map(|e| Step::Pump { e }).boxed()),
            (6, e.clone().prop_map(|e| Step::Send { e }).boxed()),
            (
                8,
                pick.clone().prop_map(|pick| Step::Ingest { pick }).boxed(),
            ),
            (
                8,
                (pick.clone(), prop::bool::weighted(0.2))
                    .prop_map(|(pick, duplicate)| Step::DeliverAck { pick, duplicate })
                    .boxed(),
            ),
            (
                2,
                prop_oneof![
                    Just(0u64),
                    Just(1_000),
                    Just(11_000),
                    Just(31_000),
                    Just(45_000)
                ]
                .prop_map(|ms| Step::Tick { ms })
                .boxed(),
            ),
            (2, e.clone().prop_map(|e| Step::Recover { e }).boxed()),
            (
                2,
                e.clone()
                    .prop_map(|e| Step::ControlCheckpoint { e })
                    .boxed(),
            ),
            (1, Just(Step::RenewEpoch).boxed()),
            (1, e.clone().prop_map(|e| Step::Failover { e }).boxed()),
            (1, e.clone().prop_map(|e| Step::Revoke { e }).boxed()),
            (2, e.clone().prop_map(|e| Step::ExitStopped { e }).boxed()),
            (1, Just(Step::ExpireDrain).boxed()),
        ];
        if state.crashes < MAX_CRASH {
            steps.push((
                3,
                (
                    e.clone(),
                    op_kind(),
                    prop_oneof![
                        Just(Fault::CrashBefore),
                        Just(Fault::CrashAfter),
                        Just(Fault::FailClean)
                    ],
                )
                    .prop_map(|(e, kind, fault)| Step::Arm { e, kind, fault })
                    .boxed(),
            ));
            steps.push((1, e.clone().prop_map(|e| Step::CrashExecutor { e }).boxed()));
            steps.push((1, e.clone().prop_map(|e| Step::KillProcess { e }).boxed()));
            steps.push((1, Just(Step::Reinstall).boxed()));
        }
        if state.drops < MAX_DROP {
            steps.push((2, pick.prop_map(|pick| Step::Drop { pick }).boxed()));
        }
        prop::strategy::Union::new_weighted(steps).boxed()
    }

    fn apply(mut state: Self::State, step: &Self::Transition) -> Self::State {
        match step {
            Step::Arm { .. }
            | Step::CrashExecutor { .. }
            | Step::KillProcess { .. }
            | Step::Reinstall => state.crashes += 1,
            Step::Drop { .. } => state.drops += 1,
            _ => {}
        }
        state
    }
}

struct Harness;

impl StateMachineTest for Harness {
    type SystemUnderTest = World;
    type Reference = Reference;

    fn init_test(state: &Reference) -> World {
        let mut world = World::new(
            state.policy.clone(),
            N,
            state.exec_bad.clone(),
            state.ctrl_bad.clone(),
            state.max_records,
        );
        world.check();
        world
    }

    fn apply(mut world: World, _state: &Reference, step: Step) -> World {
        match step {
            Step::Emit { e, with_position } => world.emit(e, with_position),
            Step::Checkpoint { e } => world.checkpoint(e),
            Step::Retry { e, pick } => world.retry(e, pick),
            Step::Persist { e, pick } => world.persist(e, pick),
            Step::Pump { e } => world.pump(e),
            Step::Send { e } => world.send(e),
            Step::Ingest { pick } => world.ingest(pick),
            Step::DeliverAck { pick, duplicate } => world.deliver_ack(pick, duplicate),
            Step::Drop { pick } => world.drop_message(pick),
            Step::Tick { ms } => world.tick(ms),
            Step::Arm { e, kind, fault } => world.arm(e, kind, fault),
            Step::CrashExecutor { e } => world.crash(e),
            Step::Recover { e } => world.recover(e),
            Step::KillProcess { e } => world.kill_process(e),
            Step::ControlCheckpoint { e } => world.control_checkpoint(e),
            Step::RenewEpoch => world.renew_epoch(MAX_EPOCH),
            Step::Failover { e } => world.failover(e, MAX_EPOCH),
            Step::Revoke { e } => world.revoke(e),
            Step::Reinstall => world.reinstall(MAX_EPOCH),
            Step::ExitStopped { e } => world.exit_stopped(e),
            Step::ExpireDrain => world.expire_drain(),
        }
        // After every step (check() also records the outbox it saw, to detect pruning on the next step).
        world.check();
        world
    }

    fn teardown(mut world: World, state: Reference) {
        world.settle(60);
        let bad: BTreeSet<u64> = state.exec_bad.union(&state.ctrl_bad).copied().collect();
        match state.policy {
            TriggerTerminalRejectionPolicy::SkipAndRecord => {
                for item in 1..=N {
                    if !bad.contains(&item) {
                        assert!(
                            world.ctrl.stored.contains(&item),
                            "EventuallyStored: item {item} never stored"
                        );
                    }
                }
            }
            TriggerTerminalRejectionPolicy::Halt => {
                if bad.is_empty() {
                    for item in 1..=N {
                        assert!(
                            world.ctrl.stored.contains(&item),
                            "EventuallyStored: item {item} never stored"
                        );
                    }
                }
            }
        }
    }
}

prop_state_machine! {
    #![proptest_config(Config {
        cases: std::env::var("TRIGGER_MODEL_CASES").ok().and_then(|v| v.parse().ok()).unwrap_or(512),
        failure_persistence: None,
        .. Config::default()
    })]

    #[test]
    fn supervisor_matches_the_delivery_model(sequential 1..120 => Harness);
}
