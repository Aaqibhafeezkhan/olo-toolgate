// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Actual broker helper processes and private pipes; OS namespace qualification is a separate gate.
use axum::{extract::State, routing::post, Json, Router};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    },
    time::Duration,
};
use tool_host::{
    broker::{answer, Invocation},
    control::Control,
};
async fn secret(
    State(calls): State<Arc<AtomicUsize>>,
    headers: axum::http::HeaderMap,
    Json(body): Json<Value>,
) -> Json<Value> {
    assert_eq!(headers["Toolgate-Fencing-Token"], "fence");
    assert_eq!(body["invocationId"], "operation");
    assert_eq!(body["name"], "declared");
    calls.fetch_add(1, Ordering::SeqCst);
    Json(json!({"value":"declared-test-secret"}))
}
#[tokio::test]
async fn real_helper_redeems_only_owned_handles_and_never_loads_a_supervisor_configuration() {
    let calls = Arc::new(AtomicUsize::new(0));
    let app = Router::new()
        .route("/api/control/v1/access/secrets/deliver", post(secret))
        .with_state(calls.clone());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let config=serde_json::from_value(json!({"tenantId":"10000000-0000-4000-8000-000000000001","url":format!("http://{address}"),"developmentLoopbackHttp":true})).unwrap();
    let control = Arc::new(Control::new(&config).unwrap());
    let corpus: Value = serde_json::from_str(include_str!(
        "../../../tests/fixtures/contracts/v2/valid.json"
    ))
    .unwrap();
    let mut declared: olo_toolgate_contracts::v2::SecretDeclaration =
        serde_json::from_value(corpus["SecretDeclaration"].clone()).unwrap();
    declared.name = "declared".into();
    declared.r#type = olo_toolgate_contracts::v2::ValueType::String;
    let mut invocation = Invocation::new(
        "operation".into(),
        "fence".into(),
        tool_host::now_ms() + 60_000,
        "profile".into(),
        format!("sha256:{}", "a".repeat(64)),
        Vec::new(),
        Vec::new(),
        Vec::new(),
        Vec::new(),
        vec![declared],
        HashMap::from([("handle".into(), "declared".into())]),
        false,
        control,
    );
    invocation.invocation_permit = Some(olo_toolgate_contracts::v2::SignedInvocationPermit {
        jws: "fixture-admission".into(),
    });
    let mut child = tokio::process::Command::new(env!("CARGO_BIN_EXE_olo-toolgate-tool-host"))
        .arg("--invocation-broker")
        .env_clear()
        .env(
            "TOOLGATE_TOOL_HOST_CONFIG",
            "/must-not-read-operator-identity",
        )
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
    let denied=answer(&invocation,&json!({"kind":"BROKER_REQUEST","requestId":"denied","operation":"SECRET_REDEEM","request":{"handle":"foreign","name":"declared"}})).await;
    assert!(denied["error"].is_object());
    assert_eq!(calls.load(Ordering::SeqCst), 0);
    let allowed=answer(&invocation,&json!({"kind":"BROKER_REQUEST","requestId":"allowed","operation":"SECRET_REDEEM","request":{"handle":"handle"}})).await;
    assert_eq!(allowed["result"]["value"], "declared-test-secret");
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    let escaped=answer(&invocation,&json!({"kind":"BROKER_REQUEST","requestId":"escape","operation":"GET_IDENTITY","request":{}})).await;
    assert!(escaped["error"].is_object());
    assert!(*connection.failed().borrow());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    assert!(invocation.broker_policy().is_err());
    connection.finish().await;
    let output = tokio::time::timeout(Duration::from_secs(3), child.wait_with_output())
        .await
        .unwrap()
        .unwrap();
    let diagnostic = String::from_utf8_lossy(&output.stderr);
    assert!(!diagnostic.contains("declared-test-secret"));
    assert!(!diagnostic.contains("BEGIN PRIVATE KEY"));
    server.abort();
}
#[tokio::test]
async fn a_required_broker_cannot_fall_back_to_the_in_process_path() {
    let config=serde_json::from_value(json!({"tenantId":"10000000-0000-4000-8000-000000000001","url":"http://127.0.0.1:1","developmentLoopbackHttp":true})).unwrap();
    let invocation = Invocation::new(
        "operation".into(),
        "fence".into(),
        tool_host::now_ms() + 60_000,
        "profile".into(),
        format!("sha256:{}", "a".repeat(64)),
        Vec::new(),
        Vec::new(),
        Vec::new(),
        Vec::new(),
        Vec::new(),
        HashMap::new(),
        false,
        Arc::new(Control::new(&config).unwrap()),
    );
    invocation.require_isolated_broker().unwrap();
    let result=answer(&invocation,&json!({"kind":"BROKER_REQUEST","requestId":"call","operation":"ROUTE_CALL","request":{"route":"anything"}})).await;
    assert_eq!(
        result["error"]["message"],
        "the isolated broker is unavailable"
    );
    assert!(invocation.closed.load(Ordering::SeqCst));
}
