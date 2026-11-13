// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The trigger runner against a fake sandbox whose trigger container is a shell script standing in for the SDK.
use super::*;
use crate::execution::engine::{Broker, Captured, SandboxPort};
use olo_toolgate_contracts::v2::{TriggerLease, TriggerOutboxAck};
use olo_toolgate_contracts::LocalRuntimeLimits;
use std::sync::{Arc, Mutex};

struct Fake {
    script: String,
    removed: Mutex<Vec<String>>,
}

impl SandboxPort for Fake {
    fn check(&self) -> crate::transport::Call<'_, ()> {
        Box::pin(async { Ok(()) })
    }
    fn recover(&self) -> crate::transport::Call<'_, ()> {
        Box::pin(async { Ok(()) })
    }
    fn present<'a>(&'a self, _image: &'a str) -> crate::transport::Call<'a, bool> {
        Box::pin(async { Ok(true) })
    }
    fn pull<'a>(&'a self, _image: &'a str, _seconds: u64) -> crate::transport::Call<'a, ()> {
        Box::pin(async { Ok(()) })
    }
    fn dirty(&self) -> bool {
        false
    }
    fn run<'a>(
        &'a self,
        _image: &'a str,
        _command: &'a [String],
        _input: &'a [u8],
        _limits: &'a LocalRuntimeLimits,
        _broker: Broker<'a>,
    ) -> crate::transport::Call<'a, Captured> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    fn trigger_container<'a>(
        &'a self,
        _image: &'a str,
        command: &'a [String],
        _memory: u64,
    ) -> crate::transport::Call<'a, (String, tokio::process::Command)> {
        Box::pin(async move {
            assert!(
                command.contains(&"TOOLGATE_EXECUTION_MODE=TRIGGER".to_string()),
                "the container runs the SDK in trigger mode"
            );
            let mut shell = tokio::process::Command::new("sh");
            shell.arg("-c").arg(&self.script);
            Ok(("toolgate-trigger-test".to_string(), shell))
        })
    }
    fn remove_container<'a>(&'a self, name: &'a str) -> crate::transport::Call<'a, ()> {
        self.removed.lock().unwrap().push(name.to_string());
        Box::pin(async { Ok(()) })
    }
}

fn descriptor() -> TriggerDescriptor {
    serde_json::from_value(serde_json::json!({
        "kind": "CLIENT_TRIGGER", "triggerId": "acme.watch/new-file", "mode": "POLL", "executors": ["CLIENT"],
        "eventType": "examples.files.observed.v1", "configSchema": {"type": "object"},
        "eventSchema": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"], "additionalProperties": false},
        "checkpointSchemaVersion": 1, "sourceReplay": "STATE_SNAPSHOT", "schedule": {"intervalMs": 60000},
        "limits": {"maxEventsPerHour": 600, "maxEventBytes": 16384, "maxCheckpointBytes": 4096, "stopGraceMs": 1000},
        "overflow": "BLOCK", "onTerminalRejection": "HALT"
    }))
    .unwrap()
}

fn instance() -> TriggerDesiredInstance {
    TriggerDesiredInstance {
        instance_id: "ti-1".into(),
        trigger_id: "acme.watch/new-file".into(),
        package_digest: format!("sha256:{}", "a".repeat(64)),
        trigger_descriptor_digest: format!("sha256:{}", "d".repeat(64)),
        config_revision: 1,
        config: Default::default(),
        binding_digest: format!("sha256:{}", "c".repeat(64)),
        desired_state: TriggerDesiredState::Running,
    }
}

fn document(generation: u64, instances: Vec<TriggerDesiredInstance>) -> TriggerDesiredDocument {
    TriggerDesiredDocument {
        format_version: 1,
        tenant_id: "t1".into(),
        executor_id: "device-a".into(),
        executor: TriggerExecutor::Client,
        generation,
        issued_at_unix_ms: now(),
        expires_at_unix_ms: now() + 86_400_000,
        instances,
    }
}

async fn until<F: FnMut(&TriggerSync) -> bool>(triggers: &Triggers, mut done: F) -> TriggerSync {
    let mut last = None;
    for _ in 0..100 {
        let sync = triggers.sync().await.unwrap();
        if done(&sync) {
            return sync;
        }
        last = Some(sync);
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    panic!(
        "the runner did not reach the expected state; last sync: {}",
        serde_json::to_string(&last).unwrap()
    );
}

const SCRIPT: &str = r#"read start
case "$start" in *'"kind":"TRIGGER_START"'*) ;; *) exit 9 ;; esac
printf '{"kind":"READY"}\n'
printf '{"kind":"EMIT","emitSeq":1,"id":"file-1","data":{"name":"a.pdf"},"position":"Mg=="}\n'
cat >/dev/null"#;

#[tokio::test]
async fn a_desired_leased_instance_runs_reports_events_truncates_on_ack_and_is_removed() {
    let root =
        std::env::temp_dir().join(format!("toolgate-client-triggers-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let fake = Arc::new(Fake {
        script: SCRIPT.into(),
        removed: Mutex::new(vec![]),
    });
    let runtime = ManagedRuntime {
        id: "jvm".into(),
        kind: olo_toolgate_contracts::LocalRuntimeKind::JavaSdk,
        image: format!("sha256:{}", "b".repeat(64)),
        version: "21.0.12+1".into(),
    };
    let registration = FleetTriggerRegistration {
        package_digest: format!("sha256:{}", "a".repeat(64)),
        trigger_id: "acme.watch/new-file".into(),
        trigger_descriptor_digest: format!("sha256:{}", "d".repeat(64)),
        runtime_id: "jvm".into(),
        entry_point: "/opt/tool/acme.watch/tool.jar".into(),
        file_access: LocalFileAccess::Read,
    };
    let triggers = Triggers::start(root.clone(), "t1".into());
    triggers
        .context(Some(TriggerContext {
            engine: fake.clone(),
            runtimes: vec![runtime.clone()],
            triggers: vec![registration.clone()],
            files: None,
        }))
        .await
        .unwrap();
    let resolved = Resolved {
        instance: instance(),
        descriptor: descriptor(),
        registration: Some(registration),
        runtime: Some(runtime),
    };
    triggers
        .desired(Desired {
            document: document(3, vec![instance()]),
            resolved: vec![resolved.clone()],
        })
        .await
        .unwrap();
    assert_eq!(triggers.observed().await.unwrap(), 3);

    // Not leased yet: installed, but nothing runs.
    let sync = triggers.sync().await.unwrap();
    let report = &sync.snapshot.as_ref().unwrap().instances[0];
    assert!(report
        .conditions
        .iter()
        .any(|c| c.r#type == TriggerConditionType::Installed
            && c.status == TriggerConditionStatus::True));
    assert!(sync.events.is_none());

    // The lease arrives in an ack: the container starts and its event is reported.
    let mut ack = TriggerSyncAck {
        generation: 3,
        max_check_in_bytes: 196_608,
        check_in_now: None,
        acked: None,
        dispositions: None,
        retry_later: None,
        leases: Some(vec![TriggerLease {
            instance_id: "ti-1".into(),
            lease_epoch: 1,
            lease_token: "t".repeat(43),
            expires_at_unix_ms: now() + 86_400_000,
        }]),
        revoked: None,
        rejected: None,
        snapshot_pages: None,
        snapshot_requested: None,
    };
    triggers.acknowledge(ack.clone()).await.unwrap();
    let sync = until(&triggers, |s| s.events.is_some()).await;
    let event = &sync.events.as_ref().unwrap()[0].event;
    assert_eq!(
        (event.id.as_str(), event.tgleaseepoch, event.tgsequence),
        ("file-1", 1, 1)
    );
    assert_eq!(event.source, "toolgate:tenant:t1:trigger-instance:ti-1");
    assert_eq!(
        sync.snapshot.as_ref().unwrap().instances[0].phase,
        TriggerPhase::Running
    );

    // Control stored it: the outbox truncates and the ACCEPTED checkpoint is reported inline.
    ack.leases = None;
    ack.acked = Some(vec![TriggerOutboxAck {
        instance_id: "ti-1".into(),
        lease_epoch: 1,
        outbox_seq: 1,
    }]);
    triggers.acknowledge(ack).await.unwrap();
    let sync = until(&triggers, |s| s.events.is_none() && s.checkpoints.is_some()).await;
    let checkpoint = &sync.checkpoints.as_ref().unwrap()[0];
    assert_eq!(
        (
            checkpoint.lease_epoch,
            checkpoint.after_outbox_seq,
            checkpoint.inline.as_deref()
        ),
        (1, 1, Some("Mg=="))
    );

    // A replacement with the same descriptor must wait for its own package.
    let mut replacement = resolved;
    replacement.instance.package_digest = format!("sha256:{}", "c".repeat(64));
    replacement.instance.config_revision += 1;
    triggers
        .desired(Desired {
            document: document(4, vec![replacement.instance.clone()]),
            resolved: vec![replacement],
        })
        .await
        .unwrap();
    let sync = triggers.sync().await.unwrap();
    let report = &sync.snapshot.as_ref().unwrap().instances[0];
    assert_eq!(report.phase, TriggerPhase::Pending);
    assert!(report.conditions.iter().any(|condition| {
        condition.r#type == TriggerConditionType::Installed
            && condition.status == TriggerConditionStatus::False
    }));

    // Missing from a newer document: stopped, drained (nothing is left) and its local state deleted.
    triggers
        .desired(Desired {
            document: document(5, vec![]),
            resolved: vec![],
        })
        .await
        .unwrap();
    until(&triggers, |s| s.snapshot.is_none()).await;
    assert!(
        !root.join("ti-1").exists(),
        "local state is deleted after removal"
    );
    assert!(
        fake.removed
            .lock()
            .unwrap()
            .contains(&"toolgate-trigger-test".to_string()),
        "the container is always removed"
    );
    let _ = std::fs::remove_dir_all(&root);
}

#[tokio::test]
async fn an_instance_without_a_released_registration_is_pending_and_not_installed() {
    let root = std::env::temp_dir().join(format!(
        "toolgate-client-triggers-missing-{}",
        std::process::id()
    ));
    let _ = std::fs::remove_dir_all(&root);
    let triggers = Triggers::start(root.clone(), "t1".into());
    let resolved = Resolved {
        instance: instance(),
        descriptor: descriptor(),
        registration: None,
        runtime: None,
    };
    triggers
        .desired(Desired {
            document: document(1, vec![instance()]),
            resolved: vec![resolved],
        })
        .await
        .unwrap();
    let sync = triggers.sync().await.unwrap();
    let report = &sync.snapshot.as_ref().unwrap().instances[0];
    assert_eq!(report.phase, TriggerPhase::Pending);
    let installed = report
        .conditions
        .iter()
        .find(|c| c.r#type == TriggerConditionType::Installed)
        .unwrap();
    assert_eq!(
        (&installed.status, installed.reason.as_str()),
        (&TriggerConditionStatus::False, "PackageNotInstalled")
    );
    let _ = std::fs::remove_dir_all(&root);
}

#[test]
fn descriptor_digests_are_canonical() {
    let a = serde_json::json!({"b": 1, "a": "e\u{301}"});
    let b = serde_json::json!({"a": "\u{e9}", "b": 1});
    assert_eq!(
        descriptor_digest(&a).unwrap(),
        descriptor_digest(&b).unwrap()
    );
}
