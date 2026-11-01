// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Real broker sequencing and mTLS wire send against scripted Control/target.
//! Control's actual owner CAS and authorization are qualified by the Java bridge tests.
use axum::{extract::State, http::StatusCode, response::IntoResponse, routing::post, Json, Router};
use olo_toolgate_contracts::v2::*;
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
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

#[derive(Clone)]
struct Script {
    allow: bool,
    issue: Arc<Mutex<Option<Value>>>,
    sending: Arc<AtomicUsize>,
    gate: Arc<AtomicBool>,
}
async fn issue(State(s): State<Script>, Json(body): Json<Value>) -> Json<Value> {
    assert!(body["brokerInstanceId"].as_str().unwrap().len() >= 16);
    assert!(body["handlerId"].as_str().unwrap().len() >= 16);
    assert_eq!(body["invocationPermit"]["jws"], "fixture-admission");
    *s.issue.lock().unwrap() = Some(body);
    Json(json!({"jws":"fixture-service-permit"}))
}
async fn sending(State(s): State<Script>, Json(body): Json<Value>) -> axum::response::Response {
    let issued = s
        .issue
        .lock()
        .unwrap()
        .clone()
        .expect("issuance must precede sending");
    assert_eq!(body["brokerInstanceId"], issued["brokerInstanceId"]);
    assert_eq!(body["handlerId"], issued["handlerId"]);
    assert_eq!(body["permit"]["jws"], "fixture-service-permit");
    s.sending.fetch_add(1, Ordering::SeqCst);
    if s.allow {
        s.gate.store(true, Ordering::SeqCst);
        Json(json!({"authorized":true})).into_response()
    } else {
        (StatusCode::FORBIDDEN, Json(json!({"error":"refused"}))).into_response()
    }
}

#[tokio::test]
async fn a_refused_send_gate_contacts_no_target_and_an_allowed_gate_sends_once_over_mtls() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    for allow in [false, true] {
        let certified = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let mut roots = rustls::RootCertStore::empty();
        roots.add(certified.cert.der().clone()).unwrap();
        let verifier = rustls::server::WebPkiClientVerifier::builder(Arc::new(roots))
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
            allow,
            issue: Arc::new(Mutex::new(None)),
            sending: Arc::new(AtomicUsize::new(0)),
            gate: Arc::new(AtomicBool::new(false)),
        };
        let target_gate = script.gate.clone();
        let peer = tokio::task::spawn_blocking(move || {
            let until = Instant::now() + Duration::from_secs(3);
            loop {
                match target.accept() {
                    Ok((socket, _)) => {
                        assert!(
                            target_gate.load(Ordering::SeqCst),
                            "target wire must follow an accepted send gate"
                        );
                        socket
                            .set_read_timeout(Some(Duration::from_secs(3)))
                            .unwrap();
                        socket
                            .set_write_timeout(Some(Duration::from_secs(3)))
                            .unwrap();
                        let mut stream = rustls::StreamOwned::new(
                            rustls::ServerConnection::new(Arc::new(config)).unwrap(),
                            socket,
                        );
                        let mut head = Vec::new();
                        let mut byte = [0u8; 1];
                        while !head.ends_with(b"\r\n\r\n") {
                            stream.read_exact(&mut byte).unwrap();
                            head.push(byte[0]);
                            assert!(head.len() < 16384);
                        }
                        assert!(stream.conn.peer_certificates().is_some());
                        let head = String::from_utf8(head).unwrap();
                        assert!(head.starts_with("POST /v1/memory.append HTTP/1.1\r\n"));
                        assert!(
                            head.contains("toolgate-service-permit: fixture-service-permit\r\n")
                        );
                        let mut body = [0u8; 7];
                        stream.read_exact(&mut body).unwrap();
                        assert_eq!(&body, b"{\"x\":1}");
                        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}").unwrap();
                        stream.flush().unwrap();
                        return 1;
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        if Instant::now() >= until {
                            return 0;
                        }
                        std::thread::sleep(Duration::from_millis(5));
                    }
                    Err(e) => panic!("accept: {e}"),
                }
            }
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let app = Router::new()
            .route("/api/control/v1/access/service-permits/issue", post(issue))
            .route(
                "/api/control/v1/access/service-permits/sending",
                post(sending),
            )
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
        let route:RouteDeclaration=serde_json::from_value(json!({"id":"append","destination":"olo-memory","method":"POST",
            "path":"/v1/memory.append","query":[],"headers":[],"requestContentTypes":["application/json"],
            "maxRequestBytes":1024,"maxResponseBytes":1024,"streaming":"NONE","timeoutMs":3000})).unwrap();
        let destination: ServiceDestination = serde_json::from_value(
            json!({"id":"olo-memory","scheme":"https","host":"localhost","port":port,
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
            jws: "fixture-admission".into(),
        });
        let response=answer(&invocation,&json!({"requestId":"call","operation":"ROUTE_CALL","request":{"route":"append","method":"POST","body":{"x":1}}})).await;
        assert_eq!(script.sending.load(Ordering::SeqCst), 1);
        assert_eq!(
            invocation.service_effect_started.load(Ordering::SeqCst),
            allow
        );
        assert_eq!(peer.await.unwrap(), usize::from(allow));
        if allow {
            assert_eq!(invocation.egress.lock().unwrap()[0].status, Some(200));
        } else {
            assert!(response["result"]["refused"].is_string());
        }
        server.abort();
    }
}
