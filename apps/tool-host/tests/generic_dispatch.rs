// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Real supervisor/private child/TLS wire sequencing; actual Control policy/CAS is tested separately in Java.
#![cfg(unix)]
use axum::{extract::State, http::StatusCode, response::IntoResponse, routing::post, Json, Router};
use olo_toolgate_contracts::v2::*;
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, HashMap},
    io::{Read, Write},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};
use tool_host::{
    broker::{answer, Invocation},
    config::ControlConfig,
    control::Control,
};

#[derive(Clone, Copy, Debug)]
enum Mode {
    Allow,
    Deny,
    False,
    Lost,
    ExpiredIssue,
    ExpiredSend,
    SlowTls,
}
#[derive(Clone)]
struct Script {
    mode: Mode,
    issued: Arc<Mutex<Option<Value>>>,
    sending: Arc<AtomicUsize>,
    gate: Arc<AtomicBool>,
}
async fn issue(State(s): State<Script>, Json(body): Json<Value>) -> Json<Value> {
    assert_eq!(body["invocationPermit"]["jws"], "original-proof");
    assert_eq!(body["arguments"], json!({"recipient":"alice","amount":1.0}));
    assert_eq!(
        body["httpRequestDigest"],
        tool_host::digest::jcs(&body["httpRequestManifest"]).unwrap()
    );
    assert_eq!(
        body["httpRequestManifest"]["bodyDigest"],
        tool_host::digest::jcs(&json!({"recipient":"alice","amount":1})).unwrap()
    );
    assert!(body["brokerInstanceId"].as_str().unwrap().len() >= 16);
    assert!(body["handlerId"].as_str().unwrap().len() >= 16);
    *s.issued.lock().unwrap() = Some(body);
    if matches!(s.mode, Mode::ExpiredIssue) {
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    Json(
        json!({"grant":{"jws":"owner-grant"},"validForMs":if matches!(s.mode,Mode::ExpiredIssue|Mode::ExpiredSend|Mode::SlowTls){100}else{2000}}),
    )
}
async fn sending(State(s): State<Script>, Json(body): Json<Value>) -> axum::response::Response {
    let issued = s.issued.lock().unwrap().clone().unwrap();
    assert_eq!(body["brokerInstanceId"], issued["brokerInstanceId"]);
    assert_eq!(body["handlerId"], issued["handlerId"]);
    assert_eq!(body["grant"]["jws"], "owner-grant");
    s.sending.fetch_add(1, Ordering::SeqCst);
    if matches!(s.mode, Mode::ExpiredSend) {
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    match s.mode {
        Mode::Deny => (StatusCode::FORBIDDEN, Json(json!({"error":"refused"}))).into_response(),
        Mode::Lost => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({"error":"lost reply"})),
        )
            .into_response(),
        Mode::False => Json(json!({"authorized":false})).into_response(),
        _ => {
            s.gate.store(true, Ordering::SeqCst);
            Json(json!({"authorized":true})).into_response()
        }
    }
}
#[tokio::test]
async fn fresh_generic_grants_guard_direct_and_private_child_transports_and_expiry_at_tls() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    for isolated in [false, true] {
        for mode in [
            Mode::Allow,
            Mode::Deny,
            Mode::False,
            Mode::Lost,
            Mode::ExpiredIssue,
            Mode::ExpiredSend,
            Mode::SlowTls,
        ] {
            let certified = rcgen::generate_simple_self_signed(vec!["127.0.0.1".into()]).unwrap();
            let mut roots = rustls::RootCertStore::empty();
            roots.add(certified.cert.der().clone()).unwrap();
            let verifier = rustls::server::WebPkiClientVerifier::builder(Arc::new(roots))
                .allow_unauthenticated()
                .build()
                .unwrap();
            let config = rustls::ServerConfig::builder()
                .with_client_cert_verifier(verifier)
                .with_single_cert(
                    vec![certified.cert.der().clone()],
                    PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
                        certified.signing_key.serialize_der(),
                    )),
                )
                .unwrap();
            let target = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            target.set_nonblocking(true).unwrap();
            let port = target.local_addr().unwrap().port();
            let script = Script {
                mode,
                issued: Arc::new(Mutex::new(None)),
                sending: Arc::new(AtomicUsize::new(0)),
                gate: Arc::new(AtomicBool::new(false)),
            };
            let gate = script.gate.clone();
            let connections = Arc::new(AtomicUsize::new(0));
            let count = connections.clone();
            let peer = tokio::task::spawn_blocking(move || {
                let until = Instant::now() + Duration::from_millis(900);
                loop {
                    match target.accept() {
                        Ok((socket, _)) => {
                            assert!(gate.load(Ordering::SeqCst), "wire before accepted gate");
                            count.fetch_add(1, Ordering::SeqCst);
                            socket
                                .set_read_timeout(Some(Duration::from_secs(2)))
                                .unwrap();
                            socket
                                .set_write_timeout(Some(Duration::from_secs(2)))
                                .unwrap();
                            if matches!(mode, Mode::SlowTls) {
                                std::thread::sleep(Duration::from_millis(250));
                            }
                            let mut stream = rustls::StreamOwned::new(
                                rustls::ServerConnection::new(Arc::new(config)).unwrap(),
                                socket,
                            );
                            let mut bytes = Vec::new();
                            let mut one = [0u8; 1];
                            while !bytes.ends_with(b"\r\n\r\n") {
                                if stream.read_exact(&mut one).is_err() {
                                    return 0;
                                }
                                bytes.push(one[0]);
                                assert!(bytes.len() < 16384);
                            }
                            assert!(
                                stream.conn.peer_certificates().is_none(),
                                "no workload identity on generic targets"
                            );
                            assert!(bytes.starts_with(b"POST /send HTTP/1.1\r\n"));
                            let mut body = vec![0u8; br#"{"amount":1,"recipient":"alice"}"#.len()];
                            stream.read_exact(&mut body).unwrap();
                            assert_eq!(body, br#"{"amount":1,"recipient":"alice"}"#);
                            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").unwrap();
                            stream.flush().unwrap();
                            return 1;
                        }
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                            if Instant::now() >= until {
                                return 0;
                            }
                            std::thread::sleep(Duration::from_millis(5));
                        }
                        Err(e) => panic!("target accept: {e}"),
                    }
                }
            });
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let app = Router::new()
                .route("/api/control/v1/access/effects/authorize", post(issue))
                .route("/api/control/v1/access/effects/sending", post(sending))
                .with_state(script.clone());
            let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
            let control = Arc::new(
                Control::new(&ControlConfig {
                    identity_trust_directory: None,
                    tenant_id: "10000000-0000-4000-8000-000000000001".into(),
                    url: format!("http://{address}"),
                    client_certificate_path: None,
                    client_key_path: None,
                    ca_certificate_path: None,
                    development_loopback_http: true,
                })
                .unwrap(),
            );
            // Control owns a usable client key; the target optionally requests it.
            // Generic egress must still present no supervisor workload certificate.
            control
                .use_identity(
                    format!(
                        "{}{}",
                        certified.cert.pem(),
                        certified.signing_key.serialize_pem()
                    )
                    .as_bytes(),
                )
                .unwrap();
            let route=serde_json::from_value(json!({"id":"send","destination":"external","method":"POST","path":"/send",
                "query":[],"headers":[],"requestContentTypes":["application/json"],"maxRequestBytes":1024,"maxResponseBytes":1024,
                "streaming":"NONE","timeoutMs":2500})).unwrap();
            let destination = serde_json::from_value(
                json!({"id":"external","scheme":"https","host":"127.0.0.1","port":port,
                "tls":{"caBundle":certified.cert.pem(),"spkiPins":[]},"mode":"BROKERED"}),
            )
            .unwrap();
            let mut invocation = Invocation::new(
                "inv".into(),
                "fence".into(),
                tool_host::now_ms() + 10000,
                "profile".into(),
                format!("sha256:{}", "a".repeat(64)),
                vec![route],
                vec![destination],
                vec![],
                vec![],
                vec![],
                HashMap::new(),
                true,
                control,
            );
            invocation.invocation_permit = Some(SignedInvocationPermit {
                jws: "original-proof".into(),
            });
            let args: BTreeMap<String, Value> =
                serde_json::from_value(json!({"recipient":"alice","amount":1.0})).unwrap();
            invocation.bind_effect_arguments(args.clone()).unwrap();
            assert!(
                invocation.bind_effect_arguments(args).is_err(),
                "admission is immutable"
            );
            let process = if isolated {
                let mut child =
                    tokio::process::Command::new(env!("CARGO_BIN_EXE_olo-toolgate-tool-host"))
                        .arg("--invocation-broker")
                        .env_clear()
                        .stdin(std::process::Stdio::piped())
                        .stdout(std::process::Stdio::piped())
                        .stderr(std::process::Stdio::piped())
                        .kill_on_drop(true)
                        .spawn()
                        .unwrap();
                let input = child.stdin.take().unwrap();
                let output = child.stdout.take().unwrap();
                let connection = invocation
                    .attach_broker(tokio::io::BufReader::new(output), input)
                    .await
                    .unwrap();
                Some((child, connection))
            } else {
                None
            };
            let response=answer(&invocation,&json!({"kind":"BROKER_REQUEST","requestId":"call","operation":"ROUTE_CALL",
                "request":{"route":"send","method":"POST","body":{"recipient":"alice","amount":1.0}}})).await;
            let business = peer.await.unwrap();
            assert_eq!(
                business,
                usize::from(matches!(mode, Mode::Allow)),
                "{mode:?} isolated={isolated} {response}"
            );
            assert_eq!(
                script.sending.load(Ordering::SeqCst),
                usize::from(!matches!(mode, Mode::ExpiredIssue)),
                "{mode:?} {response}"
            );
            if matches!(
                mode,
                Mode::Deny | Mode::False | Mode::Lost | Mode::ExpiredIssue | Mode::ExpiredSend
            ) {
                assert_eq!(connections.load(Ordering::SeqCst), 0);
            }
            if let Some((child, connection)) = process {
                connection.finish().await;
                let output = tokio::time::timeout(Duration::from_secs(2), child.wait_with_output())
                    .await
                    .unwrap()
                    .unwrap();
                assert!(
                    output.status.success(),
                    "{}",
                    String::from_utf8_lossy(&output.stderr)
                );
            }
            server.abort();
        }
    }
}
