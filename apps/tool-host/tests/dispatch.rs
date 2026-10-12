// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Dispatch through a real host: permits signed with a test key, a fake Control, a fake
//! upstream on loopback, and a stand-in runtime (a Python script in place of `java`) that speaks
//! tool protocol v2 and calls the broker. The Java runtime itself is covered by the starter
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

/// A stand-in runtime: reads the ToolInput line, calls the broker as `arguments.since` says,
/// and prints a ResultFrame holding the broker's reply.
const RUNTIME: &str = r#"
import http.client, json, os, socket, sys
inp = json.loads(sys.stdin.readline())
args = inp["arguments"]
mode = args.get("since", 0)
if mode == 2:
    sys.exit(3)
class Unix(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect(os.environ["TOOLGATE_BROKER_SOCKET"])
call = {"route": "orders.get", "method": "GET", "destination": "acme-api",
        "variables": {"orderId": args["orderId"]}, "query": {}, "headers": {"X-On-Behalf-Of": inp["claims"].get("UserName", "")}}
capability = os.environ["TOOLGATE_BROKER_CAPABILITY"]
if mode == 1:
    call["headers"]["Authorization"] = "Bearer stolen"
if mode == 3:
    capability = "guess"
if mode == 4:
    call["route"] = "orders.delete"
c = Unix("broker")
c.request("POST", "/v1/call", json.dumps(call), {"Toolgate-Capability": capability, "Content-Type": "application/json"})
reply = json.loads(c.getresponse().read())
env = sorted(k for k in os.environ)
frame = {"kind": "RESULT", "resultType": "complete", "content": [{"type": "text", "text": "ok"}],
         "structuredContent": {"reply": reply, "env": env, "globals": inp["globals"], "secretHandles": inp["secretHandles"]}, "isError": False}
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
    reports: Vec<Value>,
    consumed: Vec<Value>,
    upstream: Vec<Value>,
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
        seen.lock().unwrap().reports.push(body);
        Json(json!({}))
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
    serve(
        Router::new()
            .route("/api/control/v1/access/permits/consume", post(consume))
            .route("/api/control/v1/access/effects/report", post(report))
            .route("/api/control/v1/access/secrets/deliver", post(secret))
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
    seen: Shared,
    target: Value,
    dir: PathBuf,
}

async fn rig() -> Option<Rig> {
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
    std::fs::write(staging.join("artifacts/app.jar"), b"not really a jar").unwrap();
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
        "control": {"url": format!("http://127.0.0.1:{control_port}"), "developmentLoopbackHttp": true},
        "runtimes": {"javaPath": runtime},
        "limits": {"maxConcurrent": 4, "maxInputBytes": 65536, "maxOutputBytes": 1048576, "syncDeadlineMs": 20000, "memoryBytes": 268435456, "maxPermitWindowMs": 30000},
        "developmentLoopbackHttp": true
    }))
    .unwrap();
    let host = tool_host::server::Host::new(config).unwrap();
    let port = serve(tool_host::server::router(host)).await;

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
        "identity": {"tenantId": "t1", "mode": "HUMAN", "userId": "u1", "chain": [], "authorizationEpoch": 1, "claims": {"UserName": "ada"}},
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
    let body =
        serde_json::to_vec(&json!({"permit": {"jws": jws}, "arguments": arguments})).unwrap();
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
            "TOOLGATE_BROKER_CAPABILITY",
            "TOOLGATE_BROKER_SOCKET",
            "TOOLGATE_PROTOCOL"
        ])
    );
    assert_eq!(out["globals"], json!({"TimeoutSeconds": 10}));
    // Without CREDENTIALS_IN_SANDBOX the secret stays with the broker.
    assert_eq!(out["secretHandles"], json!([]));

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
        (3, "the broker capability is not valid for this socket"),
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

    let (mut jws, _) = permit(&rig, &arguments, |_| {});
    jws.pop();
    jws.push('A');
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
