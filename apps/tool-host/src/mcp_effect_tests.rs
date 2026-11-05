// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Actual native Session/authority/TLS/JSON/SSE; Control is scripted here and independently tested in Java.
use super::*;
use crate::{mcp::Session, stream::Limits};
use axum::{extract::State, response::IntoResponse, routing::post, Json, Router};
use olo_toolgate_contracts::v2::{McpAuthScheme, McpUpstreamAuth};
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use serde_json::{json, Value};
use std::{
    io::{Read, Write},
    sync::{atomic::AtomicUsize, Mutex, OnceLock},
    time::{Duration, Instant},
};

#[derive(Clone, Copy, Debug)]
enum Mode {
    Allow,
    Deny,
    False,
    Expired,
    Streaming,
}
#[derive(Clone)]
struct Script {
    mode: Mode,
    issued: Arc<AtomicUsize>,
    sending: Arc<AtomicUsize>,
    gate: Arc<AtomicBool>,
    owner: Arc<Mutex<Option<(String, String)>>>,
}
async fn issue(State(s): State<Script>, Json(body): Json<Value>) -> Json<Value> {
    assert_eq!(body["invocationPermit"]["jws"], "native-original-proof");
    assert_eq!(body["arguments"], json!({"recipient":"alice","amount":1.0}));
    assert_eq!(
        body["httpRequestDigest"],
        crate::digest::jcs(&body["httpRequestManifest"]).unwrap()
    );
    assert_eq!(
        body["httpRequestManifest"]["headers"]["mcp-session-id"],
        json!(["owned-session"])
    );
    assert_eq!(
        body["httpRequestManifest"]["headers"]["mcp-protocol-version"],
        json!(["2025-11-25"])
    );
    assert_eq!(
        body["httpRequestManifest"]["credentialHeaders"],
        json!(["authorization"])
    );
    assert!(body["httpRequestManifest"]["headers"]
        .get("authorization")
        .is_none());
    s.issued.fetch_add(1, Ordering::SeqCst);
    *s.owner.lock().unwrap() = Some((
        body["brokerInstanceId"].as_str().unwrap().into(),
        body["handlerId"].as_str().unwrap().into(),
    ));
    Json(
        json!({"grant":{"jws":"native-owner-grant"},"validForMs":match s.mode{Mode::Expired=>50,Mode::Streaming=>150,_=>2000}}),
    )
}
async fn sending(State(s): State<Script>, Json(body): Json<Value>) -> axum::response::Response {
    let owner = s.owner.lock().unwrap().clone().unwrap();
    assert_eq!(body["brokerInstanceId"], owner.0);
    assert_eq!(body["handlerId"], owner.1);
    assert_eq!(body["grant"]["jws"], "native-owner-grant");
    s.sending.fetch_add(1, Ordering::SeqCst);
    match s.mode {
        Mode::Deny => (
            axum::http::StatusCode::FORBIDDEN,
            Json(json!({"error":"refused"})),
        )
            .into_response(),
        Mode::False => Json(json!({"authorized":false})).into_response(),
        _ => {
            if matches!(s.mode, Mode::Expired) {
                tokio::time::sleep(Duration::from_millis(150)).await;
            }
            s.gate.store(true, Ordering::SeqCst);
            Json(json!({"authorized":true})).into_response()
        }
    }
}
fn issuer() -> &'static rcgen::Issuer<'static, rcgen::KeyPair> {
    static CA: OnceLock<rcgen::Issuer<'static, rcgen::KeyPair>> = OnceLock::new();
    CA.get_or_init(|| {
        let mut params = rcgen::CertificateParams::new(Vec::new()).unwrap();
        params.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Unconstrained);
        params.key_usages = vec![
            rcgen::KeyUsagePurpose::KeyCertSign,
            rcgen::KeyUsagePurpose::CrlSign,
        ];
        let key = rcgen::KeyPair::generate().unwrap();
        let certificate = params.self_signed(&key).unwrap();
        let file = std::env::temp_dir().join(format!(
            "toolgate-native-mcp-public-ca-{}.pem",
            crate::random_hex(16)
        ));
        let mut output = std::fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&file)
            .unwrap();
        output.write_all(certificate.pem().as_bytes()).unwrap();
        output.sync_all().unwrap();
        // Process-local public trust only; no private key or machine trust store mutation.
        std::env::set_var("SSL_CERT_FILE", file);
        std::env::remove_var("SSL_CERT_DIR");
        rcgen::Issuer::new(params, key)
    })
}
fn target(script: Script) -> (String, String, tokio::task::JoinHandle<usize>) {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let key = rcgen::KeyPair::generate().unwrap();
    let certificate = rcgen::CertificateParams::new(vec!["localhost".into(), "127.0.0.1".into()])
        .unwrap()
        .signed_by(&key, issuer())
        .unwrap();
    let pin = format!(
        "sha256/{}",
        crate::egress::spki_pin(certificate.der()).unwrap()
    );
    let config = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(
            vec![certificate.der().clone()],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key.serialize_der())),
        )
        .unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let url = format!(
        "https://127.0.0.1:{}/rpc?z=3&a=1",
        listener.local_addr().unwrap().port()
    );
    let task = tokio::task::spawn_blocking(move || {
        let until = Instant::now() + Duration::from_secs(2);
        let config = Arc::new(config);
        let mut metadata = 0;
        loop {
            let socket = match listener.accept() {
                Ok((socket, _)) => socket,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    if Instant::now() >= until {
                        assert_eq!(metadata, 2);
                        return 0;
                    }
                    std::thread::sleep(Duration::from_millis(5));
                    continue;
                }
                Err(e) => panic!("target accept {e}"),
            };
            socket
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            socket
                .set_write_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut stream = rustls::StreamOwned::new(
                rustls::ServerConnection::new(config.clone()).unwrap(),
                socket,
            );
            let mut head = Vec::new();
            let mut byte = [0u8; 1];
            while !head.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                head.push(byte[0]);
                assert!(head.len() < 16384);
            }
            let header = String::from_utf8(head).unwrap();
            assert!(header
                .to_ascii_lowercase()
                .contains("authorization: bearer invocation-secret"));
            let length: usize = header
                .lines()
                .find_map(|line| {
                    line.split_once(':')
                        .filter(|(key, _)| key.eq_ignore_ascii_case("content-length"))
                        .map(|(_, value)| value.trim().parse().unwrap())
                })
                .unwrap();
            let mut bytes = vec![0u8; length];
            stream.read_exact(&mut bytes).unwrap();
            let rpc: Value = serde_json::from_slice(&bytes).unwrap();
            let method = rpc["method"].as_str().unwrap();
            if method == "notifications/initialized" {
                metadata += 1;
                stream
                    .write_all(
                        b"HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                    )
                    .unwrap();
                stream.flush().unwrap();
                continue;
            }
            let result = if method == "initialize" {
                metadata += 1;
                json!({"protocolVersion":"2025-11-25","serverInfo":{"name":"owned-native-mcp","version":"1"},"capabilities":{"tools":{}}})
            } else {
                assert_eq!(method, "tools/call");
                assert!(
                    script.gate.load(Ordering::SeqCst),
                    "business wire preceded fresh send gate"
                );
                assert_eq!(rpc["params"]["name"], "send");
                assert_eq!(rpc["params"]["arguments"]["recipient"], "alice");
                assert_eq!(rpc["params"]["arguments"]["amount"].as_u64(), Some(1));
                assert_eq!(rpc["id"], script.owner.lock().unwrap().clone().unwrap().1);
                assert!(header
                    .to_ascii_lowercase()
                    .contains("mcp-session-id: owned-session"));
                assert!(header.starts_with("POST /rpc?a=1&z=3 HTTP/1.1"));
                json!({"content":[],"structuredContent":{"ok":true},"isError":false})
            };
            let response = json!({"jsonrpc":"2.0","id":rpc["id"],"result":result}).to_string();
            if method == "tools/call" && matches!(script.mode, Mode::Streaming) {
                stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n").unwrap();
                stream.flush().unwrap();
                std::thread::sleep(Duration::from_millis(300));
                stream
                    .write_all(format!("data: {response}\n\n").as_bytes())
                    .unwrap();
                stream.flush().unwrap();
                return 1;
            }
            let reply=format!("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nMcp-Session-Id: owned-session\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{response}",response.len());
            stream.write_all(reply.as_bytes()).unwrap();
            stream.flush().unwrap();
            if method == "tools/call" {
                return 1;
            }
        }
    });
    (url, pin, task)
}

#[tokio::test]
async fn native_session_requires_fresh_owner_gate_preserves_context_and_holds_late_sse_transport() {
    for mode in [
        Mode::Allow,
        Mode::Deny,
        Mode::False,
        Mode::Expired,
        Mode::Streaming,
    ] {
        let script = Script {
            mode,
            issued: Arc::new(AtomicUsize::new(0)),
            sending: Arc::new(AtomicUsize::new(0)),
            gate: Arc::new(AtomicBool::new(false)),
            owner: Arc::new(Mutex::new(None)),
        };
        let (url, pin, peer) = target(script.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let app = Router::new()
            .route("/api/control/v1/access/effects/authorize", post(issue))
            .route("/api/control/v1/access/effects/sending", post(sending))
            .with_state(script.clone());
        let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let config =
            serde_json::from_value(json!({"tenantId":"10000000-0000-4000-8000-000000000001",
            "url":format!("http://{address}"),"developmentLoopbackHttp":true}))
            .unwrap();
        let control = Control::new(&config).unwrap();
        let corpus: Value = serde_json::from_str(include_str!(
            "../../../tests/fixtures/contracts/v2/valid.json"
        ))
        .unwrap();
        let mut claims: InvocationPermitClaims =
            serde_json::from_value(corpus["InvocationPermitClaims"].clone()).unwrap();
        claims.invocation_id = "native-operation".into();
        claims.target.tool_id = "mcp.orders/send".into();
        claims.deadline_unix_ms = crate::now_ms() + 5000;
        let mut dispatch: Dispatch = serde_json::from_value(corpus["Dispatch"].clone()).unwrap();
        dispatch.arguments =
            serde_json::from_value(json!({"recipient":"alice","amount":1.0})).unwrap();
        dispatch.permit.jws = "native-original-proof".into();
        let profile: ServiceProfile =
            serde_json::from_value(corpus["ServiceProfile"].clone()).unwrap();
        let authority = Authority::new(
            &control,
            &dispatch,
            &claims,
            &profile,
            "fence",
            claims.deadline_unix_ms,
        );
        let auth = McpUpstreamAuth {
            secret_name: "orders-token".into(),
            scheme: McpAuthScheme::Bearer,
            header: None,
        };
        let deadline = tokio::time::Instant::now() + Duration::from_secs(4);
        let mut session = Session::open(
            &url,
            &[pin],
            &auth,
            "invocation-secret".into(),
            true,
            Limits::MCP,
            deadline,
        )
        .unwrap();
        session.initialize().await.unwrap();
        let response = session
            .call_authorized(
                "send",
                json!({"recipient":"alice","amount":1.0}),
                &authority,
                "orders",
            )
            .await;
        let allowed = matches!(mode, Mode::Allow | Mode::Streaming);
        assert_eq!(response.is_ok(), allowed, "{mode:?}: {response:?}");
        assert_eq!(peer.await.unwrap(), usize::from(allowed), "{mode:?}");
        assert_eq!(script.issued.load(Ordering::SeqCst), 1);
        assert_eq!(script.sending.load(Ordering::SeqCst), 1);
        assert!(session
            .call_authorized("send", json!({"recipient":"mallory"}), &authority, "orders")
            .await
            .is_err());
        assert_eq!(
            script.issued.load(Ordering::SeqCst),
            1,
            "an owner cannot request another grant"
        );
        server.abort();
    }
}

#[tokio::test]
async fn production_executor_refuses_missing_conflicting_and_consent_reviews_before_network() {
    use rsa::pkcs8::{EncodePublicKey, LineEnding};
    let directory =
        std::env::temp_dir().join(format!("toolgate-native-review-{}", crate::random_hex(16)));
    std::fs::create_dir(&directory).unwrap();
    let key = rsa::RsaPrivateKey::new(&mut rand::thread_rng(), 2048).unwrap();
    let public = directory.join("public.pem");
    std::fs::write(
        &public,
        key.to_public_key()
            .to_public_key_pem(LineEnding::LF)
            .unwrap(),
    )
    .unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let address = listener.local_addr().unwrap();
    let config = serde_json::from_value(json!({
        "hostId":"native-host","listen":"127.0.0.1:0","permitIssuer":"control",
        "permitKeys":[{"kid":"test","publicKeyPath":public}],
        "storeDir":directory.join("store"),"workDir":directory.join("work"),
        "control":{"tenantId":"10000000-0000-4000-8000-000000000001",
            "url":format!("http://{address}"),"developmentLoopbackHttp":true}
    }))
    .unwrap();
    let host = crate::server::Host::new(config).unwrap();
    let corpus: Value = serde_json::from_str(include_str!(
        "../../../tests/fixtures/contracts/v2/valid.json"
    ))
    .unwrap();
    let claims: InvocationPermitClaims =
        serde_json::from_value(corpus["InvocationPermitClaims"].clone()).unwrap();
    let dispatch: Dispatch = serde_json::from_value(corpus["Dispatch"].clone()).unwrap();
    let mut unreviewed = corpus["McpProxyDocument"].clone();
    unreviewed["tools"][0]
        .as_object_mut()
        .unwrap()
        .remove("effectPolicy");
    assert!(
        serde_json::from_value::<olo_toolgate_contracts::v2::McpProxyDocument>(unreviewed).is_err()
    );
    let base: olo_toolgate_contracts::v2::McpProxyDocument =
        serde_json::from_value(corpus["McpProxyDocument"].clone()).unwrap();
    let policy = olo_toolgate_contracts::v2::McpEffectPolicy {
        classification: olo_toolgate_contracts::v2::EffectClassification::Write,
        consent_required: false,
        acknowledged_unfenced_risk: false,
    };
    for case in [
        "missing-review",
        "mismatch",
        "duplicate",
        "consent",
        "financial",
        "destructive",
    ] {
        let mut document = base.clone();
        document.tools.truncate(1);
        document.tools[0].tool_id = claims.target.tool_id.clone();
        document.tools[0].effect_policy = policy.clone();
        let mut profile: ServiceProfile =
            serde_json::from_value(corpus["ServiceProfile"].clone()).unwrap();
        let mut review = olo_toolgate_contracts::v2::NativeMcpEffectReview {
            tool_id: claims.target.tool_id.clone(),
            policy: policy.clone(),
            reviewed_by: "reviewer".into(),
            reviewed_at_unix_ms: 1,
        };
        if case == "mismatch" {
            review.policy.classification = olo_toolgate_contracts::v2::EffectClassification::Read;
        }
        if case == "consent" {
            review.policy.consent_required = true;
            document.tools[0].effect_policy.consent_required = true;
        }
        if matches!(case, "financial" | "destructive") {
            review.policy.classification = if case == "financial" {
                olo_toolgate_contracts::v2::EffectClassification::Financial
            } else {
                olo_toolgate_contracts::v2::EffectClassification::Destructive
            };
            review.policy.acknowledged_unfenced_risk = true;
            document.tools[0].effect_policy = review.policy.clone();
        }
        profile.native_mcp_effects = match case {
            "missing-review" => None,
            "duplicate" => Some(vec![review.clone(), review]),
            _ => Some(vec![review]),
        };
        let prepared = crate::store::Prepared {
            tool: serde_json::from_value(corpus["ToolDefinition"].clone()).unwrap(),
            runtime: serde_json::from_value(corpus["RuntimeDeclaration"].clone()).unwrap(),
            executable: directory.join("never-run"),
            profile,
            globals: Default::default(),
            routes: vec![],
            proxy: Some(document.clone()),
        };
        let (outcome, result, _) = crate::mcp_call::run(
            &host,
            &claims,
            &prepared,
            &dispatch,
            "fence",
            crate::now_ms() + 2000,
            &document,
        )
        .await;
        assert_eq!(
            outcome,
            olo_toolgate_contracts::v2::InvocationOutcome::Failed,
            "{case}"
        );
        assert_eq!(
            result.error.unwrap().code,
            "MCP_EFFECT_REVIEW_REQUIRED",
            "{case}"
        );
        assert!(
            listener.accept().is_err(),
            "no Control or upstream contact for {case}"
        );
    }
    drop(host);
    std::fs::remove_dir_all(directory).unwrap();
}
