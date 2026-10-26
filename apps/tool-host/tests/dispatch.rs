// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Dispatch through a real host: permits signed with a test key, a fake Control, a fake
//! upstream on loopback, and a stand-in runtime (a Python script in place of `java`) that speaks
//! tool protocol v2 and calls the broker over its stdio. The Java runtime itself is covered by the starter
//! end-to-end test (e2e/run.sh).

use axum::extract::{Path as AxumPath, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::IntoResponse;
use axum::routing::{get, post};
use axum::{Json, Router};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rsa::pkcs8::{EncodePublicKey, LineEnding};
use rsa::signature::{SignatureEncoding, Signer};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use tool_host::config::Config;

const FENCE: &str = "fence-1";
const API_KEY: &str = "k-123";

/// A stand-in runtime speaking tool protocol v2 over stdio: reads the ToolInput line, sends one broker request as
/// `arguments.since` says, reads the BROKER_RESPONSE on stdin, and prints a ResultFrame holding the broker's answer.
const RUNTIME: &str = r#"
import json, os, sys
inp = json.loads(sys.stdin.readline())
args = inp["arguments"]
mode = args.get("since", 0)
if mode == 2:
    sys.exit(3)
if mode == 6:
    print(json.dumps({"kind":"RESULT","resultType":"complete","content":[],"isError":True,
        "error":{"code":"OUTCOME_UNKNOWN","message":"commit acknowledgement lost","retryable":False}}), flush=True)
    sys.exit(0)
call = {"route": "orders.get", "method": "GET", "destination": "acme-api",
        "variables": {"orderId": args["orderId"]}, "query": {}, "headers": {"X-On-Behalf-Of": inp["claims"].get("UserName", "")}}
operation, request = "ROUTE_CALL", call
if mode == 1:
    call["headers"]["Authorization"] = "Bearer stolen"
if mode == 5:
    call["headers"]["Toolgate-Service-Permit"] = "forged"
if mode == 3:
    operation, request = "FILES_READ", {"path": "notes.txt"}
if mode == 4:
    call["route"] = "orders.delete"
print(json.dumps({"kind": "LOG", "level": "info", "message": "calling the order API"}), flush=True)
print(json.dumps({"kind": "PROGRESS", "fraction": 0.5}), flush=True)
print(json.dumps({"kind": "BROKER_REQUEST", "requestId": "b-1", "operation": operation, "request": request}), flush=True)
response = json.loads(sys.stdin.readline())
assert response["kind"] == "BROKER_RESPONSE" and response["requestId"] == "b-1", response
reply = response["result"] if "result" in response else {"refused": response["error"]["message"]}
env = sorted(k for k in os.environ)
frame = {"kind": "RESULT", "resultType": "complete", "content": [{"type": "text", "text": "ok"}],
         "structuredContent": {"reply": reply, "env": env, "globals": inp["globals"], "secretHandles": inp["secretHandles"], "claims": inp["claims"],
             "traceparent": inp.get("traceparent"), "executionContext": inp.get("executionContext")}, "isError": False}
print(json.dumps(frame))
"#;

fn key() -> &'static rsa::RsaPrivateKey {
    static KEY: OnceLock<rsa::RsaPrivateKey> = OnceLock::new();
    KEY.get_or_init(|| rsa::RsaPrivateKey::new(&mut rand::thread_rng(), 2048).unwrap())
}

fn scratch(label: &str) -> PathBuf {
    static NEXT: AtomicU32 = AtomicU32::new(0);
    let dir = std::env::temp_dir().join(format!(
        "tg-host-{label}-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn fixture(name: &str) -> Value {
    let all: Value = serde_json::from_slice(
        &std::fs::read(root().join("tests/fixtures/contracts/v2/valid.json")).unwrap(),
    )
    .unwrap();
    all[name].clone()
}

fn put(dir: &Path, kind: &str, value: &Value) -> String {
    let digest = tool_host::digest::canonical(value).unwrap();
    let file = dir
        .join(kind)
        .join(format!("{}.json", digest.trim_start_matches("sha256:")));
    std::fs::create_dir_all(file.parent().unwrap()).unwrap();
    std::fs::write(file, serde_json::to_vec(value).unwrap()).unwrap();
    digest
}

#[derive(Default)]
struct Seen {
    /// Run-event batches, and the order in which events and reports reached Control.
    events: Vec<Value>,
    order: Vec<&'static str>,
    reports: Vec<Value>,
    consumed: Vec<Value>,
    upstream: Vec<Value>,
    /// Pull mode: dispatches Control holds for the host, and what the host answered.
    queued: Vec<Value>,
    admissions: Vec<Value>,
    results: Vec<Value>,
    /// DETAILED Insights span batches, by invocation ID.
    spans: Vec<(String, Value)>,
}

type Shared = Arc<Mutex<Seen>>;

async fn serve(router: Router) -> u16 {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    port
}

async fn control(seen: Shared) -> u16 {
    async fn consume(State(seen): State<Shared>, Json(body): Json<Value>) -> impl IntoResponse {
        seen.lock().unwrap().consumed.push(body.clone());
        Json(
            json!({"invocationId": body["invocationId"], "fencingToken": FENCE, "leaseExpiresAtUnixMs": 0}),
        )
    }
    async fn report(State(seen): State<Shared>, Json(body): Json<Value>) -> impl IntoResponse {
        let mut seen = seen.lock().unwrap();
        seen.order.push("report");
        seen.reports.push(body);
        Json(json!({}))
    }
    async fn events(
        State(seen): State<Shared>,
        AxumPath(id): AxumPath<String>,
        Json(body): Json<Value>,
    ) -> impl IntoResponse {
        assert_eq!(body["invocationId"], id);
        let mut seen = seen.lock().unwrap();
        seen.order.push("events");
        let through = body["events"].as_array().map_or(0, |e| e.len());
        seen.events.push(body.clone());
        Json(
            json!({"invocationId": id, "attempt": body["attempt"], "acceptedThrough": through, "duplicates": 0, "refused": 0}),
        )
    }
    async fn spans(
        State(seen): State<Shared>,
        AxumPath(id): AxumPath<String>,
        Json(body): Json<Value>,
    ) -> impl IntoResponse {
        let accepted = body["spans"].as_array().map_or(0, |s| s.len());
        seen.lock().unwrap().spans.push((id, body));
        Json(json!({"accepted": accepted, "refused": 0}))
    }
    async fn secret(headers: HeaderMap, Json(body): Json<Value>) -> axum::response::Response {
        if headers
            .get("toolgate-fencing-token")
            .and_then(|v| v.to_str().ok())
            != Some(FENCE)
            || body["name"] != "ApiKey"
        {
            return StatusCode::FORBIDDEN.into_response();
        }
        Json(json!({"value": API_KEY})).into_response()
    }
    async fn next(State(seen): State<Shared>) -> axum::response::Response {
        match seen.lock().unwrap().queued.pop() {
            Some(dispatch) => Json(dispatch).into_response(),
            None => StatusCode::NO_CONTENT.into_response(),
        }
    }
    async fn admission(
        State(seen): State<Shared>,
        AxumPath(id): AxumPath<String>,
        Json(body): Json<Value>,
    ) -> impl IntoResponse {
        assert_eq!(body["invocationId"], id);
        seen.lock().unwrap().admissions.push(body);
        Json(json!({}))
    }
    async fn result(
        State(seen): State<Shared>,
        AxumPath(id): AxumPath<String>,
        Json(body): Json<Value>,
    ) -> impl IntoResponse {
        assert_eq!(body["invocationId"], id);
        seen.lock().unwrap().results.push(body);
        Json(json!({}))
    }
    serve(
        Router::new()
            .route("/api/control/v1/tool-hosts/dispatches/next", post(next))
            .route(
                "/api/control/v1/tool-hosts/dispatches/{id}/admission",
                post(admission),
            )
            .route(
                "/api/control/v1/tool-hosts/dispatches/{id}/result",
                post(result),
            )
            .route("/api/control/v1/access/permits/consume", post(consume))
            .route("/api/control/v1/access/effects/report", post(report))
            .route(
                "/api/control/v1/access/invocations/{id}/events",
                post(events),
            )
            .route("/api/control/v1/access/secrets/deliver", post(secret))
            .route(
                "/api/control/v1/access/invocations/{id}/insights/spans",
                post(spans),
            )
            .with_state(seen),
    )
    .await
}

async fn upstream(seen: Shared) -> u16 {
    async fn order(
        State(seen): State<Shared>,
        AxumPath(id): AxumPath<String>,
        headers: HeaderMap,
    ) -> axum::response::Response {
        let header = |n: &str| {
            headers
                .get(n)
                .and_then(|v| v.to_str().ok())
                .unwrap_or("")
                .to_string()
        };
        seen.lock().unwrap().upstream.push(json!({"id": id, "apiKey": header("x-api-key"), "onBehalfOf": header("x-on-behalf-of"), "authorization": header("authorization")}));
        if header("x-api-key") != API_KEY {
            return StatusCode::UNAUTHORIZED.into_response();
        }
        (
            [("set-cookie", "session=1"), ("x-request-id", "up-1")],
            Json(json!({"orderId": id, "status": "OPEN", "items": []})),
        )
            .into_response()
    }
    serve(
        Router::new()
            .route("/orders/{id}", get(order))
            .with_state(seen),
    )
    .await
}

struct Rig {
    url: String,
    host: Arc<tool_host::server::Host>,
    seen: Shared,
    target: Value,
    dir: PathBuf,
}

async fn rig() -> Option<Rig> {
    rig_for(false).await
}

async fn rig_for(python_sdk: bool) -> Option<Rig> {
    let python = ["/usr/bin/python3", "/usr/local/bin/python3", "/bin/python3"]
        .into_iter()
        .find(|p| Path::new(p).exists())?;
    let dir = scratch("rig");
    let seen: Shared = Arc::default();
    let control_port = control(seen.clone()).await;
    let upstream_port = upstream(seen.clone()).await;

    // The package: the contract fixture's tool, packed by the CLI's packager.
    let mut manifest = fixture("PackageManifest");
    let m = manifest.as_object_mut().unwrap();
    m.remove("descriptorSetDigest");
    for tool in m["tools"].as_array_mut().unwrap() {
        tool.as_object_mut().unwrap().remove("toolDescriptorDigest");
    }
    for artifact in m["artifacts"].as_array_mut().unwrap() {
        artifact.as_object_mut().unwrap().remove("artifactDigest");
        artifact.as_object_mut().unwrap().remove("sizeBytes");
    }
    let staging = dir.join("staging");
    std::fs::create_dir_all(staging.join("artifacts")).unwrap();
    std::fs::write(
        staging.join("toolgate-package.json"),
        serde_json::to_vec(&manifest).unwrap(),
    )
    .unwrap();
    if !python_sdk {
        std::fs::write(staging.join("artifacts/app.jar"), b"not really a jar").unwrap();
    }
    if python_sdk {
        let project = dir.join("python-project");
        std::fs::create_dir_all(&project).unwrap();
        std::fs::write(
            project.join("pyproject.toml"),
            r#"
[project]
name = "orders"
version = "1.0.0"
[tool.toolgate]
descriptor_generation = 1
modules = ["orders"]
"#,
        )
        .unwrap();
        std::fs::write(project.join("orders.py"), r#"
from typing import Annotated, Any
from toolgate import *
tool_package(id="acme.orders", name="Orders", publisher="Acme")
destination(id="acme-api", host_setting="AcmeApiHost")
http_auth(id="acme-key", type="API_KEY", header="X-Api-Key", secret="ApiKey")
http_route(id="orders.get", destination="acme-api", method="GET", path="/orders/{orderId:[0-9]{6,12}}", headers=["X-On-Behalf-Of"], auth="acme-key")
@server_tool(name="orders.lookup", title="Lookup", description="Lookup", effect=EffectSafety.READ_ONLY, routes=["orders.get"])
@Secret(name="ApiKey")
@Global(name="TimeoutSeconds", type=ValueType.INT, default_value="10")
@JwtClaim(name="UserName", claim="UserName")
def lookup(order_id: Annotated[str, Param(name="orderId", description="Id")], *, context: ToolContext) -> dict[str, Any]:
    context.log.info("Calling the order API")
    context.progress(.5, "calling")
    return context.http.route("orders.get").path_var("orderId", order_id).header("X-On-Behalf-Of", context.jwt.get_str("UserName")).send(dict[str, Any])
"#).unwrap();
        // The ordinary SDK staging command builds the executable and manifest, rather than a test runtime double.
        let built = std::process::Command::new(python)
            .args(["-m", "toolgate", "stage"])
            .arg(&project)
            .arg("--output")
            .arg(&staging)
            .env("PYTHONPATH", root().join("tool-sdk/python-sdk/src"))
            .output()
            .unwrap();
        assert!(
            built.status.success(),
            "{}",
            String::from_utf8_lossy(&built.stderr)
        );
    }
    let (package, report) = toolgate_cli::package::pack(&staging).unwrap();
    let packaged = toolgate_cli::package::verify(&package).unwrap().manifest;
    let store = dir.join("store");
    std::fs::create_dir_all(store.join("packages")).unwrap();
    std::fs::write(
        store.join("packages").join(format!(
            "{}.tgpkg",
            report.package_digest.trim_start_matches("sha256:")
        )),
        &package,
    )
    .unwrap();

    let mut profile = fixture("ServiceProfile");
    profile["destinations"][0]["host"] = json!("127.0.0.1");
    profile["destinations"][0]["port"] = json!(upstream_port);
    let profile_digest = put(&store, "profiles", &profile);
    let globals_digest = put(&store, "globals", &json!({"TimeoutSeconds": 10}));

    let runtime = dir.join("java");
    std::fs::write(&runtime, format!("#!{python}\n{RUNTIME}")).unwrap();
    std::fs::set_permissions(
        &runtime,
        std::os::unix::fs::PermissionsExt::from_mode(0o755),
    )
    .unwrap();

    let public = dir.join("permit.pem");
    std::fs::write(
        &public,
        key()
            .to_public_key()
            .to_public_key_pem(LineEnding::LF)
            .unwrap(),
    )
    .unwrap();
    let config: Config = serde_json::from_value(json!({
        "hostId": "host-1",
        "listen": "127.0.0.1:0",
        "permitKeys": [{"kid": "k1", "publicKeyPath": public}],
        "permitIssuer": "control",
        "storeDir": store,
        "workDir": dir.join("work"),
        "control": {"tenantId":"10000000-0000-4000-8000-000000000001","url": format!("http://127.0.0.1:{control_port}"), "developmentLoopbackHttp": true},
        "runtimes": {"javaPath": runtime, "pythonPath": python},
        "limits": {"maxConcurrent": 4, "maxInputBytes": 65536, "maxOutputBytes": 1048576, "syncDeadlineMs": 20000, "memoryBytes": 268435456, "maxPermitWindowMs": 30000},
        "developmentLoopbackHttp": true
    }))
    .unwrap();
    let host = tool_host::server::Host::new(config).unwrap();
    let port = serve(tool_host::server::router(host.clone())).await;

    let tool = &packaged["tools"][0];
    let target = json!({
        "toolId": tool["descriptor"]["toolId"],
        "toolDescriptorDigest": tool["toolDescriptorDigest"],
        "packageDigest": report.package_digest,
        "artifactDigest": packaged["artifacts"][0]["artifactDigest"],
        "runtimeDigest": tool_host::digest::canonical(&packaged["runtimes"][0]).unwrap(),
        "globalsDigest": globals_digest,
        "serviceProfileDigest": profile_digest,
        "serviceProfileRevision": profile["revision"],
        "deploymentBindingDigest": format!("sha256:{}", "c".repeat(64)),
        "deploymentBindingRevision": 1,
        "configRevision": 1,
        "routes": ["orders.get"]
    });
    Some(Rig {
        url: format!("http://127.0.0.1:{port}/v1/dispatch"),
        host,
        seen,
        target,
        dir,
    })
}

static NEXT_ID: AtomicU32 = AtomicU32::new(0);

/// Signed permit claims for `arguments`; `edit` changes them before signing.
fn permit(rig: &Rig, arguments: &Value, edit: impl FnOnce(&mut Value)) -> (String, String) {
    let now = tool_host::now_ms();
    let id = format!("inv-{}", NEXT_ID.fetch_add(1, Ordering::Relaxed));
    let mut claims = json!({
        "jti": format!("jti-{id}"), "iss": "control", "aud": "host-1",
        "iat": now, "nbf": now, "exp": now + 20_000, "nonce": format!("n-{id}"),
        "invocationId": id, "attempt": 1,
        "identity": {"tenantId": "10000000-0000-4000-8000-000000000001", "mode": "HUMAN", "userId": "u1", "chain": [], "authorizationEpoch": 1, "claims": {"UserName": "ada"}},
        "target": rig.target,
        "catalogRevision": "rev-1",
        "argumentsDigest": tool_host::digest::jcs(arguments).unwrap(),
        "action": "orders.lookup", "resources": [],
        "effectSafety": "READ_ONLY", "requiresExecutionLedger": false,
        "approvalIds": [], "deadlineUnixMs": now + 20_000
    });
    edit(&mut claims);
    let header = URL_SAFE_NO_PAD
        .encode(br#"{"alg":"RS256","typ":"toolgate-invocation-permit+jws","kid":"k1"}"#);
    let payload = URL_SAFE_NO_PAD.encode(serde_json::to_vec(&claims).unwrap());
    let signing = rsa::pkcs1v15::SigningKey::<rsa::sha2::Sha256>::new(key().clone());
    let signature = signing
        .sign(format!("{header}.{payload}").as_bytes())
        .to_bytes();
    (
        format!("{header}.{payload}.{}", URL_SAFE_NO_PAD.encode(signature)),
        claims["invocationId"].as_str().unwrap().to_string(),
    )
}

/// Dispatches and returns the NDJSON lines.
async fn dispatch(rig: &Rig, jws: &str, arguments: &Value) -> Vec<Value> {
    send(rig, json!({"permit": {"jws": jws}, "arguments": arguments})).await
}

async fn send(rig: &Rig, dispatch: Value) -> Vec<Value> {
    let body = serde_json::to_vec(&dispatch).unwrap();
    let text = reqwest::Client::new()
        .post(&rig.url)
        .body(body)
        .send()
        .await
        .unwrap()
        .text()
        .await
        .unwrap();
    text.lines()
        .map(|l| serde_json::from_str(l).unwrap())
        .collect()
}

macro_rules! rig {
    () => {
        match rig().await {
            Some(rig) => rig,
            None => {
                eprintln!("skipped: python3 is not installed");
                return;
            }
        }
    };
}

#[tokio::test]
async fn a_real_python_sdk_package_runs_through_admission_broker_and_control_reporting() {
    let Some(rig) = rig_for(true).await else {
        panic!("Python is required for the SDK end-to-end test")
    };
    let arguments = json!({"orderId": "123456"});
    let (jws, _) = permit(&rig, &arguments, |_| {});
    let lines = dispatch(&rig, &jws, &arguments).await;
    assert_eq!(lines[0]["kind"], "ADMITTED", "{lines:?}");
    assert_eq!(lines[1]["outcome"], "COMPLETED", "{lines:?}");
    assert_eq!(lines[1]["result"]["structuredContent"]["orderId"], "123456");
    let seen = rig.seen.lock().unwrap();
    assert_eq!(seen.upstream[0]["apiKey"], API_KEY);
    assert_eq!(seen.upstream[0]["onBehalfOf"], "ada");
    assert_eq!(seen.reports[0]["outcome"], "COMPLETED");
    assert!(!seen.events.is_empty());
}

/// ADR 031 and conformance case `mcp-core/trace-propagation`: the tool input continues the dispatch's trace with the
/// host's own span, and carries the caller's declared IDs beside the execution ID the host derives. A caller cannot
/// set the execution ID or attempt.
#[tokio::test]
async fn the_tool_input_continues_the_trace_and_carries_the_execution_context() {
    let rig = rig!();
    let arguments = json!({"orderId": "123456"});
    let (jws, id) = permit(&rig, &arguments, |_| {});
    let parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    let lines = send(
        &rig,
        json!({"permit": {"jws": jws}, "arguments": arguments, "traceparent": parent,
            "executionContext": {"executionId": "ex-00000000000000000000000000000000", "attemptNumber": 9,
                "toolCallId": "toolu_01A9q", "sessionId": "sess-42", "tracestate": "vendor=opaque"}}),
    )
    .await;
    assert_eq!(lines[1]["outcome"], "COMPLETED", "{lines:?}");
    let out = &lines[1]["result"]["structuredContent"];
    let traceparent = out["traceparent"].as_str().unwrap();
    assert_eq!(&traceparent[..36], &parent[..36]);
    assert_eq!(&traceparent[52..], &parent[52..]);
    assert_ne!(&traceparent[36..52], &parent[36..52]);
    assert_eq!(
        out["executionContext"],
        json!({"executionId": tool_host::trace::execution_id("10000000-0000-4000-8000-000000000001", &id, FENCE),
            "attemptNumber": 1, "toolCallId": "toolu_01A9q", "sessionId": "sess-42", "tracestate": "vendor=opaque"})
    );
}

/// ADR 031 §9: a DETAILED dispatch sends the host's execution span (the span the tool received) and one span per
/// brokered route call beneath it, after the run and without touching the result. Without the flag nothing is sent.
#[tokio::test]
async fn a_detailed_dispatch_sends_the_execution_and_egress_spans_to_control() {
    let rig = rig!();
    let arguments = json!({"orderId": "123456"});
    let (jws, id) = permit(&rig, &arguments, |_| {});
    let parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    let lines = send(
        &rig,
        json!({"permit": {"jws": jws}, "arguments": arguments, "traceparent": parent, "collectSpans": true}),
    )
    .await;
    assert_eq!(lines[1]["outcome"], "COMPLETED", "{lines:?}");
    let tool_span = lines[1]["result"]["structuredContent"]["traceparent"]
        .as_str()
        .unwrap()[36..52]
        .to_owned();
    let mut batch = None;
    for _ in 0..100 {
        batch = rig.seen.lock().unwrap().spans.first().cloned();
        if batch.is_some() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    let (invocation, batch) = batch.expect("spans reach Control");
    assert_eq!(invocation, id);
    let spans = batch["spans"].as_array().unwrap();
    let execute = &spans[0];
    assert_eq!(execute["name"], "HOST_EXECUTE");
    assert_eq!(execute["traceId"], "0af7651916cd43dd8448eb211c80319c");
    assert_eq!(execute["spanId"], tool_span.as_str());
    assert_eq!(execute["parentSpanId"], "b7ad6b7169203331");
    assert_eq!(execute["status"], "OK");
    assert_eq!(
        execute["executionId"],
        tool_host::trace::execution_id("10000000-0000-4000-8000-000000000001", &id, FENCE)
    );
    let egress: Vec<_> = spans
        .iter()
        .filter(|s| s["name"] == "HOST_EGRESS")
        .collect();
    assert!(!egress.is_empty(), "{spans:?}");
    for span in egress {
        assert_eq!(span["parentSpanId"], tool_span.as_str());
        assert_eq!(span["httpStatus"], 200);
        assert!(span["routeId"].is_string(), "{span}");
        assert!(span.get("url").is_none());
    }

    // Without the flag the host sends nothing.
    let (jws, _) = permit(&rig, &arguments, |_| {});
    let lines = dispatch(&rig, &jws, &arguments).await;
    assert_eq!(lines[1]["outcome"], "COMPLETED", "{lines:?}");
    tokio::time::sleep(std::time::Duration::from_millis(300)).await;
    assert_eq!(rig.seen.lock().unwrap().spans.len(), 1);
}

#[tokio::test]
async fn an_admitted_invocation_runs_calls_through_the_broker_and_reports() {
    let rig = rig!();
    let arguments = json!({"orderId": "123456"});
    let (jws, id) = permit(&rig, &arguments, |_| {});
    let lines = dispatch(&rig, &jws, &arguments).await;
    assert_eq!(
        lines[0],
        json!({"kind": "ADMITTED", "invocationId": id, "fencingToken": FENCE})
    );
    assert_eq!(lines[1]["outcome"], "COMPLETED", "{lines:?}");
    let out = &lines[1]["result"]["structuredContent"];
    assert_eq!(out["reply"]["status"], 200, "{out}");
    let body = base64::engine::general_purpose::STANDARD
        .decode(out["reply"]["bodyBase64"].as_str().unwrap())
        .unwrap();
    assert_eq!(
        serde_json::from_slice::<Value>(&body).unwrap()["orderId"],
        "123456"
    );
    assert!(
        out["reply"]["headers"].get("set-cookie").is_none(),
        "Set-Cookie reaches the tool"
    );
    // A clean environment: only what the protocol gives the process.
    assert_eq!(
        out["env"],
        json!([
            "HOME",
            "LANG",
            "TOOLGATE_EXECUTION_TARGET",
            "TOOLGATE_PROTOCOL"
        ])
    );
    assert_eq!(out["globals"], json!({"TimeoutSeconds": 10}));
    // ADR 031: without caller context the tool still gets its execution ID and signed attempt, and no trace.
    assert_eq!(out["traceparent"], Value::Null);
    assert_eq!(
        out["executionContext"],
        json!({"executionId": tool_host::trace::execution_id("10000000-0000-4000-8000-000000000001", &id, FENCE),
            "attemptNumber": 1})
    );
    // Without CREDENTIALS_IN_SANDBOX the secret stays with the broker.
    assert_eq!(out["secretHandles"], json!([]));
    // M4.2: the principal comes only from the signed permit, beside the pass-through claims.
    assert_eq!(out["claims"]["UserName"], "ada");
    assert_eq!(
        out["claims"]["toolgate"],
        json!({"tenantId": "10000000-0000-4000-8000-000000000001", "mode": "HUMAN", "userId": "u1", "chain": [],
            "operation": "orders.lookup", "resources": [], "invocationId": id, "attempt": 1})
    );

    let seen = rig.seen.lock().unwrap();
    assert_eq!(seen.upstream[0]["apiKey"], API_KEY);
    assert_eq!(seen.upstream[0]["onBehalfOf"], "ada");
    assert_eq!(seen.consumed[0]["invocationId"], id);
    assert_eq!(
        seen.consumed[0]["packageDigest"],
        rig.target["packageDigest"]
    );
    assert_eq!(seen.reports[0]["outcome"], "COMPLETED");
    assert_eq!(seen.reports[0]["fencingToken"], FENCE);
    // The SDK frames reached Control in order, bound to the fencing token, before the result report.
    let frames: Vec<&Value> = seen
        .events
        .iter()
        .flat_map(|b| b["events"].as_array().unwrap())
        .collect();
    assert_eq!(seen.events[0]["fencingToken"], FENCE);
    assert_eq!(seen.events[0]["attempt"], 1);
    assert_eq!(frames.len(), 2, "{:?}", seen.events);
    assert_eq!(frames[0]["kind"], "LOG");
    assert_eq!(frames[0]["sequence"], 1);
    assert_eq!(frames[0]["message"], "calling the order API");
    assert_eq!(frames[1]["kind"], "PROGRESS");
    assert_eq!(frames[1]["sequence"], 2);
    assert_eq!(seen.order.last(), Some(&"report"));
    assert!(
        seen.order.iter().position(|o| *o == "events")
            < seen.order.iter().position(|o| *o == "report")
    );
    drop(seen);
    // The invocation's directory and socket are gone.
    assert_eq!(
        std::fs::read_dir(rig.dir.join("work/i")).unwrap().count(),
        0
    );
}

#[tokio::test]
async fn the_broker_refuses_what_the_route_does_not_allow() {
    let rig = rig!();
    for (mode, reason) in [
        (1, "header Authorization may not be set by a tool"),
        (3, "device files are not available on a Tool Host"),
        (5, "header Toolgate-Service-Permit may not be set by a tool"),
        (4, "route orders.delete is not granted to this invocation"),
    ] {
        let arguments = json!({"orderId": "123456", "since": mode});
        let (jws, _) = permit(&rig, &arguments, |_| {});
        let lines = dispatch(&rig, &jws, &arguments).await;
        assert_eq!(
            lines[1]["result"]["structuredContent"]["reply"]["refused"], reason,
            "{lines:?}"
        );
    }
    let arguments = json!({"orderId": "12", "since": 0});
    let (jws, _) = permit(&rig, &arguments, |_| {});
    let lines = dispatch(&rig, &jws, &arguments).await;
    assert_eq!(
        lines[1]["result"]["structuredContent"]["reply"]["refused"],
        "path variable orderId does not match its pattern"
    );
    assert!(
        rig.seen.lock().unwrap().upstream.is_empty(),
        "a refused call reached the upstream"
    );
}

#[tokio::test]
async fn bad_permits_are_refused_before_anything_runs() {
    let rig = rig!();
    let arguments = json!({"orderId": "123456"});
    let refused = |lines: &[Value]| lines[0]["reason"].as_str().unwrap().to_string();

    let (shadowed, _) = permit(&rig, &arguments, |claims| {
        claims["identity"]["claims"]["toolgate"] = json!({"userId": "admin"})
    });
    assert_eq!(
        refused(&dispatch(&rig, &shadowed, &arguments).await),
        "PERMIT_INVALID",
        "Pass-through claims cannot supply the reserved principal"
    );

    let (foreign, _) = permit(&rig, &arguments, |claims| {
        claims["identity"]["tenantId"] = json!("10000000-0000-4000-8000-000000000002")
    });
    assert_eq!(
        refused(&dispatch(&rig, &foreign, &arguments).await),
        "PERMIT_INVALID",
        "A valid signature and repeated host ID cannot authorize another tenant"
    );
    assert!(
        rig.seen.lock().unwrap().consumed.is_empty(),
        "Foreign permits are rejected before contacting Control"
    );

    let (jws, _) = permit(&rig, &arguments, |_| {});
    assert_eq!(
        dispatch(&rig, &jws, &arguments).await[0]["kind"],
        "ADMITTED"
    );
    assert_eq!(
        refused(&dispatch(&rig, &jws, &arguments).await),
        "PERMIT_INVALID",
        "a replayed permit"
    );

    let (jws, _) = permit(&rig, &arguments, |_| {});
    assert_eq!(
        refused(&dispatch(&rig, &jws, &json!({"orderId": "654321"})).await),
        "PERMIT_INVALID",
        "other arguments"
    );

    let (jws, _) = permit(&rig, &arguments, |c| c["aud"] = json!("host-2"));
    assert_eq!(
        refused(&dispatch(&rig, &jws, &arguments).await),
        "PERMIT_INVALID",
        "another host"
    );

    let (jws, _) = permit(&rig, &arguments, |c| {
        let past = c["iat"].as_u64().unwrap() - 60_000;
        c["iat"] = json!(past);
        c["nbf"] = json!(past);
        c["exp"] = json!(past + 10_000);
    });
    assert_eq!(
        refused(&dispatch(&rig, &jws, &arguments).await),
        "PERMIT_EXPIRED"
    );

    let (jws, _) = permit(&rig, &arguments, |c| {
        c["target"]["serviceProfileDigest"] = json!(format!("sha256:{}", "d".repeat(64)))
    });
    assert_eq!(
        refused(&dispatch(&rig, &jws, &arguments).await),
        "DIGEST_NOT_PRESENT"
    );

    let (jws, _) = permit(&rig, &arguments, |c| {
        c["target"]["routes"] = json!(["orders.get", "orders.delete"])
    });
    assert_eq!(
        refused(&dispatch(&rig, &jws, &arguments).await),
        "DIGEST_NOT_PRESENT",
        "a route the tool does not declare"
    );

    // Change a character inside the signature: the last one carries only 2 bits, and replacing it
    // can leave the signature unchanged.
    let (jws, _) = permit(&rig, &arguments, |_| {});
    let at = jws.len() - 10;
    let flipped = if &jws[at..=at] == "A" { "B" } else { "A" };
    let jws = format!("{}{flipped}{}", &jws[..at], &jws[at + 1..]);
    let lines = dispatch(&rig, &jws, &arguments).await;
    assert_eq!(refused(&lines), "PERMIT_INVALID", "a bad signature");

    // Only the one admitted permit was consumed.
    assert_eq!(rig.seen.lock().unwrap().consumed.len(), 1);
}

#[tokio::test]
async fn a_process_that_dies_is_failed_or_unknown_by_the_ledger_class() {
    let rig = rig!();
    let arguments = json!({"orderId": "123456", "since": 2});
    let (jws, _) = permit(&rig, &arguments, |_| {});
    let lines = dispatch(&rig, &jws, &arguments).await;
    assert_eq!(lines[1]["outcome"], "FAILED");
    assert_eq!(lines[1]["result"]["error"]["code"], "INTERNAL_ERROR");

    let (jws, _) = permit(&rig, &arguments, |c| {
        c["effectSafety"] = json!("NON_IDEMPOTENT");
        c["requiresExecutionLedger"] = json!(true);
    });
    let lines = dispatch(&rig, &jws, &arguments).await;
    assert_eq!(lines[1]["outcome"], "OUTCOME_UNKNOWN");
    assert_eq!(
        rig.seen.lock().unwrap().reports.last().unwrap()["outcome"],
        "OUTCOME_UNKNOWN"
    );
}

#[tokio::test]
async fn an_explicit_uncertain_commit_is_not_downgraded_to_a_failed_tool() {
    let rig = rig!();
    let arguments = json!({"orderId": "123456", "since": 6});
    let (jws, _) = permit(&rig, &arguments, |_| {});
    let lines = dispatch(&rig, &jws, &arguments).await;
    assert_eq!(lines[1]["outcome"], "OUTCOME_UNKNOWN");
    assert_eq!(
        rig.seen.lock().unwrap().reports[0]["outcome"],
        "OUTCOME_UNKNOWN"
    );
}

#[test]
fn bare_runtime_names_resolve_on_the_host_path_because_tools_start_without_one() {
    use tool_host::config::resolve;
    let sh = resolve(Path::new("sh"));
    assert!(sh.is_absolute() && sh.is_file(), "{}", sh.display());
    assert_eq!(
        resolve(Path::new("/opt/java/bin/java")),
        PathBuf::from("/opt/java/bin/java")
    );
    assert_eq!(
        resolve(Path::new("no-such-runtime-xyz")),
        PathBuf::from("no-such-runtime-xyz")
    );
}

#[tokio::test]
async fn pull_mode_takes_calls_from_control_and_answers_through_it() {
    let rig = rig!();
    let arguments = json!({"orderId": "123456"});
    let (jws, id) = permit(&rig, &arguments, |_| {});
    let (forged, forged_id) = permit(&rig, &arguments, |c| c["aud"] = json!("host-2"));
    {
        let mut seen = rig.seen.lock().unwrap();
        seen.queued
            .push(json!({"permit": {"jws": jws}, "arguments": arguments}));
        seen.queued
            .push(json!({"permit": {"jws": forged}, "arguments": arguments}));
    }
    tokio::spawn(tool_host::server::pull(rig.host.clone()));
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
    loop {
        {
            let seen = rig.seen.lock().unwrap();
            if seen.results.len() == 1 && seen.admissions.len() == 2 {
                break;
            }
        }
        assert!(
            std::time::Instant::now() < deadline,
            "the host never answered"
        );
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    let seen = rig.seen.lock().unwrap();
    // A permit for another host is refused under the id Control queued it by; nothing ran.
    let refused = seen
        .admissions
        .iter()
        .find(|a| a["invocationId"] == forged_id.as_str())
        .unwrap();
    assert_eq!(refused["kind"], "REFUSED");
    assert_eq!(refused["reason"], "PERMIT_INVALID");
    let admitted = seen
        .admissions
        .iter()
        .find(|a| a["invocationId"] == id.as_str())
        .unwrap();
    assert_eq!(admitted["kind"], "ADMITTED");
    assert_eq!(admitted["fencingToken"], FENCE);
    assert_eq!(seen.results[0]["invocationId"], id);
    assert_eq!(seen.results[0]["outcome"], "COMPLETED");
    assert_eq!(seen.reports.len(), 1);
}
