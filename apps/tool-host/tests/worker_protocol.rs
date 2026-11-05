// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Actual child stdout boundaries; namespace acceptance remains in the OCI gate.
#![cfg(target_os = "linux")]
use olo_toolgate_contracts::v2::ToolInput;
use serde_json::{json, Value};
use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tool_host::{
    broker::Invocation,
    control::Control,
    runner::{Launch, Ran},
};

struct Area(PathBuf);
impl Area {
    fn new() -> Self {
        let path =
            std::env::temp_dir().join(format!("tg-worker-protocol-{}", tool_host::random_hex(16)));
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }
    fn artifact(&self, source: &str) -> PathBuf {
        let script = self.0.join("__main__.py");
        std::fs::write(
            &script,
            format!("import json,sys,os\ni=json.loads(input())\n{source}\n"),
        )
        .unwrap();
        let artifact = self.0.join("tool.pyz");
        assert!(std::process::Command::new("/usr/bin/python3").args(["-c",
            "import zipfile,sys,pathlib; z=zipfile.ZipFile(sys.argv[1], 'w'); z.writestr('__main__.py',pathlib.Path(sys.argv[2]).read_text()); z.close()"])
            .arg(&artifact).arg(script).status().unwrap().success());
        artifact
    }
}
impl Drop for Area {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
fn input() -> ToolInput {
    let fixtures: Value = serde_json::from_str(include_str!(
        "../../../tests/fixtures/contracts/v2/valid.json"
    ))
    .unwrap();
    let mut value = fixtures["ToolInput"].clone();
    value["invocationId"] = json!("protocol-test");
    value["deadlineUnixMs"] = json!(tool_host::now_ms() + 20_000);
    serde_json::from_value(value).unwrap()
}
fn invocation(input: &ToolInput) -> Invocation {
    let config = serde_json::from_value(json!({"tenantId":"10000000-0000-4000-8000-000000000001","url":"http://127.0.0.1:1","developmentLoopbackHttp":true})).unwrap();
    let mut invocation = Invocation::new(
        "protocol-test".into(),
        "fence".into(),
        input.deadline_unix_ms,
        "profile".into(),
        format!("sha256:{}", "a".repeat(64)),
        vec![],
        vec![],
        vec![],
        vec![],
        vec![],
        Default::default(),
        true,
        Arc::new(Control::new(&config).unwrap()),
    );
    invocation.invocation_permit = Some(olo_toolgate_contracts::v2::SignedInvocationPermit {
        jws: "protocol-fixture-only".into(),
    });
    invocation.require_isolated_broker().unwrap();
    invocation
}
async fn effect_control() -> (Arc<Control>, tokio::task::JoinHandle<()>) {
    async fn issue(axum::Json(body): axum::Json<Value>) -> axum::Json<Value> {
        assert_eq!(body["invocationId"], "protocol-test");
        assert_eq!(body["fencingToken"], "fence");
        assert_eq!(body["invocationPermit"]["jws"], "protocol-fixture-only");
        assert_eq!(
            body["httpRequestDigest"],
            tool_host::digest::jcs(&body["httpRequestManifest"]).unwrap()
        );
        axum::Json(json!({"grant":{"jws":"protocol-grant"},"validForMs":2000}))
    }
    async fn sending(axum::Json(body): axum::Json<Value>) -> axum::Json<Value> {
        assert_eq!(body["grant"]["jws"], "protocol-grant");
        axum::Json(json!({"authorized":true}))
    }
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let app = axum::Router::new()
        .route(
            "/api/control/v1/access/effects/authorize",
            axum::routing::post(issue),
        )
        .route(
            "/api/control/v1/access/effects/sending",
            axum::routing::post(sending),
        );
    let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let config = serde_json::from_value(json!({"tenantId":"10000000-0000-4000-8000-000000000001",
        "url":format!("http://{address}"),"developmentLoopbackHttp":true}))
    .unwrap();
    (Arc::new(Control::new(&config).unwrap()), server)
}
async fn execute(
    area: &Area,
    artifact: &Path,
    input: &ToolInput,
    invocation: &Invocation,
    limit: usize,
) -> Ran {
    let launch = Launch {
        runtime_id: "python",
        executable: artifact,
        entry_point: "toolgate.runtime",
        sandbox_dir: &area.0,
        java_path: Path::new("/usr/bin/false"),
        python_path: Path::new("/usr/bin/python3"),
        memory_bytes: 128 * 1024 * 1024,
        max_output_bytes: limit,
    };
    tool_host::runner::run(&launch, input, input.deadline_unix_ms, None, invocation).await
}
const RESULT: &str = r#"print(json.dumps(dict(kind="RESULT",resultType="complete",isError=False,content=[],structuredContent=dict(text="<untrusted>✓"))),flush=True)"#;

#[tokio::test]
async fn actual_child_malformed_output_cannot_be_repaired_by_later_success() {
    for bad in [
        "os.write(1,b'{bad json}\\n')",
        "os.write(1,b'\\xff\\n')",
        r#"print('{"kind":"LOG","kind":"RESULT","level":"info","message":"bad"}',flush=True)"#,
        r#"print('{"kind":"LOG","level":"info","message":"bad","unexpected":"secret-marker"}',flush=True)"#,
        r#"print('{"kind":"PROGRESS","fraction":2}',flush=True)"#,
        r#"print('{"kind":"AUTHORITY","operation":"SECRET"}',flush=True)"#,
    ] {
        let area = Area::new();
        let artifact = area.artifact(&format!("{bad}\n{RESULT}"));
        let input = input();
        let ran = execute(&area, &artifact, &input, &invocation(&input), 65536).await;
        assert!(matches!(ran, Ran::Crashed(_)), "{ran:?}");
        if let Ran::Crashed(message) = ran {
            assert!(!message.contains("secret-marker"));
        }
    }
}

#[tokio::test]
async fn actual_child_fragmented_frames_are_valid_but_truncated_or_duplicate_results_fail() {
    let area = Area::new();
    let valid = r#"data=json.dumps(dict(kind="RESULT",resultType="complete",isError=False,content=[],structuredContent=dict(ok=True))).encode()+b'\n'
for byte in data: os.write(1,bytes([byte]))
"#;
    let artifact = area.artifact(valid);
    let input = input();
    assert!(matches!(
        execute(&area, &artifact, &input, &invocation(&input), 65536).await,
        Ran::Frame(_)
    ));
    for invalid in [
        r#"os.write(1,b'{"kind":"RESULT"}')"#.to_string(),
        format!("{RESULT}\n{RESULT}"),
        format!("{RESULT}\nprint(json.dumps(dict(kind='PROGRESS',fraction=.5)),flush=True)"),
        format!("{RESULT}\nsys.exit(7)"),
    ] {
        let artifact = area.artifact(&invalid);
        assert!(matches!(
            execute(&area, &artifact, &input, &invocation(&input), 65536).await,
            Ran::Crashed(_)
        ));
    }
}

#[tokio::test]
async fn actual_child_progress_and_log_floods_end_before_deadline() {
    for script in [
        format!("for n in range(10000): print(json.dumps(dict(kind='PROGRESS',fraction=.5)),flush=True)\n{RESULT}"),
        format!("print(json.dumps(dict(kind='LOG',level='info',message='x'*(2*1024*1024))),flush=True)\n{RESULT}"),
        "print(json.dumps(dict(kind='RESULT',resultType='complete',isError=False,content=[],structuredContent=dict(data='x'*(5*1024*1024)))),flush=True)".into(),
    ] {
        let area = Area::new();
        let artifact = area.artifact(&script);
        let input = input();
        let started = std::time::Instant::now();
        assert!(matches!(execute(&area, &artifact, &input, &invocation(&input), 4*1024*1024).await, Ran::Crashed(_)));
        assert!(started.elapsed() < Duration::from_secs(5));
    }
}

#[tokio::test]
async fn a_result_printed_during_an_outstanding_broker_call_cannot_finish_the_run() {
    use axum::{routing::get, Router};
    let entered = Arc::new(tokio::sync::Notify::new());
    let release = Arc::new(tokio::sync::Semaphore::new(0));
    let target_entered = entered.clone();
    let target_release = release.clone();
    let app = Router::new().route(
        "/pending",
        get(move || {
            let entered = target_entered.clone();
            let release = target_release.clone();
            async move {
                entered.notify_one();
                let _permit = release.acquire().await.unwrap();
                "settled"
            }
        }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let target = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let area = Area::new();
    let artifact = area.artifact(&format!(r#"print(json.dumps(dict(kind="BROKER_REQUEST",requestId="pending",operation="ROUTE_CALL",request=dict(route="pending",method="GET",destination="api",variables={{}},query={{}},headers={{}}))),flush=True)
{RESULT}"#));
    let input = input();
    let routes = vec![serde_json::from_value(json!({"id":"pending","destination":"api","method":"GET","path":"/pending","query":[],"headers":[],"requestContentTypes":[],"maxRequestBytes":0,"maxResponseBytes":4096,"streaming":"NONE","timeoutMs":10000})).unwrap()];
    let destinations = vec![serde_json::from_value(json!({"id":"api","host":"127.0.0.1","port":address.port(),"tls":{"spkiPins":[]},"scheme":"https","mode":"BROKERED"})).unwrap()];
    let (control, authority_server) = effect_control().await;
    let mut owned = Invocation::new(
        "protocol-test".into(),
        "fence".into(),
        input.deadline_unix_ms,
        "profile".into(),
        format!("sha256:{}", "a".repeat(64)),
        routes,
        destinations,
        vec![],
        vec![],
        vec![],
        Default::default(),
        true,
        control,
    );
    owned.invocation_permit = Some(olo_toolgate_contracts::v2::SignedInvocationPermit {
        jws: "protocol-fixture-only".into(),
    });
    owned
        .bind_effect_arguments(input.arguments.clone())
        .unwrap();
    let mut child = tokio::process::Command::new(env!("CARGO_BIN_EXE_olo-toolgate-tool-host"))
        .arg("--invocation-broker")
        .env_clear()
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let connection = owned
        .attach_broker(
            tokio::io::BufReader::new(child.stdout.take().unwrap()),
            child.stdin.take().unwrap(),
        )
        .await
        .unwrap();
    let mut task =
        tokio::spawn(async move { execute(&area, &artifact, &input, &owned, 65536).await });
    tokio::time::timeout(Duration::from_secs(5), entered.notified())
        .await
        .expect("target never received broker request");
    assert!(
        tokio::time::timeout(Duration::from_millis(100), &mut task)
            .await
            .is_err(),
        "forged result bypassed outstanding call"
    );
    release.add_permits(1);
    assert!(matches!(
        tokio::time::timeout(Duration::from_secs(5), task)
            .await
            .unwrap()
            .unwrap(),
        Ran::Frame(_)
    ));
    connection.finish().await;
    assert!(child.wait().await.unwrap().success());
    target.abort();
    authority_server.abort();
}

#[tokio::test]
async fn cancelling_an_outstanding_broker_call_closes_its_target_socket() {
    exercise_pending_call(false).await;
}
#[tokio::test]
async fn diagnostic_flooding_cancels_a_pending_broker_call_without_waiting_for_deadline() {
    exercise_pending_call(true).await;
}
async fn exercise_pending_call(stderr_flood: bool) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let entered = Arc::new(tokio::sync::Notify::new());
    let observed = entered.clone();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let target = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        let mut byte = [0u8; 1];
        while !request.ends_with(b"\r\n\r\n") {
            socket.read_exact(&mut byte).await.unwrap();
            request.push(byte[0]);
            assert!(request.len() < 16384);
        }
        observed.notify_one();
        // No response is sent: the broker request must remain outstanding.
        let eof = tokio::time::timeout(Duration::from_secs(5), socket.read(&mut byte))
            .await
            .expect("cancelled request left target connection alive")
            .unwrap();
        assert_eq!(eof, 0, "unexpected bytes after complete GET");
        let _ = socket.shutdown().await;
    });
    let area = Area::new();
    let tail = if stderr_flood {
        format!("import time\\nwhile not os.path.exists('flood-ready'): time.sleep(.01)\\nsys.stderr.write('x'*131072);sys.stderr.flush()\\n{RESULT}").replace("\\n","\n")
    } else {
        RESULT.into()
    };
    let ready = area.0.join("flood-ready");
    let artifact = area.artifact(&format!(r#"print(json.dumps(dict(kind="BROKER_REQUEST",requestId="pending",operation="ROUTE_CALL",request=dict(route="pending",method="GET",destination="api",variables={{}},query={{}},headers={{}}))),flush=True)
{tail}"#));
    let input = input();
    let routes=vec![serde_json::from_value(json!({"id":"pending","destination":"api","method":"GET","path":"/pending","query":[],"headers":[],"requestContentTypes":[],"maxRequestBytes":0,"maxResponseBytes":4096,"streaming":"NONE","timeoutMs":10000})).unwrap()];
    let destinations=vec![serde_json::from_value(json!({"id":"api","host":"127.0.0.1","port":address.port(),"tls":{"spkiPins":[]},"scheme":"https","mode":"BROKERED"})).unwrap()];
    let (control, authority_server) = effect_control().await;
    let mut owned = Invocation::new(
        "protocol-test".into(),
        "fence".into(),
        input.deadline_unix_ms,
        "profile".into(),
        format!("sha256:{}", "a".repeat(64)),
        routes,
        destinations,
        vec![],
        vec![],
        vec![],
        Default::default(),
        true,
        control,
    );
    owned.invocation_permit = Some(olo_toolgate_contracts::v2::SignedInvocationPermit {
        jws: "protocol-fixture-only".into(),
    });
    owned
        .bind_effect_arguments(input.arguments.clone())
        .unwrap();
    let mut child = tokio::process::Command::new(env!("CARGO_BIN_EXE_olo-toolgate-tool-host"))
        .arg("--invocation-broker")
        .env_clear()
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let connection = owned
        .attach_broker(
            tokio::io::BufReader::new(child.stdout.take().unwrap()),
            child.stdin.take().unwrap(),
        )
        .await
        .unwrap();
    let mut task =
        tokio::spawn(async move { execute(&area, &artifact, &input, &owned, 65536).await });
    tokio::time::timeout(Duration::from_secs(5), entered.notified())
        .await
        .expect("target did not observe outstanding call");
    if stderr_flood {
        std::fs::write(ready, "ready").unwrap();
        let result = tokio::time::timeout(Duration::from_secs(2), &mut task)
            .await
            .expect("diagnostic flood waited for the invocation deadline")
            .unwrap();
        assert!(
            matches!(result,Ran::Crashed(message) if message.contains("diagnostic output budget"))
        );
    } else {
        task.abort();
        assert!(task.await.unwrap_err().is_cancelled());
    }
    connection.finish().await;
    target.await.unwrap();
    authority_server.abort();
    tokio::time::timeout(Duration::from_secs(3), child.wait())
        .await
        .expect("broker did not stop after cancellation")
        .unwrap();
}
