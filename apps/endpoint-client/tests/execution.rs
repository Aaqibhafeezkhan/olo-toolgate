// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Runtime boundary negatives plus explicit real-engine tests (run by runtime_check.py).
use olo_toolgate_client::{
    builtins::AuthorizationPort,
    execution::{adapters, Manager, Settings},
    transport::Call,
    Failure, Result,
};
use olo_toolgate_contracts::*;
use serde_json::json;
use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::{Arc, Mutex},
};

struct Policy {
    result: Result<()>,
    deadline: Option<u64>,
    calls: Mutex<Vec<AuthorizationRequest>>,
}
impl AuthorizationPort for Policy {
    fn authorize(&self, request: AuthorizationRequest) -> Call<'_, ()> {
        self.calls.lock().unwrap().push(request);
        Box::pin(async { self.result })
    }
    fn authorize_bound(&self, request: AuthorizationRequest) -> Call<'_, Option<u64>> {
        self.calls.lock().unwrap().push(request);
        Box::pin(async { self.result.map(|_| self.deadline) })
    }
}
fn tool(kind: LocalRuntimeKind) -> LocalToolRegistration {
    let name = match kind {
        LocalRuntimeKind::Native => "run",
        LocalRuntimeKind::Python => "tool.py",
        LocalRuntimeKind::Node => "tool.mjs",
        LocalRuntimeKind::Powershell => "tool.ps1",
        LocalRuntimeKind::Shell => "tool.sh",
        LocalRuntimeKind::JavaJar => "tool.jar",
        LocalRuntimeKind::Dotnet => "tool.dll",
        _ => "unsupported",
    };
    let timeout = if kind == LocalRuntimeKind::Powershell {
        10000
    } else {
        3000
    };
    let memory = if kind == LocalRuntimeKind::Powershell {
        512
    } else {
        128
    };
    let mut value = json!({"toolId":"local.echo","action":"execute","runtimeId":"runtime-test","entryPoint":format!("/opt/tool/{name}"),
        "inputSchema":{"type":"object","additionalProperties":false,"properties":{"text":{"type":"string","maxLength":256},"mode":{"type":"string"}},"required":["text","mode"]},
        "outputSchema":{"type":"object","additionalProperties":false,"properties":{"text":{"type":"string"},"isolated":{"type":"boolean"},"threads":{"type":"boolean"}},"required":["text"]},
        "limits":{"timeoutMs":timeout,"memoryMiB":memory,"maxInputBytes":4096,"maxOutputBytes":4096}});
    value["authorizationProfile"] = json!({"tool":{"id":"local.echo","name":"Echo","enabled":true,"revision":1,"version":"1.0.0","packageDigest":"a".repeat(64),"extractorId":"extract-echo","definition":{"id":"local.echo","name":"Echo","description":"Fixture compute capability","actions":[{"name":"execute","resourceKinds":["CUSTOM"]}],"inputSchema":value["inputSchema"],"outputSchema":value["outputSchema"]}},"extractor":{"id":"extract-echo","name":"Resources","enabled":true,"revision":1,"extractorKind":"FIELDS","version":"1.0.0","fields":[],"fixedResources":[{"kind":"CUSTOM","locator":"runtime/local.echo"}],"maxResources":1}});
    serde_json::from_value(value).unwrap()
}
fn settings() -> Settings {
    let mut settings = Settings {
        engine_path: PathBuf::from(if cfg!(windows) {
            "C:/Program Files/Docker/Docker/resources/bin/docker.exe"
        } else {
            "/usr/bin/docker"
        }),
        engine_endpoint: "unix:///var/run/docker.sock".into(),
        state_directory: PathBuf::from(if cfg!(windows) {
            "C:/ProgramData/olo-toolgate/runtimes"
        } else {
            "/var/lib/olo-toolgate/runtimes"
        }),
        allow_first_use_pull: false,
        pull_timeout_seconds: 30,
        runtimes: vec![ManagedRuntime {
            id: "runtime-test".into(),
            kind: LocalRuntimeKind::Python,
            image: format!("sha256:{}", "a".repeat(64)),
            version: "3.14.0".into(),
        }],
        tools: vec![tool(LocalRuntimeKind::Python)],
    };
    repin(&mut settings);
    settings
}
fn repin(settings: &mut Settings) {
    for tool in &mut settings.tools {
        let runtime = settings
            .runtimes
            .iter()
            .find(|r| r.id == tool.runtime_id)
            .unwrap();
        tool.authorization_profile.tool.package_digest =
            olo_toolgate_client::authorization_profile::managed_package_digest(tool, runtime)
                .unwrap();
    }
}
fn input(text: &str, mode: &str) -> LocalToolInput {
    LocalToolInput {
        protocol_version: 1,
        request_id: "runtime-request".into(),
        tool_id: "local.echo".into(),
        arguments: serde_json::from_value(json!({"text":text,"mode":mode})).unwrap(),
    }
}
#[cfg(unix)]
#[tokio::test]
async fn cold_engine_startup_preserves_limits_and_capabilities() {
    use olo_toolgate_client::execution::engine::{DockerEngine, SandboxPort};
    use std::os::unix::fs::PermissionsExt;
    struct Directory(PathBuf);
    impl Drop for Directory {
        fn drop(&mut self) {
            std::fs::remove_dir_all(&self.0).unwrap();
        }
    }
    #[cfg(target_os = "macos")]
    let base = PathBuf::from("/private/tmp");
    #[cfg(not(target_os = "macos"))]
    let base = std::env::temp_dir();
    let directory = Directory(base.join(format!(
        "toolgate-engine-{}",
        olo_toolgate_client::identity::nonce().unwrap()
    )));
    let _custody = olo_toolgate_client::storage::ProtectedStore::open(directory.0.clone()).unwrap();
    let binary = directory.0.join("docker-fixture");
    let capabilities = r#"{"OSType":"linux","MemoryLimit":true,"SwapLimit":true,"PidsLimit":true,"CpuCfsQuota":true,"SecurityOptions":["name=seccomp,profile=builtin"]}"#;
    // A healthy engine's first metadata query must survive a cold startup over 3s.
    std::fs::write(
        &binary,
        format!("#!/bin/sh\n/bin/sleep 4\nprintf '%s\\n' '{capabilities}'\n"),
    )
    .unwrap();
    std::fs::set_permissions(&binary, std::fs::Permissions::from_mode(0o700)).unwrap();
    let engine = DockerEngine::new(
        binary.clone(),
        "unix:///var/run/docker.sock".into(),
        directory.0.join("state"),
    )
    .unwrap();
    engine.check().await.unwrap();
    // Startup headroom must never turn a missing mandatory limit into readiness.
    let unsupported = capabilities.replace("\"SwapLimit\":true", "\"SwapLimit\":false");
    std::fs::write(
        &binary,
        format!("#!/bin/sh\nprintf '%s\\n' '{unsupported}'\n"),
    )
    .unwrap();
    assert_eq!(engine.check().await.unwrap_err(), Failure::Unsupported);
    // Version preparation is setup: a cold create over the former 10s deadline
    // must succeed without spending the separate 1s tool execution budget.
    std::fs::write(
        &binary,
        "#!/bin/sh\ncase \"$5\" in\ncreate) exec /bin/sleep 11 ;;\nstart) printf 'ready\\n' ;;\nrm) exit 0 ;;\n*) exit 1 ;;\nesac\n",
    )
    .unwrap();
    let limits = LocalRuntimeLimits {
        timeout_ms: 1000,
        memory_mi_b: 128,
        max_input_bytes: 4096,
        max_output_bytes: 4096,
    };
    let output = engine
        .probe_version("sha256:fixture", &["/fixture".into()], &limits)
        .await
        .unwrap();
    assert_eq!(output.stdout, b"ready\n");
    // The same cold create still exceeds an ordinary invocation's 10s budget.
    assert_eq!(
        engine
            .run("sha256:fixture", &["/fixture".into()], b"", &limits)
            .await
            .err(),
        Some(Failure::Expired)
    );
    // Startup headroom must not extend the tool's own deadline or skip cleanup.
    let removed = directory.0.join("removed");
    std::fs::write(
        &binary,
        format!(
            "#!/bin/sh\ncase \"$5\" in\ncreate) exit 0 ;;\nstart) exec /bin/sleep 2 ;;\nrm) printf removed > '{}' ;;\n*) exit 1 ;;\nesac\n",
            removed.display()
        ),
    )
    .unwrap();
    assert_eq!(
        engine
            .probe_version("sha256:fixture", &["/fixture".into()], &limits)
            .await
            .err(),
        Some(Failure::Expired)
    );
    assert_eq!(std::fs::read(removed).unwrap(), b"removed");
    assert!(!engine.dirty());
}
#[test]
fn fixed_adapter_vectors_never_interpolate_input() {
    for kind in [
        LocalRuntimeKind::Native,
        LocalRuntimeKind::Python,
        LocalRuntimeKind::Node,
        LocalRuntimeKind::Powershell,
        LocalRuntimeKind::Shell,
        LocalRuntimeKind::JavaJar,
        LocalRuntimeKind::Dotnet,
    ] {
        let args = adapters::invocation_arguments(&kind, &tool(kind.clone())).unwrap();
        assert_eq!(args[0], "/usr/bin/env");
        assert_eq!(args[1], "-i");
        assert!(!args
            .iter()
            .any(|v| ["-c", "-Command", "/c"].contains(&v.as_str())));
        for bad in [
            "/opt/tool/../escape.py",
            "/opt/tool/a//b.py",
            "/opt/tool/.hidden.py",
            "/opt/tool/a.py;id",
            "C:\\tools\\file.py",
            "--eval",
            "/opt/tool/a.py\n",
        ] {
            let mut badtool = tool(kind.clone());
            badtool.entry_point = bad.into();
            assert_eq!(
                adapters::invocation_arguments(&kind, &badtool).unwrap_err(),
                Failure::Validation
            );
        }
    }
    assert_eq!(
        adapters::executable(&LocalRuntimeKind::Batch),
        Err(Failure::Unsupported)
    );
    assert_eq!(
        adapters::executable(&LocalRuntimeKind::Wasm),
        Err(Failure::Unsupported)
    );
}
#[test]
fn exact_versions_and_closed_configuration_fail_safely() {
    adapters::verify_version(b"Python 3.14.0\n", "3.14.0").unwrap();
    assert_eq!(
        adapters::verify_version(b"Python 3.14.0\n", "3.1.0"),
        Err(Failure::Unsupported)
    );
    assert_eq!(
        adapters::verify_version(b"secret invalid\nPython 3.14.0", "3.14.0"),
        Err(Failure::Unsupported)
    );
    adapters::verify_version(b"openjdk version \"21.0.12.1\"\n", "21.0.12+1").unwrap();
    assert!(adapters::verify_version(b"openjdk version \"21.0.12.1\"\n", "21.0.12").is_err());
    let original = settings();
    original.validate().unwrap();
    let mut bad = original.clone();
    bad.runtimes[0].image = "python:latest".into();
    assert!(bad.validate().is_err());
    let mut bad = original.clone();
    bad.tools[0]
        .input_schema
        .insert("$ref".into(), json!("https://metadata.invalid/schema"));
    assert!(bad.validate().is_err());
    let mut bad = original.clone();
    bad.tools[0]
        .output_schema
        .insert("pattern".into(), json!("unbounded"));
    assert!(bad.validate().is_err());
    let mut bad = original.clone();
    bad.tools[0].tool_id = "hotfolder.read_text".into();
    assert!(bad.validate().is_err());
    let mut bad = original.clone();
    bad.tools[0].limits.memory_mi_b = 4096;
    assert!(bad.validate().is_err());
    let mut bad = original.clone();
    bad.runtimes.push(bad.runtimes[0].clone());
    assert!(bad.validate().is_err());
}
#[test]
fn protocol_binding_and_malformed_outputs_reject() {
    let contracts = olo_toolgate_client::contracts::Contracts::new().unwrap();
    for bad in [
        br#"{"protocolVersion":1,"requestId":"r","requestId":"other","output":{}}"#.as_slice(),
        br#"{"protocolVersion":1,"requestId":"r","output":{},"secret":"no"}"#,
        br#"{} {}"#,
        br#"not json"#,
    ] {
        assert!(contracts
            .decode::<LocalToolOutput>("LocalToolOutput", bad)
            .is_err());
    }
    let request = LocalRuntimeIpcRequest {
        protocol_version: 3,
        request_id: "r".into(),
        operation: LocalRuntimeOperation::Status,
        invocation: None,
    };
    let bytes = contracts
        .encode("LocalRuntimeIpcRequest", &request)
        .unwrap();
    assert!(contracts
        .decode::<BuiltinIpcRequest>("BuiltinIpcRequest", &bytes)
        .is_err());
    assert!(contracts
        .decode::<ClientIpcRequest>("ClientIpcRequest", &bytes)
        .is_err());
}

/// Explicitly invoked by the real-engine CI gate; not a silently skipped test.
#[tokio::test]
#[ignore = "requires tools/client/runtime_check.py real Docker fixture provisioning"]
async fn real_managed_runtime_security_boundary() {
    let _ = tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .try_init();
    let images: BTreeMap<String, serde_json::Value> = serde_json::from_str(
        &std::env::var("TOOLGATE_RUNTIME_TEST_IMAGES").expect("real image evidence required"),
    )
    .unwrap();
    let base = PathBuf::from(std::env::var("TOOLGATE_RUNTIME_TEST_STATE").unwrap());
    let docker = PathBuf::from(std::env::var("TOOLGATE_RUNTIME_TEST_ENGINE").unwrap());
    let endpoint = std::env::var("TOOLGATE_RUNTIME_TEST_ENDPOINT").unwrap();
    let _custody = olo_toolgate_client::storage::ProtectedStore::open(base.clone())
        .expect("owned fixture state custody");
    olo_toolgate_client::storage::check_parents(&docker).expect("engine executable parent custody");
    olo_toolgate_client::storage::check_owned(&docker, false).expect("engine executable custody");
    for (index, (kind, descriptor)) in images.iter().enumerate() {
        let kind: LocalRuntimeKind = serde_json::from_value(json!(kind)).unwrap();
        let mut settings = settings();
        settings.engine_path = docker.clone();
        settings.engine_endpoint = endpoint.clone();
        settings.state_directory = base.join(format!("case-{index}"));
        settings.runtimes[0].kind = kind.clone();
        settings.runtimes[0].image = descriptor["image"].as_str().unwrap().into();
        settings.runtimes[0].version = descriptor["version"].as_str().unwrap().into();
        settings.tools[0] = tool(kind.clone());
        repin(&mut settings);
        let policy = Arc::new(Policy {
            result: Ok(()),
            deadline: None,
            calls: Mutex::new(Vec::new()),
        });
        let mut manager = Manager::new(settings.clone(), policy.clone()).unwrap();
        assert!(!manager.health().ready);
        let health = manager.prepare().await.unwrap();
        assert!(
            health.ready,
            "runtime {kind:?} version/sandbox self-test failed: {health:?}"
        );
        let payload = "'; $(touch /escape) & | > < --eval \"\\ unicode-π";
        let output = manager
            .invoke(input(payload, "echo"), olo_toolgate_client::now() + 60000)
            .await
            .unwrap();
        assert_eq!(output.output["text"], json!(payload));
        assert_eq!(
            policy.calls.lock().unwrap()[0].arguments,
            serde_json::from_value(json!({"text":payload,"mode":"echo"})).unwrap()
        );
        // A fresh online grant is required even after an image was prepared.
        let mut denied = Manager::new(
            settings.clone(),
            Arc::new(Policy {
                result: Err(Failure::Unavailable),
                deadline: None,
                calls: Mutex::new(Vec::new()),
            }),
        )
        .unwrap();
        assert_eq!(
            denied
                .invoke(input("private", "echo"), olo_toolgate_client::now() + 60000)
                .await
                .unwrap_err(),
            Failure::Unavailable
        );
        let mut bad = input("private", "echo");
        bad.tool_id = "unassigned.tool".into();
        assert_eq!(
            manager
                .invoke(bad, olo_toolgate_client::now() + 60000)
                .await
                .unwrap_err(),
            Failure::Unauthorized
        );
        if kind == LocalRuntimeKind::Python {
            let mut expired_permit = Manager::new(
                settings.clone(),
                Arc::new(Policy {
                    result: Ok(()),
                    deadline: Some(olo_toolgate_client::now() + 1000),
                    calls: Mutex::new(Vec::new()),
                }),
            )
            .unwrap();
            assert_eq!(
                expired_permit
                    .invoke(input("private", "echo"), olo_toolgate_client::now() + 60000)
                    .await
                    .unwrap_err(),
                Failure::Expired
            );
            let mut pull = settings.clone();
            pull.allow_first_use_pull = true;
            pull.runtimes[0].version = std::env::var("TOOLGATE_RUNTIME_PULL_VERSION").unwrap();
            pull.tools.clear();
            pull.runtimes[0].image = std::env::var("TOOLGATE_RUNTIME_PULL_IMAGE")
                .expect("initially absent real registry image required");
            let mut pull = Manager::new(pull, policy.clone()).unwrap();
            assert!(
                pull.prepare().await.unwrap().ready,
                "pinned managed Python could not be provisioned"
            );
            let proof = manager
                .invoke(
                    input("safe", "isolation"),
                    olo_toolgate_client::now() + 60000,
                )
                .await
                .unwrap();
            assert_eq!(proof.output["isolated"], json!(true));
            assert_eq!(proof.output["threads"], json!(true));
            for mode in [
                "overflow",
                "malformed",
                "duplicate",
                "wrong-request",
                "stderr",
                "wrong-schema",
            ] {
                assert!(
                    manager
                        .invoke(input("private", mode), olo_toolgate_client::now() + 60000)
                        .await
                        .is_err(),
                    "accepted {mode}"
                );
            }
            assert_eq!(
                manager
                    .invoke(
                        input("private", "timeout"),
                        olo_toolgate_client::now() + 60000
                    )
                    .await
                    .unwrap_err(),
                Failure::Expired
            );
            assert!(manager
                .invoke(
                    input("private", "memory"),
                    olo_toolgate_client::now() + 60000
                )
                .await
                .is_err());
            // Tools pin the runtime version and image in their package digest, so these checks prepare runtimes only.
            let mut wrong = settings.clone();
            wrong.tools.clear();
            wrong.runtimes[0].version = "0.0.1".into();
            let mut wrong = Manager::new(wrong, policy.clone()).unwrap();
            assert!(!wrong.prepare().await.unwrap().ready);
            let mut missing = settings.clone();
            missing.tools.clear();
            missing.runtimes[0].image = format!("sha256:{}", "f".repeat(64));
            let mut missing = Manager::new(missing, policy.clone()).unwrap();
            assert!(!missing.prepare().await.unwrap().ready);
            assert_eq!(
                manager
                    .invoke(input("private", "echo"), olo_toolgate_client::now() + 1)
                    .await
                    .unwrap_err(),
                Failure::Expired
            );
        }
        println!("real {kind:?} runtime protocol, injection, version, online authorization and cleanup passed");
    }
}
