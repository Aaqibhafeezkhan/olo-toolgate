// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
use axum::{
    body::{to_bytes, Body},
    http::{Request, StatusCode},
    Router,
};
use olo_toolgate_contracts::v2::{self, RequestContext, RequestMode};
use olo_toolgate_contracts::*;
use olo_toolgate_gateway::{
    application::Gateway,
    auth::{Authenticator, Credential},
    config::Config,
    digest,
    http::{management_router, runtime_router, AppState},
    relay::{PortFuture, RelayPort},
    unix_ms,
    validation::{strict_json, Contracts},
};
use serde_json::{json, Value};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc, Mutex,
};
use tower::ServiceExt;
const TOKEN: &str = "enterprise-test-credential-00000000000000000000000000000000";
fn fixtures() -> Value {
    serde_json::from_str(include_str!(
        "../../../tests/fixtures/contracts/v1/valid.json"
    ))
    .unwrap()
}
fn context() -> RequestContext {
    serde_json::from_value(json!({"requestId":"operation","tenantId":"tenant-demo","mode":"DELEGATED","userId":"user-demo","agentId":"agent-demo","workloadBindingId":"workload","chain":[],"sessionEpoch":1,"credentialEpoch":1,"credentialKind":"STATIC_TOKEN","bindingId":"binding","credentialSha256":digest(TOKEN.as_bytes())})).unwrap()
}
fn credential() -> Credential {
    Credential {
        token_sha256: digest(TOKEN.as_bytes()),
        context: context(),
        expires_at_unix_ms: 4102444800000,
    }
}
fn config() -> Config {
    serde_json::from_value(json!({"listen":"127.0.0.1:8081","managementListen":"127.0.0.1:9091","trustedTlsProxy":false,"allowedOrigins":[],"control":{"url":"http://127.0.0.1:8080/","tokenPath":"/run/control-token","developmentLoopbackHttp":true},"limits":{"maxBodyBytes":65536,"maxHeaderBytes":8192,"maxConcurrentRequests":4,"maxConnections":8,"requestsPerSecond":1000,"requestTimeoutMs":10000,"connectionTimeoutMs":12000,"shutdownTimeoutMs":15000,"auditQueueCapacity":256}})).unwrap()
}
fn payload() -> Value {
    json!({"toolId":"files.read","action":"read","arguments":{"path":"data/report.txt"}})
}
#[derive(Default)]
struct Authority {
    failure: bool,
    pending: bool,
    substitute: bool,
    ask: bool,
    reservations: AtomicUsize,
    invocation: Mutex<Option<EnterpriseInvocation>>,
    submission: Mutex<Option<RemoteToolSubmission>>,
    permit: Mutex<Option<v2::PermitReservationRequest>>,
    /// Control placed the call on a Tool Host, so it signs a permit; otherwise the device is not
    /// a Tool Host and the reservation is a conflict.
    tool_host: bool,
    /// Pull mode: the dispatch handed to Control, and how many status polls it took.
    relayed: Mutex<Option<v2::Dispatch>>,
    polls: AtomicUsize,
}
impl Authority {
    /// The host claims the call on the second poll and finishes it on the third.
    fn relay_status(&self) -> v2::ToolHostDispatchStatus {
        let id = self.invocation.lock().unwrap().as_ref().unwrap().id.clone();
        let polls = self.polls.fetch_add(1, Ordering::SeqCst);
        let state = ["QUEUED", "ADMITTED", "FINISHED"][polls.min(2)];
        let mut status = json!({"invocationId":id,"hostId":"placed-device","state":state});
        if polls >= 1 {
            status["admission"] = json!({"kind":"ADMITTED","invocationId":id,"fencingToken":"f"});
        }
        if polls >= 2 {
            status["result"] = json!({"invocationId":id,"fencingToken":"f","outcome":"COMPLETED","result":{"kind":"RESULT","resultType":"complete","content":[{"type":"text","text":"hi"}],"structuredContent":{"greeting":"hi"},"isError":false}});
        }
        serde_json::from_value(status).unwrap()
    }
    fn progress(&self, state: RemoteToolState) -> RemoteToolResponse {
        let guard = self.submission.lock().unwrap();
        let s = guard.as_ref().unwrap();
        RemoteToolResponse {
            record: RemoteToolRecord {
                request_id: s.context.request_id.clone(),
                device_id: "placed-device".into(),
                agent_id: s.context.agent_id.clone(),
                tool_id: s.request.tool_id.clone(),
                state: state.clone(),
                received_at_unix_ms: unix_ms().unwrap(),
                expires_at_unix_ms: s.expires_at_unix_ms,
                submitted_at_unix_ms: None,
                response_at_unix_ms: None,
                completed_at_unix_ms: None,
                error: None,
            },
            result: if state == RemoteToolState::Done {
                Some(RemoteToolResult {
                    request_id: s.context.request_id.clone(),
                    lease_id: "lease".into(),
                    output: Some(serde_json::from_value(json!({"ok":true})).unwrap()),
                    error: None,
                })
            } else {
                None
            },
        }
    }
}
impl RelayPort for Authority {
    fn permit<'a>(
        &'a self,
        r: &'a v2::PermitReservationRequest,
    ) -> PortFuture<'a, Result<v2::SignedInvocationPermit, ErrorCode>> {
        Box::pin(async move {
            if !self.tool_host {
                return Err(ErrorCode::Conflict);
            }
            *self.permit.lock().unwrap() = Some(r.clone());
            Ok(v2::SignedInvocationPermit {
                jws: "e30.e30.c2ln".into(),
            })
        })
    }
    fn relay_dispatch<'a>(
        &'a self,
        dispatch: &'a v2::Dispatch,
    ) -> PortFuture<'a, Result<v2::ToolHostDispatchStatus, ErrorCode>> {
        Box::pin(async move {
            *self.relayed.lock().unwrap() = Some(dispatch.clone());
            Ok(self.relay_status())
        })
    }
    fn dispatch_status<'a>(
        &'a self,
        _: &'a str,
    ) -> PortFuture<'a, Result<v2::ToolHostDispatchStatus, ErrorCode>> {
        Box::pin(async move { Ok(self.relay_status()) })
    }
    fn ready(&self, _: u64) -> bool {
        !self.failure
    }
    fn health(&self) -> PortFuture<'_, Result<(), ErrorCode>> {
        Box::pin(async { Ok(()) })
    }
    fn catalog<'a>(
        &'a self,
        _: &'a RequestContext,
    ) -> PortFuture<'a, Result<LocalToolCatalog, ErrorCode>> {
        Box::pin(async move {
            if self.failure {
                return Err(ErrorCode::DependencyUnavailable);
            }
            Ok(LocalToolCatalog {
                tools: vec![LocalToolInfo {
                    tool_id: "files.read".into(),
                    action: "read".into(),
                    description: "Read".into(),
                    enabled: true,
                    input_schema: serde_json::from_value(json!({"type":"object"})).unwrap(),
                    tool_digest: "a".repeat(64),
                    package_digest: "b".repeat(64),
                }],
            })
        })
    }
    fn invoke<'a>(
        &'a self,
        r: &'a EnterpriseInvocationRequest,
    ) -> PortFuture<'a, Result<EnterpriseInvocation, ErrorCode>> {
        Box::pin(async move {
            if self.pending {
                std::future::pending::<()>().await;
            }
            let mut i: EnterpriseInvocation =
                serde_json::from_value(fixtures()["EnterpriseInvocation"].clone()).unwrap();
            let mut c = serde_json::to_value(&r.context).unwrap();
            // Control resolves the device; the request context never names one.
            let fields = c.as_object_mut().unwrap();
            for key in [
                "credentialSha256",
                "credentialKind",
                "issuer",
                "expiresAtUnixMs",
                "delegatedSessionEpoch",
            ] {
                fields.remove(key);
            }
            fields.insert("deviceId".into(), json!("placed-device"));
            i.id = r.context.request_id.clone();
            i.evaluation.context = serde_json::from_value(c).unwrap();
            i.evaluation.tool_id = r.request.tool_id.clone();
            i.evaluation.action = r.request.action.clone();
            i.evaluation.arguments_digest =
                digest(&serde_json::to_vec(&r.request.arguments).unwrap());
            i.evaluation.tool_digest = r.tool_digest.clone();
            i.evaluation.package_digest = r.package_digest.clone();
            i.expires_at_unix_ms = unix_ms().unwrap() + 60000;
            i.state = if self.ask {
                EnterpriseInvocationState::PendingApproval
            } else {
                EnterpriseInvocationState::Queued
            };
            i.revision = 1;
            if self.substitute {
                i.evaluation.context.binding_id = "attacker-binding".into();
            }
            *self.invocation.lock().unwrap() = Some(i.clone());
            Ok(i)
        })
    }
    fn reserve<'a>(
        &'a self,
        r: &'a EnterpriseReservationRequest,
    ) -> PortFuture<'a, Result<EnterpriseReservation, ErrorCode>> {
        Box::pin(async move {
            self.reservations.fetch_add(1, Ordering::SeqCst);
            let mut rsv: EnterpriseReservation =
                serde_json::from_value(fixtures()["EnterpriseReservation"].clone()).unwrap();
            let outcome = self.invocation.lock().unwrap().clone().unwrap();
            rsv.invocation = outcome;
            rsv.invocation.state = EnterpriseInvocationState::Reserved;
            rsv.invocation.revision = r.expected_revision + 1;
            Ok(rsv)
        })
    }
    fn submit<'a>(
        &'a self,
        r: &'a RemoteToolSubmission,
    ) -> PortFuture<'a, Result<RemoteToolResponse, ErrorCode>> {
        Box::pin(async move {
            *self.submission.lock().unwrap() = Some(r.clone());
            Ok(self.progress(RemoteToolState::Submitted))
        })
    }
    fn response<'a>(
        &'a self,
        _: &'a RequestContext,
    ) -> PortFuture<'a, Result<RemoteToolResponse, ErrorCode>> {
        Box::pin(async move { Ok(self.progress(RemoteToolState::Done)) })
    }
}
fn state(a: Arc<Authority>) -> Arc<AppState> {
    state_with(a, config())
}
fn state_with(a: Arc<Authority>, config: Config) -> Arc<AppState> {
    let contracts = Contracts::new().unwrap();
    let auth = Authenticator::new(vec![credential()], &contracts, unix_ms().unwrap()).unwrap();
    Arc::new(AppState::new(
        Gateway {
            contracts,
            authority: a,
        },
        auth,
        config,
    ))
}
fn req(path: &str, body: Value) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(path)
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {TOKEN}"))
        .header("idempotency-key", "logical-request")
        .body(Body::from(body.to_string()))
        .unwrap()
}
async fn call(router: Router, r: Request<Body>) -> (StatusCode, Value) {
    let response = router.oneshot(r).await.unwrap();
    let status = response.status();
    let bytes = to_bytes(response.into_body(), 1048576).await.unwrap();
    (status, serde_json::from_slice(&bytes).unwrap())
}
fn mcp(method: &str, name: Option<&str>) -> Request<Body> {
    let mut params = json!({"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}});
    if let Some(name) = name {
        params["name"] = json!(name);
        params["arguments"] = payload()["arguments"].clone();
    }
    let mut r = req(
        "/mcp",
        json!({"jsonrpc":"2.0","id":7,"method":method,"params":params}),
    );
    r.headers_mut().insert(
        "accept",
        "application/json, text/event-stream".parse().unwrap(),
    );
    r.headers_mut()
        .insert("mcp-protocol-version", "2026-07-28".parse().unwrap());
    r.headers_mut()
        .insert("mcp-method", method.parse().unwrap());
    if let Some(name) = name {
        r.headers_mut().insert("mcp-name", name.parse().unwrap());
    }
    r
}

#[test]
fn credentials_bind_all_facts_and_cannot_be_human_or_rebound() {
    let contracts = Contracts::new().unwrap();
    let c = credential();
    let auth = Authenticator::new(vec![c.clone()], &contracts, 1).unwrap();
    let (ctx, _) = auth.authenticate(TOKEN, "fresh-request".into(), 2).unwrap();
    assert_eq!(ctx.binding_id.as_deref(), Some("binding"));
    assert_eq!(ctx.session_epoch, Some(1));
    assert_eq!(ctx.expires_at_unix_ms, Some(c.expires_at_unix_ms));
    // Only static-token contexts can be mounted, and their expiry must match the credential.
    for change in [
        json!({"credentialKind": "JWT"}),
        json!({"expiresAtUnixMs": c.expires_at_unix_ms + 1}),
        json!({"deviceId": "device"}),
    ] {
        let mut value = serde_json::to_value(&c).unwrap();
        for (key, field) in change.as_object().unwrap() {
            value["context"][key] = field.clone();
        }
        let rejected = serde_json::from_value::<Credential>(value)
            .ok()
            .and_then(|credential| Authenticator::new(vec![credential], &contracts, 1).ok());
        assert!(rejected.is_none(), "{change}");
    }
    assert!(auth
        .authenticate(
            "unknown-credential-0000000000000000000000000000000000",
            "r".into(),
            2
        )
        .is_none());
    assert!(auth
        .authenticate(TOKEN, "r".into(), c.expires_at_unix_ms)
        .is_none());
    assert!(Authenticator::new(vec![c.clone(), c.clone()], &contracts, 1).is_err());
    let mut human = c.clone();
    human.context.mode = RequestMode::Human;
    assert!(Authenticator::new(vec![human], &contracts, 1).is_err());
    let mut changed = c;
    changed.context.credential_sha256 = Some("0".repeat(64));
    assert!(Authenticator::new(vec![changed], &contracts, 1).is_err());
}
#[test]
fn configuration_rejects_static_policy_and_unbounded_transport() {
    let contracts = Contracts::new().unwrap();
    let c = config();
    assert!(c.validate(&contracts, 1).is_ok());
    let mut old = serde_json::to_value(&c).unwrap();
    old["policy"] = json!({});
    assert!(serde_json::from_value::<Config>(old).is_err());
    let mut insecure = c.clone();
    insecure.control.url = "http://example.com/".into();
    assert!(insecure.validate(&contracts, 1).is_err());
    let mut unbounded = c;
    unbounded.limits.max_concurrent_requests = 0;
    assert!(unbounded.validate(&contracts, 1).is_err());
}
#[test]
fn strict_json_rejects_duplicates_and_sorts_hash_input() {
    assert!(strict_json(br#"{"a":{"x":1,"x":2}}"#).is_err());
    assert_eq!(
        strict_json(br#"{"z":2,"a":1}"#).unwrap().to_string(),
        r#"{"a":1,"z":2}"#
    );
}
#[tokio::test]
async fn ordinary_allow_also_requires_a_reserved_permit() {
    let a = Arc::new(Authority::default());
    let (code, body) = call(
        runtime_router(state(a.clone())),
        req("/access/invocations", payload()),
    )
    .await;
    assert_eq!(code, StatusCode::OK);
    assert_eq!(body["invocation"]["state"], "RESERVED");
    assert!(body["reservation"]["permit"]["jws"].is_string());
    assert_eq!(a.reservations.load(Ordering::SeqCst), 1);
    assert!(body.to_string().find(TOKEN).is_none());
}
#[tokio::test]
async fn ask_returns_pending_without_effect_capability() {
    let a = Arc::new(Authority {
        ask: true,
        ..Default::default()
    });
    let (code, body) = call(
        runtime_router(state(a.clone())),
        req("/access/invocations", payload()),
    )
    .await;
    assert_eq!(code, StatusCode::OK);
    assert_eq!(body["invocation"]["state"], "PENDING_APPROVAL");
    assert!(body.get("reservation").is_none());
    assert_eq!(a.reservations.load(Ordering::SeqCst), 0);
}
#[tokio::test]
async fn substituted_target_or_unavailable_authority_never_grants() {
    for a in [
        Authority {
            substitute: true,
            ..Default::default()
        },
        Authority {
            failure: true,
            ..Default::default()
        },
    ] {
        let (code, body) = call(
            runtime_router(state(Arc::new(a))),
            req("/access/invocations", payload()),
        )
        .await;
        assert_ne!(code, StatusCode::OK);
        assert!(body.get("reservation").is_none());
    }
}
#[tokio::test]
async fn malformed_body_authentication_and_retired_routes_fail_closed() {
    let s = state(Arc::new(Authority::default()));
    for path in ["/v1/authorize", "/v2/authorize", "/v1/permits/consume"] {
        assert_eq!(
            call(runtime_router(s.clone()), req(path, payload()))
                .await
                .0,
            StatusCode::NOT_FOUND
        );
    }
    let mut bad = req("/access/invocations", payload());
    bad.headers_mut().remove("authorization");
    assert_eq!(
        call(runtime_router(s.clone()), bad).await.0,
        StatusCode::UNAUTHORIZED
    );
    let mut duplicate = req("/access/invocations", payload());
    duplicate
        .headers_mut()
        .append("authorization", format!("Bearer {TOKEN}").parse().unwrap());
    assert_eq!(
        call(runtime_router(s.clone()), duplicate).await.0,
        StatusCode::UNAUTHORIZED
    );
    let mut forged = payload();
    forged["context"] = json!({"userId":"administrator"});
    assert_eq!(
        call(
            runtime_router(s.clone()),
            req("/access/invocations", forged)
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    let mut missing = req("/access/invocations", payload());
    missing.headers_mut().remove("idempotency-key");
    assert_eq!(
        call(runtime_router(s), missing).await.0,
        StatusCode::BAD_REQUEST
    );
}
#[tokio::test(start_paused = true)]
async fn authoritative_timeout_is_bounded() {
    let mut s = state(Arc::new(Authority {
        pending: true,
        ..Default::default()
    }));
    Arc::get_mut(&mut s)
        .unwrap()
        .config
        .limits
        .request_timeout_ms = 50;
    let (code, body) = call(runtime_router(s), req("/access/invocations", payload())).await;
    assert_eq!(code, StatusCode::GATEWAY_TIMEOUT);
    assert_eq!(body["code"], "TIMEOUT");
}
#[tokio::test]
async fn mcp_uses_original_arguments_and_reports_approval_without_dispatch() {
    let a = Arc::new(Authority {
        ask: true,
        ..Default::default()
    });
    let (code, body) = call(
        runtime_router(state(a.clone())),
        mcp("tools/call", Some("files.read")),
    )
    .await;
    assert_eq!(code, StatusCode::OK);
    assert_eq!(
        body["result"]["structuredContent"]["state"],
        "PENDING_APPROVAL"
    );
    assert!(a.submission.lock().unwrap().is_none());
    let a = Arc::new(Authority::default());
    let (code, body) = call(
        runtime_router(state(a.clone())),
        mcp("tools/call", Some("files.read")),
    )
    .await;
    assert_eq!(code, StatusCode::OK);
    assert_eq!(body["result"]["structuredContent"]["ok"], true);
    assert_eq!(
        serde_json::to_value(
            &a.submission
                .lock()
                .unwrap()
                .as_ref()
                .unwrap()
                .request
                .arguments
        )
        .unwrap(),
        payload()["arguments"]
    );
}
#[tokio::test]
async fn protocol_and_identity_headers_do_not_override_authenticated_context() {
    let s = state(Arc::new(Authority::default()));
    let mut r = mcp("tools/call", Some("files.read"));
    r.headers_mut()
        .insert("mcp-name", "different".parse().unwrap());
    assert_eq!(
        call(runtime_router(s.clone()), r).await.0,
        StatusCode::BAD_REQUEST
    );
    let mut r = req("/access/invocations", payload());
    r.headers_mut()
        .insert("x-user-id", "administrator".parse().unwrap());
    let (_, body) = call(runtime_router(s.clone()), r).await;
    assert_eq!(
        body["invocation"]["evaluation"]["context"]["userId"],
        "user-demo"
    );
    let (code, body) = call(runtime_router(s), mcp("tools/list", None)).await;
    assert_eq!(code, StatusCode::OK);
    assert_eq!(body["result"]["ttlMs"], 0);
}
#[tokio::test]
async fn transport_limits_and_readiness_are_separate() {
    let mut s = state(Arc::new(Authority::default()));
    Arc::get_mut(&mut s).unwrap().config.limits.max_body_bytes = 256;
    let mut body = payload();
    body["arguments"]["path"] = json!("x".repeat(1024));
    assert_eq!(
        call(runtime_router(s.clone()), req("/access/invocations", body))
            .await
            .0,
        StatusCode::PAYLOAD_TOO_LARGE
    );
    let mut r = req("/access/invocations", payload());
    r.headers_mut()
        .insert("origin", "https://untrusted.invalid".parse().unwrap());
    assert_eq!(
        call(runtime_router(s.clone()), r).await.0,
        StatusCode::FORBIDDEN
    );
    assert!(!state(Arc::new(Authority {
        failure: true,
        ..Default::default()
    }))
    .ready());
    let response = management_router(s)
        .oneshot(
            Request::builder()
                .uri("/v1/health/live")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}
#[tokio::test]
async fn tcp_service_and_graceful_shutdown_use_the_current_wire() {
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::{TcpListener, TcpStream},
        sync::watch,
    };
    let s = state(Arc::new(Authority::default()));
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let (stop, signal) = watch::channel(false);
    let task = tokio::spawn(olo_toolgate_gateway::server::serve(
        listener,
        runtime_router(s.clone()),
        s.config.limits.clone(),
        signal,
    ));
    let body = payload().to_string();
    let mut connection = TcpStream::connect(address).await.unwrap();
    connection.write_all(format!("POST /access/invocations HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {TOKEN}\r\nIdempotency-Key: logical-request\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await.unwrap();
    let mut bytes = vec![];
    connection.read_to_end(&mut bytes).await.unwrap();
    let text = String::from_utf8(bytes).unwrap();
    assert!(text.starts_with("HTTP/1.1 200"));
    assert!(text.contains("RESERVED"));
    stop.send(true).unwrap();
    task.await.unwrap().unwrap();
    assert!(TcpStream::connect(address).await.is_err());
}
fn legacy(version: Option<&str>, body: Value) -> Request<Body> {
    let mut r = req("/mcp", body);
    r.headers_mut().insert(
        "accept",
        "application/json, text/event-stream".parse().unwrap(),
    );
    if let Some(version) = version {
        r.headers_mut()
            .insert("mcp-protocol-version", version.parse().unwrap());
    }
    r
}
#[tokio::test]
async fn modern_core_drops_ping_and_discovers_every_supported_version() {
    let s = state(Arc::new(Authority::default()));
    let (code, body) = call(runtime_router(s.clone()), mcp("ping", None)).await;
    assert_eq!(code, StatusCode::NOT_FOUND);
    assert_eq!(body["error"]["code"], -32601);
    let (code, body) = call(runtime_router(s.clone()), mcp("server/discover", None)).await;
    assert_eq!(code, StatusCode::OK);
    assert_eq!(body["result"]["supportedVersions"], json!(["2026-07-28"]));
    assert_eq!(body["result"]["resultType"], "complete");
    // The _meta version decides: a legacy header on a modern request is a mismatch.
    let mut r = mcp("tools/list", None);
    r.headers_mut()
        .insert("mcp-protocol-version", "2025-06-18".parse().unwrap());
    let (code, body) = call(runtime_router(s), r).await;
    assert_eq!(code, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"]["code"], -32020);
}
#[tokio::test]
async fn session_era_clients_are_refused_with_the_supported_version() {
    let s = state(Arc::new(Authority::default()));
    for version in ["2025-11-25", "2025-06-18"] {
        let (code, body) = call(
            runtime_router(s.clone()),
            legacy(
                None,
                json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":version,"capabilities":{},"clientInfo":{"name":"old","version":"1"}}}),
            ),
        )
        .await;
        assert_eq!(code, StatusCode::BAD_REQUEST);
        assert_eq!(body["error"]["code"], -32022);
        assert_eq!(body["error"]["data"]["supported"], json!(["2026-07-28"]));
        assert_eq!(body["error"]["data"]["requested"], version);
        let (code, body) = call(
            runtime_router(s.clone()),
            legacy(
                Some(version),
                json!({"jsonrpc":"2.0","id":2,"method":"tools/list"}),
            ),
        )
        .await;
        assert_eq!(code, StatusCode::BAD_REQUEST);
        assert_eq!(body["error"]["code"], -32022);
    }
    let response = runtime_router(s)
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/mcp")
                .header("authorization", format!("Bearer {TOKEN}"))
                .header("accept", "text/event-stream")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::METHOD_NOT_ALLOWED);
}

/// A call Control placed on a configured Tool Host goes there with a permit, never the relay.
#[tokio::test]
async fn tool_host_calls_dispatch_with_a_permit_and_return_the_host_result() {
    let seen = Arc::new(Mutex::new(None::<Value>));
    let host = axum::Router::new().route(
        "/v1/dispatch",
        axum::routing::post({
            let seen = seen.clone();
            move |body: axum::body::Bytes| async move {
                *seen.lock().unwrap() = Some(serde_json::from_slice::<Value>(&body).unwrap());
                String::new()
            }
        }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let a = Arc::new(Authority {
        tool_host: true,
        ..Authority::default()
    });
    // The host answers for the invocation the Authority created, which the test learns after the call starts.
    let invocations = a.clone();
    let host = host.layer(axum::middleware::map_response(
        move |_: axum::response::Response| {
            let invocations = invocations.clone();
            async move {
                let id = invocations.invocation.lock().unwrap().as_ref().unwrap().id.clone();
                let body = format!(
                    "{}\n{}\n",
                    json!({"kind":"ADMITTED","invocationId":id,"fencingToken":"f"}),
                    json!({"invocationId":id,"fencingToken":"f","outcome":"COMPLETED","result":{"kind":"RESULT","resultType":"complete","content":[{"type":"text","text":"hi"}],"structuredContent":{"greeting":"hi"},"isError":false}})
                );
                axum::response::Response::new(axum::body::Body::from(body))
            }
        },
    ));
    tokio::spawn(async move { axum::serve(listener, host).await.unwrap() });
    let mut config = config();
    config.tool_hosts = serde_json::from_value(
        json!([{"hostId":"placed-device","url":format!("http://127.0.0.1:{port}")}]),
    )
    .unwrap();
    let (code, body) = call(
        runtime_router(state_with(a.clone(), config)),
        mcp("tools/call", Some("files.read")),
    )
    .await;
    assert_eq!(code, StatusCode::OK, "{body}");
    assert_eq!(body["result"]["structuredContent"]["greeting"], "hi");
    assert_eq!(body["result"]["content"][0]["text"], "hi");
    let permit = a.permit.lock().unwrap().clone().unwrap();
    assert_eq!(permit.audience, "placed-device");
    assert_eq!(
        serde_json::to_value(&permit.arguments).unwrap(),
        payload()["arguments"]
    );
    let dispatched = seen.lock().unwrap().clone().unwrap();
    assert_eq!(dispatched["permit"]["jws"], "e30.e30.c2ln");
    assert!(a.submission.lock().unwrap().is_none());
}

/// A Tool Host the Gateway has no route to fetches its calls from Control: the Gateway hands the
/// permitted dispatch to Control and waits for the host's result there.
#[tokio::test]
async fn a_tool_host_without_a_route_gets_its_calls_through_control() {
    let a = Arc::new(Authority {
        tool_host: true,
        ..Authority::default()
    });
    let (code, body) = call(
        runtime_router(state(a.clone())),
        mcp("tools/call", Some("files.read")),
    )
    .await;
    assert_eq!(code, StatusCode::OK, "{body}");
    assert_eq!(body["result"]["structuredContent"]["greeting"], "hi");
    let relayed = a.relayed.lock().unwrap().clone().unwrap();
    assert_eq!(relayed.permit.jws, "e30.e30.c2ln");
    assert_eq!(
        serde_json::to_value(&relayed.arguments).unwrap(),
        payload()["arguments"]
    );
    assert_eq!(a.polls.load(Ordering::SeqCst), 3);
    assert!(a.submission.lock().unwrap().is_none());
}
#[tokio::test]
async fn credential_reload_validates_the_whole_file_and_keeps_the_live_set_on_error() {
    const NEXT: &str = "enterprise-next-credential-000000000000000000000000000000000";
    let s = state(Arc::new(Authority::default()));
    let directory = std::env::temp_dir().join(format!(
        "toolgate-credentials-{}-{}",
        std::process::id(),
        unix_ms().unwrap()
    ));
    std::fs::create_dir_all(&directory).unwrap();
    let path = directory.join("credentials.json");
    let mut next = credential();
    next.token_sha256 = digest(NEXT.as_bytes());
    next.context.credential_sha256 = Some(next.token_sha256.clone());
    next.context.workload_binding_id = Some("workload-next".into());
    let digest_endpoint = |s: Arc<AppState>| async move {
        let response = management_router(s)
            .oneshot(
                Request::builder()
                    .uri("/v1/credentials")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = to_bytes(response.into_body(), 4096).await.unwrap();
        serde_json::from_slice::<Value>(&bytes).unwrap()
    };
    let before = digest_endpoint(s.clone()).await;
    assert_eq!(before["count"], 1);
    // A valid file replaces the whole set: the old token stops, the new one works at once.
    let good = serde_json::to_vec(&vec![next.clone()]).unwrap();
    std::fs::write(&path, &good).unwrap();
    assert_eq!(s.reload_credentials(&path), Ok(1));
    assert_eq!(
        digest_endpoint(s.clone()).await,
        json!({"sha256": digest(&good), "count": 1})
    );
    assert!(s.auth.authenticate(TOKEN, "r".into(), 2).is_none());
    assert!(s.auth.authenticate(NEXT, "r".into(), 2).is_some());
    // Malformed, oversized, duplicate, unknown-field and invalid files are rejected; the set stays.
    let mut human = next.clone();
    human.context.mode = RequestMode::Human;
    let mut unknown = serde_json::to_value(vec![next.clone()]).unwrap();
    unknown[0]["rawToken"] = json!("never");
    for bad in [
        b"[{\"tokenSha256\":".to_vec(),
        b"[]".to_vec(),
        serde_json::to_vec(&vec![next.clone(), next.clone()]).unwrap(),
        serde_json::to_vec(&vec![credential(), human]).unwrap(),
        serde_json::to_vec(&unknown).unwrap(),
        vec![b' '; 1_048_577],
    ] {
        std::fs::write(&path, &bad).unwrap();
        assert!(s.reload_credentials(&path).is_err());
        assert_eq!(
            digest_endpoint(s.clone()).await,
            json!({"sha256": digest(&good), "count": 1})
        );
        assert!(s.auth.authenticate(NEXT, "r".into(), 2).is_some());
        assert!(s.auth.authenticate(TOKEN, "r".into(), 2).is_none());
    }
    std::fs::remove_file(&path).unwrap();
    assert!(s.reload_credentials(&path).is_err());
    assert!(s.auth.authenticate(NEXT, "r".into(), 2).is_some());
    std::fs::remove_dir_all(&directory).unwrap();
    // The startup path digests the exact file bytes too.
    let contracts = Contracts::new().unwrap();
    let loaded = Authenticator::from_bytes(&good, &contracts, 1).unwrap();
    assert_eq!(loaded.loaded(), (digest(&good), 1));
}
