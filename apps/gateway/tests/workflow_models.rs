// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Actual HTTPS provider calls; fallback never replays effects or bypasses authority.
use axum::{
    body::{to_bytes, Body},
    http::{Request, Response, StatusCode},
};
use olo_toolgate_contracts::v2::RequestContext;
use olo_toolgate_contracts::*;
use olo_toolgate_gateway::{
    application::Gateway,
    auth::Authenticator,
    config::Config,
    http::{runtime_router, AppState},
    relay::{PortFuture, RelayPort, WorkflowModel},
    unix_ms,
    validation::Contracts,
};
use serde_json::{json, Value};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use tower::ServiceExt;

struct Authority {
    origin: String,
    calls: Mutex<Vec<u8>>,
    denied: AtomicBool,
    unapproved: AtomicBool,
}
impl RelayPort for Authority {
    fn workflow_model<'a>(
        &'a self,
        _run: &'a str,
        _step: &'a str,
        index: u8,
    ) -> PortFuture<'a, Result<WorkflowModel, ErrorCode>> {
        Box::pin(async move {
            self.calls.lock().unwrap().push(index);
            if index == 1 && self.denied.load(Ordering::SeqCst) {
                return Err(ErrorCode::Forbidden);
            }
            Ok(serde_json::from_value(json!({"origin":if self.unapproved.load(Ordering::SeqCst) { "https://unapproved.invalid" } else { &self.origin },"modelId":format!("model-{index}"),"credential":"fixture-provider-key","deploymentId":format!("deployment-{index}"),"generation":{"maxOutputTokens":256,"temperature":0,"reasoningEffort":"none"},"candidateIndex":index,"candidateCount":2,"resultSchema":{"type":"object","required":["summary"],"properties":{"summary":{"type":"string"}},"additionalProperties":false}})).unwrap())
        })
    }
    fn ready(&self, _: u64) -> bool {
        true
    }
    fn health(&self) -> PortFuture<'_, Result<(), ErrorCode>> {
        Box::pin(async { Ok(()) })
    }
    fn invoke<'a>(
        &'a self,
        _: &'a EnterpriseInvocationRequest,
    ) -> PortFuture<'a, Result<EnterpriseInvocation, ErrorCode>> {
        panic!("model fallback must not invoke tools")
    }
    fn reserve<'a>(
        &'a self,
        _: &'a EnterpriseReservationRequest,
    ) -> PortFuture<'a, Result<EnterpriseReservation, ErrorCode>> {
        panic!("model fallback must not reserve effects")
    }
    fn catalog<'a>(
        &'a self,
        _: &'a RequestContext,
    ) -> PortFuture<'a, Result<LocalToolCatalog, ErrorCode>> {
        panic!("unexpected catalog")
    }
    fn submit<'a>(
        &'a self,
        _: &'a RemoteToolSubmission,
    ) -> PortFuture<'a, Result<RemoteToolResponse, ErrorCode>> {
        panic!("unexpected submit")
    }
    fn response<'a>(
        &'a self,
        _: &'a RequestContext,
    ) -> PortFuture<'a, Result<RemoteToolResponse, ErrorCode>> {
        panic!("unexpected response")
    }
}
async fn call(state: Arc<AppState>) -> (StatusCode, Value) {
    let request = Request::builder()
        .method("POST")
        .uri("/v1/chat/completions")
        .header("host", "gate.example.test")
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {}", "a".repeat(43)))
        .header(
            "x-toolgate-run-id",
            "run-10000000-0000-4000-8000-000000000001",
        )
        .body(Body::from(
            json!({"messages":[{"role":"user","content":"fixture"}]}).to_string(),
        ))
        .unwrap();
    let response = runtime_router(state).oneshot(request).await.unwrap();
    (
        response.status(),
        serde_json::from_slice(&to_bytes(response.into_body(), 1048576).await.unwrap()).unwrap(),
    )
}
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn https_fallback_is_ordered_bounded_and_reauthorizes_before_next_candidate() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let ca = std::env::temp_dir().join(format!("olo-model-fallback-{}.pem", std::process::id()));
    std::fs::write(&ca, cert.cert.pem()).unwrap();
    let tls = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(
            vec![cert.cert.der().clone()],
            rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der()).into(),
        )
        .unwrap();
    let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(tls));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let origin = format!(
        "https://localhost:{}",
        listener.local_addr().unwrap().port()
    );
    std::env::set_var("TOOLGATE_WORKFLOW_MODEL_ORIGINS", &origin);
    std::env::set_var("TOOLGATE_WORKFLOW_MODEL_CA_FILE", &ca);
    let hits = Arc::new(Mutex::new(Vec::<String>::new()));
    let mode = Arc::new(Mutex::new(0u8));
    let (seen, behavior) = (hits.clone(), mode.clone());
    let server = tokio::spawn(async move {
        loop {
            let (socket, _) = listener.accept().await.unwrap();
            let acceptor = acceptor.clone();
            let seen = seen.clone();
            let behavior = behavior.clone();
            tokio::spawn(async move {
                let stream = acceptor.accept(socket).await.unwrap();
                let service = hyper::service::service_fn(
                    move |request: hyper::Request<hyper::body::Incoming>| {
                        let seen = seen.clone();
                        let behavior = behavior.clone();
                        async move {
                            assert_eq!(request.uri().path(), "/v1/chat/completions");
                            assert_eq!(
                                request.headers()["authorization"],
                                "Bearer fixture-provider-key"
                            );
                            let value: Value = serde_json::from_slice(
                                &to_bytes(Body::new(request.into_body()), 98304)
                                    .await
                                    .unwrap(),
                            )
                            .unwrap();
                            assert_eq!(value["reasoning_effort"], "none");
                            let model = value["model"].as_str().unwrap().to_owned();
                            seen.lock().unwrap().push(model.clone());
                            let mode = *behavior.lock().unwrap();
                            let (status, body) = if model == "model-0" && mode == 0 || mode == 3 {
                                (503, "provider unavailable".into())
                            } else if model == "model-0" && mode == 4 {
                                (200,json!({"choices":[{"message":{"content":json!({"kind":"finish","result":{"status":"success"}}).to_string()}}]}).to_string())
                            } else if model == "model-0" && mode == 1 {
                                (
                                    200,
                                    json!({"choices":[{"message":{"content":"{not-json"}}]})
                                        .to_string(),
                                )
                            } else {
                                (200,json!({"choices":[{"message":{"content":json!({"kind":"finish","result":{"summary":"confirmed"}}).to_string()}}]}).to_string())
                            };
                            Ok::<_, std::convert::Infallible>(
                                Response::builder()
                                    .status(status)
                                    .header("content-type", "application/json")
                                    .body(Body::from(body))
                                    .unwrap(),
                            )
                        }
                    },
                );
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), service)
                    .await;
            });
        }
    });
    let authority = Arc::new(Authority {
        origin,
        calls: Mutex::new(vec![]),
        denied: AtomicBool::new(false),
        unapproved: AtomicBool::new(false),
    });
    let contracts = Contracts::new().unwrap();
    let hash = olo_toolgate_gateway::digest(b"unrelated-fixture-credential");
    let context=serde_json::from_value(json!({"requestId":"fixture","tenantId":"10000000-0000-4000-8000-000000000001",
        "mode":"SERVICE","agentId":"fixture-agent","workloadBindingId":"fixture-workload","chain":[],
        "credentialEpoch":1,"credentialKind":"STATIC_TOKEN","bindingId":"fixture-binding","credentialSha256":hash})).unwrap();
    let credential = olo_toolgate_gateway::auth::Credential {
        token_sha256: hash,
        context,
        expires_at_unix_ms: 4102444800000,
    };
    let auth = Authenticator::new(vec![credential], &contracts, unix_ms().unwrap()).unwrap();
    let config:Config=serde_json::from_value(json!({"tenantHosts":["gate.example.test"],"listen":"127.0.0.1:8081","managementListen":"127.0.0.1:9091","trustedTlsProxy":false,"allowedOrigins":[],"control":{"tenantId":"10000000-0000-4000-8000-000000000001","url":"http://127.0.0.1:8080/","tokenPath":"/run/control-token","developmentLoopbackHttp":true},"limits":{"maxBodyBytes":98304,"maxHeaderBytes":8192,"maxConcurrentRequests":4,"maxConnections":8,"requestsPerSecond":1000,"requestTimeoutMs":30000,"connectionTimeoutMs":32000,"shutdownTimeoutMs":35000,"auditQueueCapacity":256}})).unwrap();
    let state = Arc::new(
        AppState::new(
            Gateway {
                contracts,
                authority: authority.clone(),
            },
            auth,
            config,
        )
        .unwrap(),
    );
    for behavior in [0, 1, 2, 3, 4] {
        *mode.lock().unwrap() = behavior;
        hits.lock().unwrap().clear();
        authority.calls.lock().unwrap().clear();
        let (status, result) = call(state.clone()).await;
        assert_eq!(status.is_success(), behavior != 3, "{result}");
        let expected = if behavior == 2 { vec![0] } else { vec![0, 1] };
        assert_eq!(*authority.calls.lock().unwrap(), expected);
        assert_eq!(hits.lock().unwrap().len(), expected.len());
    }
    *mode.lock().unwrap() = 0;
    hits.lock().unwrap().clear();
    authority.calls.lock().unwrap().clear();
    authority.denied.store(true, Ordering::SeqCst);
    assert_eq!(call(state.clone()).await.0, StatusCode::FORBIDDEN);
    assert_eq!(*authority.calls.lock().unwrap(), vec![0, 1]);
    assert_eq!(hits.lock().unwrap().len(), 1);
    hits.lock().unwrap().clear();
    authority.calls.lock().unwrap().clear();
    authority.unapproved.store(true, Ordering::SeqCst);
    assert_eq!(call(state).await.0, StatusCode::FORBIDDEN);
    assert_eq!(*authority.calls.lock().unwrap(), vec![0]);
    assert!(hits.lock().unwrap().is_empty());
    server.abort();
    std::env::remove_var("TOOLGATE_WORKFLOW_MODEL_ORIGINS");
    std::env::remove_var("TOOLGATE_WORKFLOW_MODEL_CA_FILE");
    std::fs::remove_file(ca).unwrap();
}
