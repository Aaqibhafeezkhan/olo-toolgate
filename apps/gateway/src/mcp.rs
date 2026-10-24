// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Stateless MCP ingress with an optional device-scoped client relay.
//!
//! Only MCP 2026-07-28 is served. Session-era clients (`initialize`, or an
//! older `MCP-Protocol-Version` without a `_meta` version) get -32022 listing
//! the supported version.
use crate::http::{AppState, error, parse, single_header, status};
use crate::unix_ms;
use axum::{
    Json,
    extract::{Request, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use base64::Engine;
use olo_toolgate_contracts::v2::{
    Dispatch, InvocationOutcome, PermitReservationRequest, RequestContext, ResultProvenance,
};
use olo_toolgate_contracts::{
    AuthorizationRequest, EnterpriseInvocationState, ErrorCode, RemoteToolState,
    RemoteToolSubmission,
};
use serde_json::{Value, json};
use std::sync::{Arc, atomic::Ordering};

/// The stateless core version.
pub(crate) const MODERN_VERSION: &str = "2026-07-28";
fn supported_versions() -> Value {
    json!([MODERN_VERSION])
}

fn unsupported_version(id: Value, requested: Option<&str>) -> Response {
    rpc_error(
        StatusCode::BAD_REQUEST,
        id,
        -32022,
        "Unsupported protocol version",
        json!({"supported":supported_versions(),"requested":requested}),
    )
}

/// The client must accept JSON and SSE responses.
fn accepts_json_and_sse(headers: &HeaderMap) -> bool {
    let accept = single_header(headers, "accept").unwrap_or("");
    accept.split(',').any(|v| v.trim() == "application/json")
        && accept.split(',').any(|v| v.trim() == "text/event-stream")
}

/// A session-era request carries no `_meta` version and either opens a session
/// or names another version in the header. A request with a `_meta` version is
/// always judged by the 2026-07-28 rules, whatever its header says.
fn session_era_version<'a>(value: &'a Value, headers: &'a HeaderMap) -> Option<&'a str> {
    if !value["params"]["_meta"]["io.modelcontextprotocol/protocolVersion"].is_null() {
        return None;
    }
    if value["method"] == "initialize" {
        return Some(
            value["params"]["protocolVersion"]
                .as_str()
                .unwrap_or("unknown"),
        );
    }
    single_header(headers, "mcp-protocol-version").filter(|v| *v != MODERN_VERSION)
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
    if let Some(requested) = session_era_version(&value, &headers) {
        return unsupported_version(id, Some(requested));
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
        // Read-only authenticated binding evidence. Carries no credential and grants no tool access.
        "olo/identity" => Ok(json!({"tenantId":context.tenant_id,"mode":context.mode,
            "userId":context.user_id,"agentId":context.agent_id,"chain":context.chain,"ttlMs":0,"cacheScope":"private"})),
        "olo/invocations/cancel" => {
            if let Some(operation) = params["operationId"]
                .as_str()
                .filter(|v| !v.is_empty() && v.len() <= 128)
            {
                let mut cancellation = context;
                cancellation.request_id =
                    crate::http::invocation_id(&cancellation, &json!(operation).to_string());
                s.gateway
                    .authority
                    .cancel(&cancellation)
                    .await
                    .map(|v| json!(v))
                    .map_err(|code| error(code, &correlation.request_id))
            } else {
                Err(rpc_error(
                    StatusCode::BAD_REQUEST,
                    id.clone(),
                    -32602,
                    "Invalid operation id",
                    Value::Null,
                ))
            }
        }
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
        // ping is not part of 2026-07-28.
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
async fn tools_list(s: &AppState, context: &RequestContext, id: &Value) -> Result<Value, Response> {
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
async fn tools_call(
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
    // OLO M5.4: a replayed key whose invocation ended reports how it ended. OUTCOME_UNKNOWN in particular is never run
    // again here: the caller reconciles it instead of resending.
    if matches!(
        outcome.invocation.state,
        EnterpriseInvocationState::Failed
            | EnterpriseInvocationState::Cancelled
            | EnterpriseInvocationState::Expired
            | EnterpriseInvocationState::Partial
            | EnterpriseInvocationState::OutcomeUnknown
    ) {
        let state = serde_json::to_value(&outcome.invocation.state).unwrap_or(Value::Null);
        return Ok(
            json!({"content":[{"type":"text","text":format!("Invocation ended: {}", state.as_str().unwrap_or("UNKNOWN"))}],
            "structuredContent":{"invocationId":outcome.invocation.id,"state":state,
                "reconciliation":outcome.invocation.reconciliation},"isError":true}),
        );
    }
    if !matches!(
        outcome.invocation.state,
        EnterpriseInvocationState::Queued
            | EnterpriseInvocationState::Reserved
            | EnterpriseInvocationState::Dispatched
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
    let device = outcome.invocation.evaluation.context.device_id.clone();
    if outcome.invocation.state == EnterpriseInvocationState::Queued {
        // A call Control placed on a Tool Host gets a permit for that host. The host runs it
        // whether the Gateway reaches it directly (a configured route) or the host fetches its
        // calls from Control, so moving a tool between hosts never changes this path.
        let reservation = PermitReservationRequest {
            invocation_id: outcome.invocation.id.clone(),
            audience: device.clone(),
            attempt: 1,
            arguments: Some(request.arguments.clone()),
        };
        match relay.permit(&reservation).await {
            // Placed on a device that is not a Tool Host: relay it as a local tool call.
            Err(ErrorCode::Conflict) => {}
            Err(code) => {
                return Err(rpc_error(
                    status(&code),
                    id.clone(),
                    -32000,
                    "Tool permit refused",
                    json!({"code":code}),
                ));
            }
            Ok(permit) => {
                let dispatch = Dispatch {
                    permit,
                    arguments: request.arguments.clone(),
                };
                let invocation_id = &outcome.invocation.id;
                let dispatched = match s.tool_hosts.url(&device) {
                    Some(url) => {
                        s.tool_hosts
                            .dispatch(url, &dispatch, invocation_id, expires)
                            .await
                    }
                    None => relayed(relay.as_ref(), &dispatch, invocation_id, expires).await,
                };
                return host_result(dispatched, invocation_id, id);
            }
        }
    }
    if outcome.invocation.state != EnterpriseInvocationState::Queued {
        // A repeated call: Control keeps the result of a call it placed on a Tool Host, whether the Gateway reached
        // the host directly or the host fetched it, so the repeat returns that result and never runs it again.
        // Control knows no dispatch for a call placed on a client device, which the relay below answers.
        let invocation_id = &outcome.invocation.id;
        match relay.dispatch_status(invocation_id).await {
            Ok(state) => {
                let dispatched = follow(relay.as_ref(), state, invocation_id, expires).await;
                return host_result(dispatched, invocation_id, id);
            }
            Err(ErrorCode::NotFound) => {}
            Err(code) => return host_result(Err(code), invocation_id, id),
        }
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
                ));
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

/// Pull mode: hands the dispatch to Control, where the Tool Host fetches it, and waits for the
/// host's admission and result until `deadline` (Unix ms).
async fn relayed(
    relay: &dyn crate::relay::RelayPort,
    dispatch: &Dispatch,
    invocation_id: &str,
    deadline: u64,
) -> Result<crate::toolhost::Dispatched, ErrorCode> {
    let state = relay.relay_dispatch(dispatch).await?;
    follow(relay, state, invocation_id, deadline).await
}

/// Waits for a dispatch Control holds to finish, be refused or expire, until `deadline` (Unix ms).
async fn follow(
    relay: &dyn crate::relay::RelayPort,
    mut state: olo_toolgate_contracts::v2::ToolHostDispatchStatus,
    invocation_id: &str,
    deadline: u64,
) -> Result<crate::toolhost::Dispatched, ErrorCode> {
    use crate::toolhost::Dispatched;
    use olo_toolgate_contracts::v2::{AdmissionKind, ToolHostDispatchState};
    loop {
        if state.invocation_id != invocation_id {
            return Err(ErrorCode::Validation);
        }
        match state.state {
            ToolHostDispatchState::Finished => {
                let result = state.result.ok_or(ErrorCode::Validation)?;
                if result.invocation_id != invocation_id {
                    return Err(ErrorCode::Validation);
                }
                return Ok(Dispatched::Finished(Box::new(result)));
            }
            ToolHostDispatchState::Refused => {
                let admission = state.admission.ok_or(ErrorCode::Validation)?;
                if admission.kind != AdmissionKind::Refused {
                    return Err(ErrorCode::Validation);
                }
                return Ok(Dispatched::Refused(
                    admission.reason.ok_or(ErrorCode::Validation)?,
                ));
            }
            ToolHostDispatchState::Expired => return Err(ErrorCode::Timeout),
            _ => {}
        }
        if unix_ms().is_none_or(|now| now >= deadline) {
            return Err(ErrorCode::Timeout);
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        state = relay.dispatch_status(invocation_id).await?;
    }
}

/// Turns a Tool Host's answer, direct or relayed through Control, into an MCP tool result.
#[allow(clippy::result_large_err)] // An axum Response, built once per call.
fn host_result(
    dispatched: Result<crate::toolhost::Dispatched, ErrorCode>,
    invocation_id: &str,
    id: &Value,
) -> Result<Value, Response> {
    use crate::toolhost::Dispatched;
    let failed = |message: &'static str, data: Value| {
        rpc_error(StatusCode::OK, id.clone(), -32000, message, data)
    };
    match dispatched {
        Err(code) => Err(rpc_error(
            status(&code),
            id.clone(),
            -32000,
            "Tool Host unavailable",
            json!({"code":code,"requestId":invocation_id}),
        )),
        Ok(Dispatched::Refused(reason)) => Err(failed(
            "Tool Host refused the call",
            json!({"code":ErrorCode::DependencyUnavailable,"reason":reason,"requestId":invocation_id}),
        )),
        Ok(Dispatched::Finished(message)) => match (message.outcome, message.result) {
            (InvocationOutcome::Completed, Some(frame)) => {
                let mut result = json!({"content":frame.content,"isError":frame.is_error});
                if let Some(structured) = frame.structured_content {
                    result["structuredContent"] = json!(structured);
                }
                if let Some(error) = frame.error {
                    result["_meta"] = json!({"io.ololabs.toolgate/error":error});
                }
                if frame.provenance == Some(ResultProvenance::UntrustedUpstream) {
                    // Output relayed from an upstream MCP server (MCP_PROXY), also on replays.
                    result["_meta"][UNTRUSTED_UPSTREAM] = json!(true);
                }
                Ok(result)
            }
            (outcome, frame) => Err(failed(
                "Tool execution failed",
                json!({"code":ErrorCode::DependencyUnavailable,"outcome":outcome,
                    "error":frame.and_then(|f| f.error),"requestId":invocation_id}),
            )),
        },
    }
}

/// The `_meta` member marking a result as relayed from an untrusted upstream MCP server.
pub const UNTRUSTED_UPSTREAM: &str = "io.toolgate/untrusted-upstream";

fn success(id: Value, result: Value) -> Response {
    Json(json!({"jsonrpc":"2.0","id":id,"result":completed(result)})).into_response()
}

/// Marks a result complete and adds the Gateway's `serverInfo` to its `_meta`, keeping the members already there.
fn completed(mut result: Value) -> Value {
    result["resultType"] = json!("complete");
    if !result["_meta"].is_object() {
        result["_meta"] = json!({});
    }
    result["_meta"]["io.modelcontextprotocol/serverInfo"] =
        json!({"name":"olo-toolgate-gateway","version":env!("CARGO_PKG_VERSION")});
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use olo_toolgate_contracts::v2::{ResultFrame, ResultMessage, ResultType};

    fn finished(provenance: Option<ResultProvenance>) -> Value {
        let frame = ResultFrame {
            kind: "RESULT".into(),
            result_type: ResultType::Complete,
            content: vec![],
            structured_content: None,
            is_error: false,
            error: None,
            provenance,
        };
        let message = ResultMessage {
            invocation_id: "inv-1".into(),
            fencing_token: None,
            outcome: InvocationOutcome::Completed,
            result: Some(frame),
        };
        let result = host_result(
            Ok(crate::toolhost::Dispatched::Finished(Box::new(message))),
            "inv-1",
            &json!(1),
        )
        .unwrap();
        completed(result)
    }

    #[test]
    fn untrusted_upstream_results_carry_the_marker_and_the_server_info() {
        let result = finished(Some(ResultProvenance::UntrustedUpstream));
        assert_eq!(result["_meta"][UNTRUSTED_UPSTREAM], json!(true));
        assert_eq!(
            result["_meta"]["io.modelcontextprotocol/serverInfo"]["name"],
            json!("olo-toolgate-gateway")
        );
    }

    #[test]
    fn tool_results_have_no_marker() {
        for provenance in [None, Some(ResultProvenance::Tool)] {
            let result = finished(provenance);
            assert!(result["_meta"].get(UNTRUSTED_UPSTREAM).is_none());
            assert!(result["_meta"]["io.modelcontextprotocol/serverInfo"].is_object());
        }
    }

    #[test]
    fn completed_keeps_existing_meta_members() {
        let result =
            completed(json!({"content":[],"_meta":{"io.ololabs.toolgate/error":{"code":"X"}}}));
        assert_eq!(
            result["_meta"]["io.ololabs.toolgate/error"]["code"],
            json!("X")
        );
        assert!(result["_meta"]["io.modelcontextprotocol/serverInfo"].is_object());
    }
}
