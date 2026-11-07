// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The tokio process wrapper against a real child process (a POSIX shell script standing in for the SDK).
#![cfg(unix)]

use olo_toolgate_contracts::v2::{TriggerExecutor, TriggerStartFrame};
use olo_toolgate_trigger_supervisor::frames::FrameError;
use olo_toolgate_trigger_supervisor::process::{ReadError, TriggerProcess};
use olo_toolgate_trigger_supervisor::{Inbound, Outbound};
use std::collections::BTreeMap;
use tokio::process::Command;

fn start() -> TriggerStartFrame {
    TriggerStartFrame {
        kind: "TRIGGER_START".into(),
        trigger_id: "examples.mail/new-message".into(),
        instance_id: "inst-1".into(),
        config_revision: 1,
        lease_epoch: 1,
        config: BTreeMap::new(),
        globals: None,
        secret_handles: None,
        checkpoint: Some("AQ==".into()),
    }
}

fn sh(script: &str) -> Command {
    let mut command = Command::new("sh");
    command.arg("-c").arg(script);
    command
}

#[tokio::test]
async fn the_process_gets_the_environment_and_start_frame_and_speaks_frames() {
    let script = r#"
        read start
        case "$start" in *'"kind":"TRIGGER_START"'*'"checkpoint":"AQ=="'*) ;; *) exit 9 ;; esac
        printf '{"kind":"READY"}\n'
        printf '{"kind":"EMIT","emitSeq":1,"data":{"mode":"%s","target":"%s","protocol":"%s"}}\n' "$TOOLGATE_EXECUTION_MODE" "$TOOLGATE_EXECUTION_TARGET" "$TOOLGATE_PROTOCOL"
        read stop
        case "$stop" in *'"kind":"STOP"'*) printf '{"kind":"EXIT","code":0}\n' ;; esac
    "#;
    let mut process = TriggerProcess::spawn(sh(script), TriggerExecutor::Client, start())
        .await
        .unwrap();
    assert!(matches!(
        process.next_frame().await.unwrap(),
        Some(Outbound::Ready(_))
    ));
    let Some(Outbound::Emit(emit)) = process.next_frame().await.unwrap() else {
        panic!("expected EMIT")
    };
    assert_eq!(
        emit.data,
        serde_json::json!({"mode": "TRIGGER", "target": "CLIENT", "protocol": "2"})
    );
    process
        .send(&Inbound::stop("Removed", 1_000))
        .await
        .unwrap();
    assert!(matches!(
        process.next_frame().await.unwrap(),
        Some(Outbound::Exit(_))
    ));
    assert!(process.next_frame().await.unwrap().is_none());
    assert!(process.wait().await.unwrap().success());
}

#[tokio::test]
async fn an_oversized_line_is_refused_without_buffering_it() {
    let script = r#"read start; head -c 70000 /dev/zero | tr '\0' 'x'; printf '\n'"#;
    let mut process = TriggerProcess::spawn(sh(script), TriggerExecutor::Server, start())
        .await
        .unwrap();
    assert!(matches!(
        process.next_frame().await,
        Err(ReadError::Frame(FrameError::TooLong))
    ));
    process.kill().unwrap();
    process.wait().await.unwrap();
}

#[tokio::test]
async fn terminate_sends_sigterm() {
    let script = r#"trap 'exit 3' TERM; read start; printf '{"kind":"READY"}\n'; while :; do sleep 0.05; done"#;
    let mut process = TriggerProcess::spawn(sh(script), TriggerExecutor::Server, start())
        .await
        .unwrap();
    assert!(matches!(
        process.next_frame().await.unwrap(),
        Some(Outbound::Ready(_))
    ));
    process.terminate().unwrap();
    assert_eq!(process.wait().await.unwrap().code(), Some(3));
}
