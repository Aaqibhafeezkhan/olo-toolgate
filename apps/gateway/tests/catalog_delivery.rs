// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
use axum::{
    extract::State,
    http::{HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::post,
    Json, Router,
};
use olo_toolgate_contracts::v2::RequestContext;
use olo_toolgate_contracts::ErrorCode;
use olo_toolgate_gateway::relay::{HttpRelay, RelayConfig, RelayPort};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};

const TENANT: &str = "10000000-0000-4000-8000-000000000001";
const TOKEN: &str = "catalog-delivery-test-token";
#[derive(Clone)]
struct Fixture {
    epochs: Vec<&'static str>,
    body: Value,
    fail_ack: bool,
    received: Arc<Mutex<Vec<Value>>>,
}
fn authenticated(headers: &HeaderMap) {
    assert_eq!(headers["authorization"], format!("Bearer {TOKEN}"));
    assert_eq!(headers["Toolgate-Tenant-ID"], TENANT);
}
async fn catalog(State(f): State<Fixture>, headers: HeaderMap) -> Response {
    authenticated(&headers);
    let mut response = Json(f.body).into_response();
    for epoch in f.epochs {
        response.headers_mut().append(
            "Toolgate-Authorization-Epoch",
            HeaderValue::from_static(epoch),
        );
    }
    response
}
async fn ack(State(f): State<Fixture>, headers: HeaderMap, Json(body): Json<Value>) -> Response {
    authenticated(&headers);
    f.received.lock().unwrap().push(body.clone());
    if f.fail_ack {
        StatusCode::SERVICE_UNAVAILABLE.into_response()
    } else {
        Json(body).into_response()
    }
}
async fn exchange(
    epochs: Vec<&'static str>,
    body: Value,
    fail_ack: bool,
) -> (
    Result<olo_toolgate_contracts::LocalToolCatalog, ErrorCode>,
    Vec<Value>,
) {
    let received = Arc::new(Mutex::new(vec![]));
    let fixture = Fixture {
        epochs,
        body,
        fail_ack,
        received: received.clone(),
    };
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let app = Router::new()
        .route("/api/control/v1/mcp/catalog", post(catalog))
        .route("/api/control/v1/gateways/kills/acknowledgements", post(ack))
        .with_state(fixture);
    let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let root = std::env::temp_dir().join(format!(
        "toolgate-catalog-{}-{}",
        std::process::id(),
        address.port()
    ));
    std::fs::create_dir(&root).unwrap();
    let token_path = root.join("token");
    std::fs::write(&token_path, TOKEN).unwrap();
    let relay = HttpRelay::new(RelayConfig {
        ca_certificate_path: None,
        tenant_id: TENANT.into(),
        url: format!("http://{address}/"),
        token_path,
        development_loopback_http: true,
    })
    .unwrap();
    let context: RequestContext = serde_json::from_value(json!({
        "requestId":"catalog", "tenantId":TENANT, "mode":"DELEGATED",
        "userId":"user", "agentId":"agent", "workloadBindingId":"binding",
        "chain":[], "sessionEpoch":1, "credentialEpoch":1,
        "credentialKind":"STATIC_TOKEN", "bindingId":"binding",
        "credentialSha256":"a".repeat(64)
    }))
    .unwrap();
    let result = relay.catalog(&context).await;
    let requests = received.lock().unwrap().clone();
    server.abort();
    std::fs::remove_file(root.join("token")).unwrap();
    std::fs::remove_dir(root).unwrap();
    (result, requests)
}
#[tokio::test]
async fn current_catalog_is_validated_before_own_authenticated_ack() {
    for fail_ack in [false, true] {
        let (catalog, requests) = exchange(vec!["42"], json!({"tools":[]}), fail_ack).await;
        assert!(catalog.unwrap().tools.is_empty());
        assert_eq!(
            requests,
            vec![json!({"authorizationEpoch":42,"deliveryPath":"POLL"})]
        );
    }
}
#[tokio::test]
async fn missing_ambiguous_noncanonical_epochs_and_invalid_catalogs_are_never_acknowledged() {
    for epochs in [
        vec![],
        vec!["42", "42"],
        vec!["42", "43"],
        vec!["042"],
        vec!["+42"],
        vec!["-1"],
        vec!["9007199254740992"],
        vec!["not-an-epoch"],
    ] {
        let (result, requests) = exchange(epochs, json!({"tools":[]}), false).await;
        assert_eq!(result.unwrap_err(), ErrorCode::Validation);
        assert!(requests.is_empty());
    }
    let (result, requests) =
        exchange(vec!["42"], json!({"tools":[],"unreviewed":true}), false).await;
    assert_eq!(result.unwrap_err(), ErrorCode::Validation);
    assert!(requests.is_empty());
}
