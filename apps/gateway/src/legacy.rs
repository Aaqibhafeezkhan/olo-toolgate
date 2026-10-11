// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Legacy MCP adapter for session-era `2025-11-25` and `2025-06-18` clients (ADR 018).
//!
//! Sessions are a transport artifact only. The guard has already authenticated
//! this request, nothing is stored, and an `Mcp-Session-Id` never carries
//! identity, catalog scope or a decision. Every call reaches the same core
//! functions as a 2026-07-28 request. The GET SSE stream and
//! `notifications/tools/list_changed` are not offered yet, so `initialize`
//! advertises `listChanged: false` and GET returns 405, which the legacy
//! transport allows.
use crate::http::{single_header, AppState};
use crate::mcp::{
    accepts_json_and_sse, rpc_error, tools_call, tools_list, unsupported_version, LEGACY_VERSIONS,
};
use axum::{
    http::{HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use olo_toolgate_contracts::RequestContext;
use ring::rand::{SecureRandom, SystemRandom};
use serde_json::{json, Value};

pub(crate) async fn handle(
    s: &AppState,
    value: &Value,
    context: RequestContext,
    request_id: &str,
    headers: &HeaderMap,
    credential_deadline: u64,
) -> Response {
    let invalid_request = || {
        rpc_error(
            StatusCode::BAD_REQUEST,
            Value::Null,
            -32600,
            "Invalid request",
            Value::Null,
        )
    };
    let Some(object) = value.as_object() else {
        return invalid_request();
    };
    let notification = !object.contains_key("id");
    let id = value.get("id").cloned().unwrap_or(Value::Null);
    let Some(method) = value["method"].as_str() else {
        return invalid_request();
    };
    if value["jsonrpc"] != "2.0"
        || (!notification
            && (!(id.is_string() || id.is_i64() || id.is_u64())
                || id.as_str().is_some_and(|v| v.len() > 128)))
        || object
            .keys()
            .any(|k| !["jsonrpc", "id", "method", "params"].contains(&k.as_str()))
        || (object.contains_key("params") && !value["params"].is_object())
        || (headers.contains_key("mcp-session-id")
            && single_header(headers, "mcp-session-id").is_none_or(|v| !valid_session_id(v)))
    {
        return invalid_request();
    }
    if !accepts_json_and_sse(headers) {
        return rpc_error(
            StatusCode::NOT_ACCEPTABLE,
            id,
            -32600,
            "JSON and SSE acceptance required",
            Value::Null,
        );
    }
    let params = &value["params"];
    if method == "initialize" {
        if notification {
            return invalid_request();
        }
        let requested = params["protocolVersion"].as_str();
        if requested.is_none()
            || !params["capabilities"].is_object()
            || !params["clientInfo"].is_object()
        {
            return rpc_error(
                StatusCode::BAD_REQUEST,
                id,
                -32602,
                "Invalid parameters",
                Value::Null,
            );
        }
        let Some(version) = requested.filter(|v| LEGACY_VERSIONS.contains(v)) else {
            return unsupported_version(id, requested);
        };
        packet(request_id, version, method, &id);
        let Some(session) = session_id() else {
            return crate::http::error(
                olo_toolgate_contracts::ErrorCode::DependencyUnavailable,
                request_id,
            );
        };
        let mut response = success(
            id,
            json!({"protocolVersion":version,"capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"olo-toolgate-gateway","version":env!("CARGO_PKG_VERSION")}}),
        );
        response.headers_mut().insert(
            "mcp-session-id",
            HeaderValue::from_str(&session).expect("hex session id"),
        );
        return response;
    }
    let version = single_header(headers, "mcp-protocol-version");
    let Some(version) = version.filter(|v| LEGACY_VERSIONS.contains(v)) else {
        return unsupported_version(id, version);
    };
    if notification {
        // notifications/initialized, notifications/cancelled and the rest change
        // nothing here: the core keeps no session and cancels by deadline.
        return StatusCode::ACCEPTED.into_response();
    }
    packet(request_id, version, method, &id);
    let result = match method {
        "ping" => Ok(json!({})),
        "tools/list" => tools_list(s, &context, &id).await.map(|mut result| {
            if let Some(result) = result.as_object_mut() {
                result.remove("ttlMs");
                result.remove("cacheScope");
            }
            result
        }),
        "tools/call" => tools_call(s, context, request_id, credential_deadline, &id, params).await,
        _ => Err(rpc_error(
            StatusCode::NOT_FOUND,
            id.clone(),
            -32601,
            "Method not found",
            Value::Null,
        )),
    };
    match result {
        Ok(result) => success(id, result),
        Err(response) => response,
    }
}

/// Visible ASCII only, as the legacy transport requires, and bounded.
fn valid_session_id(value: &str) -> bool {
    !value.is_empty() && value.len() <= 128 && value.bytes().all(|b| (0x21..=0x7e).contains(&b))
}

/// An opaque, unguessable label. It is never stored or looked up.
fn session_id() -> Option<String> {
    let mut bytes = [0u8; 16];
    SystemRandom::new().fill(&mut bytes).ok()?;
    Some(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

fn packet(request_id: &str, version: &str, method: &str, id: &Value) {
    crate::diagnostics::packet(
        "RECEIVE",
        "/mcp",
        request_id,
        None,
        json!({"protocolVersion":version,"method":if matches!(method,"initialize"|"ping"|"tools/list"|"tools/call") {method} else {"unsupported"},"rpcIdDigest":crate::digest(id.to_string().as_bytes()),"arguments":"[redacted]"}),
    );
}

/// Legacy results carry no `resultType` and no per-result server info.
fn success(id: Value, result: Value) -> Response {
    Json(json!({"jsonrpc":"2.0","id":id,"result":result})).into_response()
}
