// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Owned systemd drill fixture. No workload identity, live effects or production admission.
#![cfg(target_os = "linux")]
use olo_toolgate_contracts::v2::ToolInput;
use serde_json::{json, Value};
use std::{path::PathBuf, sync::Arc, time::Duration};
use tool_host::{
    broker::Invocation,
    control::Control,
    sandbox::{Config, Sandbox},
};

#[tokio::test]
#[ignore = "only tools/security/deployment.py may select this owned process fixture"]
async fn managed_deployment_fixture() {
    let area =
        PathBuf::from(std::env::var("TOOLGATE_DEPLOYMENT_AREA").expect("owned area required"));
    let requested = std::env::var("TOOLGATE_DEPLOYMENT_SCENARIO").expect("scenario required");
    let scenario = if requested == "crash" && area.join("recover-next").exists() {
        "recover".to_string()
    } else {
        requested
    };
    let config = Config {
        podman_path: "/usr/bin/podman".into(),
        cgroup_parent: std::env::var("TOOLGATE_SANDBOX_CGROUP_PARENT").unwrap(),
        python_image: std::env::var("TOOLGATE_SANDBOX_IMAGE").unwrap(),
        broker_image: std::env::var("TOOLGATE_SANDBOX_BROKER_IMAGE").unwrap(),
        io_device: std::env::var("TOOLGATE_SANDBOX_IO_DEVICE").unwrap(),
        io_bytes_per_second: 64 * 1024 * 1024,
        scratch_bytes: 8 * 1024 * 1024,
    };
    let start = std::time::Instant::now();
    let pool = Sandbox::open(config, &area, "deployment-drill", 1).unwrap();
    let qualified = pool.qualify().await;
    if scenario == "weak-unit" {
        let error = qualified.expect_err("weak service must never qualify");
        assert!(
            error.contains("control-group SIGKILL"),
            "wrong refusal: {error}"
        );
        assert!(!pool.available());
        std::fs::write(area.join("observation.json"),serde_json::to_vec(&json!({"scenario":scenario,"refused":true,"elapsedMs":start.elapsed().as_millis()})).unwrap()).unwrap();
        return;
    }
    qualified.expect("managed deployment must qualify");
    if scenario == "recover" {
        assert!(pool.available());
        assert_eq!(
            std::fs::read_dir(area.join("sandbox-spool"))
                .unwrap()
                .count(),
            1,
            "old lifecycle records must be reconciled"
        );
        std::fs::write(area.join("observation.json"),serde_json::to_vec(&json!({"scenario":scenario,"recovered":true,"replayedWorker":false,"supervisorPid":std::process::id(),"unitInvocationId":std::env::var("INVOCATION_ID").unwrap(),"elapsedMs":start.elapsed().as_millis()})).unwrap()).unwrap();
        return;
    }
    assert_eq!(scenario, "crash");
    let fixtures: Value = serde_json::from_str(include_str!(
        "../../../tests/fixtures/contracts/v2/valid.json"
    ))
    .unwrap();
    let mut value = fixtures["ToolInput"].clone();
    value["invocationId"] = json!("deployment-crash");
    value["deadlineUnixMs"] = json!(tool_host::now_ms() + 120_000);
    let input: ToolInput = serde_json::from_value(value).unwrap();
    let control_config=serde_json::from_value(json!({"tenantId":"10000000-0000-4000-8000-000000000001","url":"http://127.0.0.1:1","developmentLoopbackHttp":true})).unwrap();
    let mut invocation = Invocation::new(
        "deployment-crash".into(),
        "fence".into(),
        input.deadline_unix_ms,
        "deployment-profile".into(),
        format!("sha256:{}", "a".repeat(64)),
        vec![],
        vec![],
        vec![],
        vec![],
        vec![],
        Default::default(),
        true,
        Arc::new(Control::new(&control_config).unwrap()),
    );
    invocation.invocation_permit = Some(olo_toolgate_contracts::v2::SignedInvocationPermit {
        jws: "containment-drill-only".into(),
    });
    let invocation = Arc::new(invocation);
    let script = area.join("__main__.py");
    std::fs::write(&script,"import json,signal,time\ni=json.loads(input())\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\nwhile True: time.sleep(.1)\n").unwrap();
    assert!(std::process::Command::new("/usr/bin/python3")
        .args(["-m", "py_compile"])
        .arg(&script)
        .status()
        .unwrap()
        .success());
    let artifact = area.join("drill.pyz");
    assert!(std::process::Command::new("/usr/bin/python3")
        .current_dir(&area)
        .args(["-m", "zipfile", "-c", "drill.pyz", "__main__.py"])
        .status()
        .unwrap()
        .success());
    let (sink, _source) = tool_host::events::channel();
    let running_pool = pool.clone();
    tokio::spawn(async move {
        running_pool
            .run(
                "deployment-crash",
                &artifact,
                &input,
                128 * 1024 * 1024,
                1024 * 1024,
                Arc::new(sink),
                invocation,
            )
            .await
    });
    // The observer confirms actual running container PIDs and cgroup membership
    // before killing this supervisor. This fixture never reports a fake PID.
    std::fs::write(area.join("qualified.json"),serde_json::to_vec(&json!({"scenario":scenario,"qualified":true,"supervisorPid":std::process::id(),"unitInvocationId":std::env::var("INVOCATION_ID").unwrap(),"elapsedMs":start.elapsed().as_millis()})).unwrap()).unwrap();
    loop {
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
}
