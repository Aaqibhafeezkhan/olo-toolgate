// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Managed SDK runtime boundary: fixed launch vectors, exact versions, closed configuration, and tool protocol v2
//! end to end through the engine with the device files broker. The real-engine test is run by runtime_check.py.
use olo_toolgate_client::{
    execution::{adapters, AuthorizationPort, Manager, Settings},
    transport::Call,
    Failure, Result,
};
use olo_toolgate_contracts::*;
use serde_json::json;
use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
};

struct Policy {
    result: Result<()>,
    calls: Mutex<Vec<AuthorizationRequest>>,
}
impl AuthorizationPort for Policy {
    fn authorize(&self, request: AuthorizationRequest) -> Call<'_, ()> {
        self.calls.lock().unwrap().push(request);
        Box::pin(async { self.result })
    }
    fn authorize_bound(&self, request: AuthorizationRequest) -> Call<'_, Option<u64>> {
        self.calls.lock().unwrap().push(request);
        Box::pin(async {
            self.result
                .map(|_| Some(olo_toolgate_client::now() + 60000))
        })
    }
}
/// A temporary directory protected storage accepts: on macOS the temporary directory is under the /var symbolic link,
/// and protected storage refuses links among its parents.
#[cfg(unix)]
fn custody_base() -> PathBuf {
    std::env::temp_dir().canonicalize().unwrap()
}

fn tool(access: Option<LocalFileAccess>) -> LocalToolRegistration {
    let mut value = json!({"toolId":"acme.notes/notes.read","action":"invoke","runtimeId":"java-sdk","entryPoint":"/opt/tool/acme.notes/tool.jar",
        "inputSchema":{"type":"object","additionalProperties":false,"properties":{"path":{"type":"string","maxLength":128}},"required":["path"]},
        "outputSchema":{"type":"object","additionalProperties":false,"properties":{"first":{"type":"object"},"second":{"type":"object"}},"required":["first","second"]},
        "limits":{"timeoutMs":3000,"memoryMiB":256,"maxInputBytes":4096,"maxOutputBytes":8192}});
    if let Some(access) = access {
        value["fileAccess"] = serde_json::to_value(access).unwrap();
    }
    value["authorizationProfile"] = json!({"tool":{"id":"acme.notes/notes.read","name":"Read","enabled":true,"revision":1,"version":"1.0.0","packageDigest":"a".repeat(64),"extractorId":"extract-notes","definition":{"id":"acme.notes/notes.read","name":"Read","description":"Reads a note","actions":[{"name":"invoke","resourceKinds":["CUSTOM"]}],"inputSchema":value["inputSchema"],"outputSchema":value["outputSchema"]}},"extractor":{"id":"extract-notes","name":"Resources","enabled":true,"revision":1,"extractorKind":"FIELDS","version":"1.0.0","fields":[],"fixedResources":[{"kind":"CUSTOM","locator":"tool://acme.notes/notes.read"}],"maxResources":1}});
    serde_json::from_value(value).unwrap()
}
fn settings(engine: PathBuf, state: PathBuf, access: Option<LocalFileAccess>) -> Settings {
    let mut settings = Settings {
        engine_path: engine,
        engine_endpoint: "unix:///var/run/docker.sock".into(),
        state_directory: state,
        allow_first_use_pull: false,
        pull_timeout_seconds: 30,
        runtimes: vec![ManagedRuntime {
            id: "java-sdk".into(),
            kind: LocalRuntimeKind::JavaSdk,
            image: format!("sha256:{}", "b".repeat(64)),
            version: "21.0.12+1".into(),
        }],
        tools: vec![tool(access)],
        files: None,
    };
    for tool in &mut settings.tools {
        tool.authorization_profile.tool.package_digest =
            olo_toolgate_client::authorization_profile::managed_package_digest(
                tool,
                &settings.runtimes[0],
            )
            .unwrap();
    }
    settings
}

#[test]
fn the_launch_vector_is_fixed_and_never_interpolates_input() {
    let args = adapters::invocation_arguments(&LocalRuntimeKind::JavaSdk, &tool(None)).unwrap();
    assert_eq!(&args[..2], ["/usr/bin/env", "-i"]);
    assert!(args.contains(&"TOOLGATE_PROTOCOL=2".into()));
    assert!(args.contains(&"TOOLGATE_EXECUTION_TARGET=CLIENT".into()));
    assert_eq!(
        &args[args.len() - 3..],
        [
            "-cp",
            "/opt/tool/acme.notes/tool.jar",
            "io.ololabs.toolgate.runtime.Main"
        ]
    );
    for bad in [
        "/opt/tool/../escape.jar",
        "/opt/tool/a//b.jar",
        "/opt/tool/.hidden.jar",
        "/opt/tool/a.jar;id",
        "/opt/tool/tool.py",
        "C:\\tools\\file.jar",
        "--eval",
    ] {
        let mut badtool = tool(None);
        badtool.entry_point = bad.into();
        assert_eq!(
            adapters::invocation_arguments(&LocalRuntimeKind::JavaSdk, &badtool).unwrap_err(),
            Failure::Validation,
            "{bad}"
        );
    }
}

#[test]
fn exact_versions_and_closed_configuration_fail_safely() {
    adapters::verify_version(
        b"openjdk version \"21.0.12.1\" 2026-08-18 LTS\n",
        "21.0.12+1",
    )
    .unwrap();
    assert!(adapters::verify_version(b"openjdk version \"21.0.12.1\"\n", "21.0.12").is_err());
    assert!(
        adapters::verify_version(b"secret\nopenjdk version \"21.0.12.1\"", "21.0.12+1").is_err()
    );
    // Absolute on every platform; validation never touches these paths.
    let base = std::env::temp_dir();
    let original = settings(base.join("docker"), base.join("runtimes"), None);
    original.validate().unwrap();
    let mut bad = original.clone();
    bad.runtimes[0].image = "eclipse-temurin:latest".into();
    assert!(bad.validate().is_err());
    let mut bad = original.clone();
    bad.tools[0]
        .input_schema
        .insert("$ref".into(), json!("https://metadata.invalid/schema"));
    assert!(bad.validate().is_err());
    let mut bad = original.clone();
    bad.tools[0].limits.memory_mi_b = 4096;
    assert!(bad.validate().is_err());
    let mut bad = original.clone();
    bad.runtimes.push(bad.runtimes[0].clone());
    assert!(bad.validate().is_err());
    // A registration changed after signing no longer matches its pinned package digest.
    let mut bad = original;
    bad.tools[0].entry_point = "/opt/tool/acme.other/tool.jar".into();
    assert!(bad.validate().is_err());
}

/// A fake engine CLI: `start` plays a tool speaking tool protocol v2, sending the broker requests listed in
/// `requests` and returning the answers it read in its RESULT. Everything else answers as a healthy engine.
#[cfg(unix)]
fn fake_engine(dir: &std::path::Path) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;
    let capabilities = r#"{"OSType":"linux","MemoryLimit":true,"SwapLimit":true,"PidsLimit":true,"CpuCfsQuota":true,"SecurityOptions":["name=seccomp,profile=builtin"]}"#;
    let d = dir.display();
    let script = format!(
        r#"#!/bin/sh
case "$5" in
info) printf '%s\n' '{capabilities}' ;;
image) printf '%s\n' "{{\"Id\":\"$7\",\"Os\":\"linux\",\"Config\":{{}}}}" ;;
ps) exit 0 ;;
rm) exit 0 ;;
create) case "$*" in *-version*) printf probe > '{d}/'"$7" ;; *) printf tool > '{d}/'"$7" ;; esac ;;
start)
  if [ "$(cat '{d}/'"$8")" = probe ]; then printf 'openjdk version "21.0.12.1" 2026-08-18 LTS\n' >&2; exit 0; fi
  read -r input
  printf '%s' "$input" > '{d}/input'
  exec 3< '{d}/requests'
  read -r r1 <&3; read -r r2 <&3
  printf '%s\n' "$r1"; read -r a
  printf '%s\n' "$r2"; read -r b
  printf '{{"kind":"RESULT","resultType":"complete","content":[],"structuredContent":{{"first":%s,"second":%s}},"isError":false}}\n' "$a" "$b"
  ;;
*) exit 1 ;;
esac
"#
    );
    let binary = dir.join("docker-fixture");
    std::fs::write(&binary, script).unwrap();
    std::fs::set_permissions(&binary, std::fs::Permissions::from_mode(0o700)).unwrap();
    binary
}

#[cfg(unix)]
#[tokio::test]
async fn tool_protocol_v2_brokers_only_the_signed_file_access_for_authorized_paths() {
    use std::os::unix::fs::PermissionsExt;
    struct Directory(PathBuf);
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    let dir = Directory(custody_base().join(format!(
        "toolgate-protocol-{}",
        olo_toolgate_client::identity::nonce().unwrap()
    )));
    let _custody = olo_toolgate_client::storage::ProtectedStore::open(dir.0.clone()).unwrap();
    let engine = fake_engine(&dir.0);
    let files = dir.0.join("files");
    let _area = olo_toolgate_client::storage::ProtectedStore::open(files.clone()).unwrap();
    std::fs::write(files.join("notes.txt"), "hello").unwrap();
    std::fs::set_permissions(
        files.join("notes.txt"),
        std::fs::Permissions::from_mode(0o600),
    )
    .unwrap();
    let read = r#"{"kind":"BROKER_REQUEST","requestId":"b-1","operation":"FILES_READ","request":{"path":"notes.txt"}}"#;
    let write_other = r#"{"kind":"BROKER_REQUEST","requestId":"b-2","operation":"FILES_WRITE","request":{"path":"other.txt","text":"x"}}"#;
    let write_same = r#"{"kind":"BROKER_REQUEST","requestId":"b-2","operation":"FILES_APPEND","request":{"path":"notes.txt","text":" world"}}"#;
    let route = r#"{"kind":"BROKER_REQUEST","requestId":"b-2","operation":"ROUTE_CALL","request":{"route":"x"}}"#;
    let run = |access: Option<LocalFileAccess>, first: &str, second: &str| {
        let engine = engine.clone();
        let state = dir.0.join("runtimes");
        let files = files.clone();
        std::fs::write(dir.0.join("requests"), format!("{first}\n{second}\n")).unwrap();
        async move {
            let mut settings = settings(engine, state, access);
            settings.files = Some(olo_toolgate_client::files::Settings {
                root: files,
                max_file_bytes: 65536,
                max_entries: 64,
                extensions: vec!["txt".into()],
            });
            let policy = Arc::new(Policy {
                result: Ok(()),
                calls: Mutex::new(vec![]),
            });
            let mut manager = Manager::new(settings, policy.clone()).unwrap();
            let output = manager
                .invoke(
                    LocalToolInput {
                        protocol_version: 1,
                        request_id: "inv-device-1".into(),
                        tool_id: "acme.notes/notes.read".into(),
                        arguments: serde_json::from_value(json!({"path": "notes.txt"})).unwrap(),
                    },
                    olo_toolgate_client::now() + 60000,
                )
                .await
                .unwrap();
            assert_eq!(
                policy.calls.lock().unwrap().len(),
                1,
                "one authorization per invocation"
            );
            serde_json::to_value(output.output).unwrap()
        }
    };

    // READ: the authorized path is read; a write is refused whatever the path.
    let out = run(Some(LocalFileAccess::Read), read, write_same).await;
    assert_eq!(out["first"]["result"]["text"], "hello", "{out}");
    assert!(
        out["second"]["error"]["message"]
            .as_str()
            .unwrap()
            .contains("does not grant"),
        "{out}"
    );
    let input: serde_json::Value =
        serde_json::from_slice(&std::fs::read(dir.0.join("input")).unwrap()).unwrap();
    assert_eq!(input["protocolVersion"], 2);
    assert_eq!(input["invocationId"], "inv-device-1");
    assert_eq!(input["arguments"], json!({"path": "notes.txt"}));
    // WRITE: only paths the invocation was authorized with.
    let out = run(Some(LocalFileAccess::Write), write_other, write_same).await;
    assert!(
        out["first"]["error"]["message"]
            .as_str()
            .unwrap()
            .contains("not one this invocation was authorized with"),
        "{out}"
    );
    assert!(out["second"].get("result").is_some(), "{out}");
    assert!(!files.join("other.txt").exists());
    assert_eq!(
        std::fs::read_to_string(files.join("notes.txt")).unwrap(),
        "hello world"
    );
    // No grant: no files at all. Route calls belong to Tool Hosts.
    let out = run(None, read, route).await;
    assert!(
        out["first"]["error"]["message"]
            .as_str()
            .unwrap()
            .contains("does not grant"),
        "{out}"
    );
    assert!(
        out["second"]["error"]["message"]
            .as_str()
            .unwrap()
            .contains("not available on a device"),
        "{out}"
    );
}

/// Without a local Gateway credential a device runs only what Control relays and authorizes; a local call is refused
/// before the tool starts.
#[cfg(unix)]
#[tokio::test]
async fn without_a_local_gateway_credential_local_calls_are_refused() {
    struct Directory(PathBuf);
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    let dir = Directory(custody_base().join(format!(
        "toolgate-relay-only-{}",
        olo_toolgate_client::identity::nonce().unwrap()
    )));
    let _custody = olo_toolgate_client::storage::ProtectedStore::open(dir.0.clone()).unwrap();
    let engine = fake_engine(&dir.0);
    std::fs::write(
        dir.0.join("requests"),
        "

",
    )
    .unwrap();
    let mut manager = Manager::new(
        settings(engine, dir.0.join("runtimes"), None),
        Arc::new(olo_toolgate_client::execution::RelayOnly),
    )
    .unwrap();
    let refused = manager
        .invoke(
            LocalToolInput {
                protocol_version: 1,
                request_id: "inv-local-1".into(),
                tool_id: "acme.notes/notes.read".into(),
                arguments: serde_json::from_value(json!({"path": "notes.txt"})).unwrap(),
            },
            olo_toolgate_client::now() + 60000,
        )
        .await;
    assert_eq!(refused.err(), Some(Failure::Unauthorized));
    assert!(!dir.0.join("input").exists(), "the tool never started");
}

/// Explicitly invoked by the real-engine CI gate with the Hello World package on the approved JRE base.
#[tokio::test]
#[ignore = "requires tools/client/runtime_check.py real Docker fixture provisioning"]
async fn real_engine_runs_hello_world_over_tool_protocol_v2() {
    let image = std::env::var("TOOLGATE_RUNTIME_TEST_IMAGE").expect("real image evidence required");
    let state = PathBuf::from(std::env::var("TOOLGATE_RUNTIME_TEST_STATE").unwrap());
    let engine = PathBuf::from(std::env::var("TOOLGATE_RUNTIME_TEST_ENGINE").unwrap());
    let mut settings = settings(engine, state, None);
    settings.engine_endpoint = std::env::var("TOOLGATE_RUNTIME_TEST_ENDPOINT").unwrap();
    settings.runtimes[0].image = image;
    let mut registration = settings.tools[0].clone();
    registration.tool_id = "ololabs.hello-world/client_greet".into();
    registration.entry_point = "/opt/tool/ololabs.hello-world/tool.jar".into();
    registration.input_schema = serde_json::from_value(json!({"type":"object","additionalProperties":false,"properties":{"text":{"type":"string","default":"World","maxLength":128}},"required":[]})).unwrap();
    registration.output_schema = serde_json::from_value(json!({"type":"object","additionalProperties":false,"properties":{"message":{"type":"string"}},"required":["message"]})).unwrap();
    registration.authorization_profile.tool.id = registration.tool_id.clone();
    registration.authorization_profile.tool.definition.id = registration.tool_id.clone();
    registration
        .authorization_profile
        .tool
        .definition
        .input_schema = registration.input_schema.clone();
    registration
        .authorization_profile
        .tool
        .definition
        .output_schema = registration.output_schema.clone();
    registration.authorization_profile.tool.package_digest =
        olo_toolgate_client::authorization_profile::managed_package_digest(
            &registration,
            &settings.runtimes[0],
        )
        .unwrap();
    settings.tools = vec![registration];
    let mut manager = Manager::new(
        settings,
        Arc::new(Policy {
            result: Ok(()),
            calls: Mutex::new(vec![]),
        }),
    )
    .unwrap();
    assert!(manager.prepare().await.unwrap().ready);
    let output = manager
        .invoke(
            LocalToolInput {
                protocol_version: 1,
                request_id: "inv-real-1".into(),
                tool_id: "ololabs.hello-world/client_greet".into(),
                arguments: serde_json::from_value(json!({"text": "Anupriya"})).unwrap(),
            },
            olo_toolgate_client::now() + 60000,
        )
        .await
        .unwrap();
    assert_eq!(output.output["message"], "Hello Anupriya");
}
