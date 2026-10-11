// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Stateless MCP ingress with an optional device-scoped client relay.
//!
//! The core speaks MCP 2026-07-28. Requests without a `_meta` protocol version
//! that use a session-era version go to the legacy adapter in `legacy.rs`
//! (ADR 018), which translates them onto the same core functions.
use crate::http::{error, parse, single_header, status, AppState};
use crate::unix_ms;
use axum::{
    extract::{Request, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use base64::Engine;
use olo_toolgate_contracts::{
    AuthorizationRequest, EnterpriseInvocationState, ErrorCode, RemoteToolState,
    RemoteToolSubmission, RequestContext,
};
use serde_json::{json, Value};
use std::sync::{atomic::Ordering, Arc};

/// The stateless core version.
pub(crate) const MODERN_VERSION: &str = "2026-07-28";
/// Session-era versions served by the legacy adapter for the MCP deprecation window.
pub(crate) const LEGACY_VERSIONS: [&str; 2] = ["2025-11-25", "2025-06-18"];

pub(crate) fn supported_versions() -> Value {
    json!([MODERN_VERSION, LEGACY_VERSIONS[0], LEGACY_VERSIONS[1]])
}

pub(crate) fn unsupported_version(id: Value, requested: Option<&str>) -> Response {
    rpc_error(
        StatusCode::BAD_REQUEST,
        id,
        -32022,
        "Unsupported protocol version",
        json!({"supported":supported_versions(),"requested":requested}),
    )
}

/// Both transports require a client that accepts JSON and SSE responses.
pub(crate) fn accepts_json_and_sse(headers: &HeaderMap) -> bool {
    let accept = single_header(headers, "accept").unwrap_or("");
    accept.split(',').any(|v| v.trim() == "application/json")
        && accept.split(',').any(|v| v.trim() == "text/event-stream")
}

/// A request is legacy when it carries no `_meta` version and either opens a
/// session or names a version other than the core's in the header. A request
/// with a `_meta` version is always judged by the core, whatever its header says.
fn is_legacy(value: &Value, headers: &HeaderMap) -> bool {
    value["params"]["_meta"]["io.modelcontextprotocol/protocolVersion"].is_null()
        && (value["method"] == "initialize"
            || single_header(headers, "mcp-protocol-version").is_some_and(|v| v != MODERN_VERSION))
}
pub(crate) fn rpc_error(
    http_status: StatusCode,
    id: Value,
    code: i64,
    message: &'static str,
    data: Value,
) -> Response {
    let body =
        json!({"jsonrpc":"2.0", "id":id, "error":{"code":code,"message":message,"data":data}});
    (http_status, Json(body)).into_response()
}

fn decoded_name(headers: &HeaderMap) -> Option<String> {
    let value = single_header(headers, "mcp-name")?;
    if let Some(encoded) = value
        .strip_prefix("=?base64?")
        .and_then(|v| v.strip_suffix("?="))
    {
        String::from_utf8(
            base64::engine::general_purpose::STANDARD
                .decode(encoded)
                .ok()?,
        )
        .ok()
    } else {
        Some(value.into())
    }
}

pub(crate) async fn ingress(State(s): State<Arc<AppState>>, request: Request) -> Response {
    let credential_deadline = request
        .extensions()
        .get::<crate::http::CredentialDeadline>()
        .map(|d| d.0)
        .unwrap_or(0);
    let (value, context, correlation, headers) = match parse(&s, request).await {
        Ok(v) => v,
        Err(r) => return r,
    };
    let id = value.get("id").cloned().unwrap_or(Value::Null);
    let Some(object) = value.as_object() else {
        return rpc_error(
            StatusCode::BAD_REQUEST,
            Value::Null,
            -32600,
            "Invalid request",
            Value::Null,
        );
    };
    if is_legacy(&value, &headers) {
        return crate::legacy::handle(
            &s,
            &value,
            context,
            &correlation.request_id,
            &headers,
            credential_deadline,
        )
        .await;
    }
    if value["jsonrpc"] == "2.0" && value["method"].is_string() && !object.contains_key("id") {
        // Notifications have no JSON-RPC reply. The core has no client
        // notifications, so it rejects them with an ordinary HTTP error.
        return error(ErrorCode::Unsupported, &correlation.request_id);
    }
    if value["jsonrpc"] != "2.0"
        || !(id.is_string() || id.is_i64() || id.is_u64())
        || id.as_str().is_some_and(|v| v.len() > 128)
        || object
            .keys()
            .any(|k| !["jsonrpc", "id", "method", "params"].contains(&k.as_str()))
        || !value["params"].is_object()
    {
        return rpc_error(
            StatusCode::BAD_REQUEST,
            Value::Null,
            -32600,
            "Invalid request",
            Value::Null,
        );
    }
    let version = single_header(&headers, "mcp-protocol-version");
    let method = value["method"].as_str().unwrap_or("");
    let params = &value["params"];
    if version.is_none()
        || version != params["_meta"]["io.modelcontextprotocol/protocolVersion"].as_str()
        || single_header(&headers, "mcp-method") != Some(method)
        || (method == "tools/call" && decoded_name(&headers).as_deref() != params["name"].as_str())
    {
        return rpc_error(
            StatusCode::BAD_REQUEST,
            id,
            -32020,
            "Header mismatch",
            Value::Null,
        );
    }
    if version != Some(MODERN_VERSION) {
        return unsupported_version(id, version);
    }
    let meta = &params["_meta"];
    if !meta["io.modelcontextprotocol/clientCapabilities"].is_object() {
        return rpc_error(
            StatusCode::BAD_REQUEST,
            id,
            -32602,
            "Client capabilities required",
            Value::Null,
        );
    }
    if !accepts_json_and_sse(&headers) {
        return rpc_error(
            StatusCode::NOT_ACCEPTABLE,
            id,
            -32600,
            "JSON and SSE acceptance required",
            Value::Null,
        );
    }
    crate::diagnostics::packet(
        "RECEIVE",
        "/mcp",
        &correlation.request_id,
        None,
        json!({"protocolVersion":MODERN_VERSION,"method":if matches!(method,"server/discover"|"tools/list"|"tools/call") {method} else {"unsupported"},"rpcIdDigest":crate::digest(id.to_string().as_bytes()),"arguments":"[redacted]"}),
    );
    let result = match method {
        "server/discover" => Ok(
            json!({"supportedVersions":supported_versions(),"capabilities":json!({"tools":{}}),"ttlMs":0,"cacheScope":"private"}),
        ),
        "tools/list" => tools_list(&s, &context, &id).await,
        "tools/call" => {
            tools_call(
                &s,
                context,
                &correlation.request_id,
                credential_deadline,
                &id,
                params,
            )
            .await
        }
        // ping is not part of 2026-07-28; only the legacy adapter answers it.
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

/// The caller's enabled tools, looked up again on every request.
pub(crate) async fn tools_list(
    s: &AppState,
    context: &RequestContext,
    id: &Value,
) -> Result<Value, Response> {
    match s.gateway.authority.catalog(context).await {
        Ok(catalog) => Ok(
            json!({"tools":catalog.tools.iter().filter(|tool|tool.enabled).map(|tool|json!({"name":tool.tool_id,"description":tool.description,"inputSchema":tool.input_schema})).collect::<Vec<_>>(),"ttlMs":0,"cacheScope":"private"}),
        ),
        Err(code) => Err(rpc_error(
            status(&code),
            id.clone(),
            -32000,
            "Local tool discovery failed",
            json!({"code":code}),
        )),
    }
}

/// Authorizes and relays one tool call. Nothing is cached between requests.
pub(crate) async fn tools_call(
    s: &AppState,
    context: RequestContext,
    request_id: &str,
    credential_deadline: u64,
    id: &Value,
    params: &Value,
) -> Result<Value, Response> {
    let name = params["name"].as_str().unwrap_or("");
    let relay = &s.gateway.authority;
    let catalog = relay
        .catalog(&context)
        .await
        .map_err(|code| error(code, request_id))?;
    let matches: Vec<_> = catalog
        .tools
        .into_iter()
        .filter(|t| t.enabled && t.tool_id == name)
        .collect();
    if matches.len() != 1 {
        return Err(rpc_error(
            StatusCode::FORBIDDEN,
            id.clone(),
            -32602,
            "Tool unavailable for this actor and device",
            Value::Null,
        ));
    }
    let action = matches[0].action.clone();
    let invalid = || {
        rpc_error(
            StatusCode::BAD_REQUEST,
            id.clone(),
            -32602,
            "Invalid parameters",
            Value::Null,
        )
    };
    if params.as_object().is_none_or(|p| {
        p.keys()
            .any(|k| !["name", "arguments", "_meta"].contains(&k.as_str()))
    }) {
        return Err(invalid());
    }
    let request = serde_json::from_value::<AuthorizationRequest>(
        json!({"toolId":name,"action":action,"arguments":params["arguments"]}),
    )
    .map_err(|_| invalid())?;
    if !s.gateway.contracts.valid(
        "AuthorizationRequest",
        &serde_json::to_value(&request).unwrap_or_default(),
    ) {
        return Err(error(ErrorCode::Validation, request_id));
    }
    let Some(now) = unix_ms() else {
        return Err(error(ErrorCode::DependencyUnavailable, request_id));
    };
    let mut context = context;
    context.request_id = crate::http::invocation_id(&context, &id.to_string());
    let outcome = s
        .gateway
        .authorize(request.clone(), context.clone())
        .await
        .map_err(|code| {
            rpc_error(
                status(&code),
                id.clone(),
                -32000,
                "Authorization failed",
                json!({"code":code}),
            )
        })?;
    if outcome.invocation.state == EnterpriseInvocationState::PendingApproval {
        return Ok(
            json!({"content":[{"type":"text","text":"Approval required"}],"structuredContent":{"invocationId":outcome.invocation.id,"state":"PENDING_APPROVAL"},"isError":true}),
        );
    }
    if !matches!(
        outcome.invocation.state,
        EnterpriseInvocationState::Queued
            | EnterpriseInvocationState::Reserved
            | EnterpriseInvocationState::Executing
            | EnterpriseInvocationState::Succeeded
    ) {
        return Err(error(ErrorCode::Forbidden, request_id));
    }
    s.metrics.allow.fetch_add(1, Ordering::Relaxed);
    let expires = credential_deadline
        .min(now.saturating_add(s.config.limits.request_timeout_ms.saturating_sub(1000)));
    if expires <= now.saturating_add(2000) {
        return Err(error(ErrorCode::Timeout, request_id));
    }
    let submission = RemoteToolSubmission {
        context: context.clone(),
        invocation_id: outcome.invocation.id,
        request,
        expires_at_unix_ms: expires,
    };
    let mut response = relay.submit(&submission).await.map_err(|code| {
        rpc_error(
            status(&code),
            id.clone(),
            -32000,
            "Local tool request rejected",
            json!({"code":code}),
        )
    })?;
    loop {
        if response.record.request_id != context.request_id
            || response.record.device_id != context.device_id
            || response.record.agent_id != context.agent_id
            || response.record.tool_id != submission.request.tool_id
        {
            return Err(error(ErrorCode::Validation, request_id));
        }
        if unix_ms().is_none_or(|time| time >= expires) {
            return Err(error(ErrorCode::Timeout, request_id));
        }
        match response.record.state {
            RemoteToolState::Done => {
                let Some(result) = response.result else {
                    return Err(error(ErrorCode::Validation, request_id));
                };
                if result.request_id != context.request_id || result.error.is_some() {
                    return Err(error(ErrorCode::Validation, request_id));
                }
                let Some(output) = result.output else {
                    return Err(error(ErrorCode::Validation, request_id));
                };
                return Ok(
                    json!({"content":[{"type":"text","text":serde_json::to_string(&output).unwrap_or_default()}],"structuredContent":output,"isError":false}),
                );
            }
            RemoteToolState::Failed | RemoteToolState::Expired => {
                return Err(rpc_error(
                    StatusCode::OK,
                    id.clone(),
                    -32000,
                    "Local tool execution failed",
                    json!({"code":response.record.error.unwrap_or(ErrorCode::Timeout),"requestId":context.request_id}),
                ))
            }
            _ => {}
        }
        tokio::time::sleep(std::time::Duration::from_millis(250)).await;
        response = relay.response(&context).await.map_err(|code| {
            rpc_error(
                status(&code),
                id.clone(),
                -32000,
                "Local tool response unavailable",
                json!({"code":code}),
            )
        })?;
    }
}

fn success(id: Value, mut result: Value) -> Response {
    result["resultType"] = json!("complete");
    result["_meta"] = json!({"io.modelcontextprotocol/serverInfo":{"name":"olo-toolgate-gateway","version":env!("CARGO_PKG_VERSION")}});
    Json(json!({"jsonrpc":"2.0","id":id,"result":result})).into_response()
}
