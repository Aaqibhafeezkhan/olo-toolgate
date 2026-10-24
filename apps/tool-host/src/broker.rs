// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The Tool Host side of the broker in tool protocol v2, the one protocol every executor speaks (ADR 027): the tool
//! writes a `BROKER_REQUEST` frame on stdout and the host answers with one `BROKER_RESPONSE` line on stdin. The tool
//! selects among the routes its permit grants; the broker builds the upstream request from the approved service
//! profile, injects credentials last, and never returns them.
//!
//! `ROUTE_CALL` takes the route call in the recorded-exchange shape of the SDKs' test mode (spec/authoring/testing.md):
//! `route`, `method`, `destination`, `variables`, `query`, `headers`, `contentType` and the body as `body` (JSON),
//! `bodyText` or `bodyBase64`. Its result is `{status, headers, bodyBase64}`, `{failure}` when the call failed in flight,
//! or `{refused}` when the broker would not send it. `SECRET_REDEEM` redeems a secret handle. Device-file operations
//! are refused: a Tool Host has no device files.

use crate::control::Control;
use crate::egress::{self, AddressPolicy, AddressRefused};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use olo_toolgate_contracts::v2::PrivateRangeApproval;
use olo_toolgate_contracts::v2::{
    AuthType, DestinationScheme, EgressMode, HttpAuthDeclaration, HttpMethod, RouteDeclaration,
    SecretDeclaration, ServiceDestination, ValueType,
};
use serde_json::{Map, Value, json};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Request headers a tool may never set, whatever the route allows (ÃÆ’Æ’â€Å¡ÃÆ’â€Å¡§11.8.5).
const FORBIDDEN: [&str; 11] = [
    "authorization",
    "toolgate-service-permit",
    "proxy-authorization",
    "cookie",
    "host",
    "forwarded",
    "content-length",
    "transfer-encoding",
    "connection",
    "te",
    "upgrade",
];
/// Response headers removed before the reply reaches the tool.
const STRIPPED: [&str; 5] = [
    "set-cookie",
    "toolgate-service-permit",
    "set-cookie2",
    "www-authenticate",
    "proxy-authenticate",
];

/// Everything the broker enforces for one invocation, fixed at admission.
pub struct Invocation {
    pub invocation_id: String,
    pub fencing_token: String,
    pub deadline_unix_ms: u64,
    /// The permit's routes as the service profile approves them.
    pub routes: Vec<RouteDeclaration>,
    pub destinations: Vec<ServiceDestination>,
    pub private_ranges: Vec<PrivateRangeApproval>,
    pub auths: Vec<HttpAuthDeclaration>,
    pub secrets: Vec<SecretDeclaration>,
    /// Handle ÃÆ’Æ’¢ÃÆ’¢ââ€Å¡¬ ÃÆ’¢ââ€Å¡¬ââ€Å¾¢ secret name, for secrets delivered into the sandbox.
    pub secret_handles: HashMap<String, String>,
    pub development_loopback_http: bool,
    pub control: Arc<Control>,
    /// Set when the invocation ends; the broker refuses everything afterwards.
    pub closed: AtomicBool,
    /// A single-use downstream write capability was dispatched; a lost/error reply needs reconciliation.
    pub service_effect_started: AtomicBool,
    clients: Mutex<HashMap<String, reqwest::Client>>,
}

impl Invocation {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        invocation_id: String,
        fencing_token: String,
        deadline_unix_ms: u64,
        routes: Vec<RouteDeclaration>,
        destinations: Vec<ServiceDestination>,
        private_ranges: Vec<PrivateRangeApproval>,
        auths: Vec<HttpAuthDeclaration>,
        secrets: Vec<SecretDeclaration>,
        secret_handles: HashMap<String, String>,
        development_loopback_http: bool,
        control: Arc<Control>,
    ) -> Invocation {
        Invocation {
            invocation_id,
            fencing_token,
            deadline_unix_ms,
            routes,
            destinations,
            private_ranges,
            auths,
            secrets,
            secret_handles,
            development_loopback_http,
            control,
            closed: AtomicBool::new(false),
            service_effect_started: AtomicBool::new(false),
            clients: Mutex::new(HashMap::new()),
        }
    }
}

fn refused(reason: impl Into<String>) -> Value {
    json!({ "refused": reason.into() })
}

/// The `BROKER_RESPONSE` for one `BROKER_REQUEST` frame of this invocation.
pub async fn answer(invocation: &Invocation, frame: &Value) -> Value {
    let id = frame
        .get("requestId")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    let empty = json!({});
    let request = frame.get("request").unwrap_or(&empty);
    let operation = frame
        .get("operation")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let outcome = if invocation.closed.load(Ordering::SeqCst)
        || crate::now_ms() >= invocation.deadline_unix_ms
    {
        Err("the invocation has ended".to_string())
    } else {
        match operation {
            "ROUTE_CALL" => Ok(call(invocation, request).await),
            "SECRET_REDEEM" => secret(invocation, request).await,
            o if o.starts_with("FILES_") => {
                Err("device files are not available on a Tool Host".into())
            }
            _ => Err("unknown broker operation".into()),
        }
    };
    match outcome {
        Ok(result) => json!({"kind": "BROKER_RESPONSE", "requestId": id, "result": result}),
        Err(message) => json!({"kind": "BROKER_RESPONSE", "requestId": id,
            "error": {"code": "BROKER_REFUSED", "message": message, "retryable": false}}),
    }
}

async fn secret(invocation: &Invocation, body: &Value) -> Result<Value, String> {
    let handle = body
        .get("handle")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let Some(name) = invocation.secret_handles.get(handle) else {
        audit_secret(invocation, "", "refused");
        return Err("the secret handle is not valid for this invocation".into());
    };
    match invocation
        .control
        .secret(&invocation.invocation_id, &invocation.fencing_token, name)
        .await
    {
        Ok(value) => {
            audit_secret(invocation, name, "delivered");
            let typed = invocation
                .secrets
                .iter()
                .find(|s| &s.name == name)
                .map(|s| s.r#type.clone());
            Ok(json!({ "value": typed_secret(value, typed) }))
        }
        Err(e) => {
            audit_secret(invocation, name, "refused");
            Err(format!("Control did not deliver secret {name}: {e:?}"))
        }
    }
}

/// A secret of a structured type is stored as JSON text; others are strings.
fn typed_secret(value: String, declared: Option<ValueType>) -> Value {
    match declared {
        Some(ValueType::Object | ValueType::Array) => {
            serde_json::from_str(&value).unwrap_or(Value::String(value))
        }
        _ => Value::String(value),
    }
}

fn audit_secret(invocation: &Invocation, name: &str, decision: &str) {
    tracing::info!(target: "toolgate::audit", invocation_id = %invocation.invocation_id, secret = name, decision, "broker secret");
}

async fn call(invocation: &Invocation, body: &Value) -> Value {
    let started = Instant::now();
    let route_id = body
        .get("route")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    let (reply, status, bytes, upstream_id) = match send(invocation, body).await {
        Ok(Sent {
            reply,
            status,
            bytes,
            upstream_id,
        }) => (reply, Some(status), bytes, upstream_id),
        Err(reply) => (reply, None, 0, None),
    };
    let decision = if reply.get("refused").is_some() {
        "refused"
    } else if reply.get("failure").is_some() {
        "failed"
    } else {
        "sent"
    };
    let route = invocation.routes.iter().find(|r| r.id == route_id);
    tracing::info!(
        target: "toolgate::audit",
        invocation_id = %invocation.invocation_id,
        route = %route_id,
        destination = route.map(|r| r.destination.as_str()).unwrap_or(""),
        method = route.map(|r| method_name(&r.method)).unwrap_or(""),
        status = status.unwrap_or(0),
        bytes,
        duration_ms = started.elapsed().as_millis() as u64,
        upstream_request_id = upstream_id.as_deref().unwrap_or(""),
        reason = reply.get("refused").and_then(serde_json::Value::as_str).unwrap_or(""),
        decision,
        "broker call"
    );
    reply
}

struct Sent {
    reply: Value,
    status: u16,
    bytes: usize,
    upstream_id: Option<String>,
}

pub fn method_name(method: &HttpMethod) -> &'static str {
    match method {
        HttpMethod::Get => "GET",
        HttpMethod::Head => "HEAD",
        HttpMethod::Post => "POST",
        HttpMethod::Put => "PUT",
        HttpMethod::Patch => "PATCH",
        HttpMethod::Delete => "DELETE",
    }
}

/// Builds, checks and sends one route call. `Err` carries a `refused` or `failure` reply.
async fn send(invocation: &Invocation, body: &Value) -> Result<Sent, Value> {
    let route_id = body
        .get("route")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let route = invocation
        .routes
        .iter()
        .find(|r| r.id == route_id)
        .ok_or_else(|| {
            refused(format!(
                "route {route_id} is not granted to this invocation"
            ))
        })?;
    let method = method_name(&route.method);
    if body.get("method").and_then(Value::as_str) != Some(method) {
        return Err(refused(format!("route {route_id} is {method} only")));
    }
    if let Some(destination) = body.get("destination").and_then(Value::as_str) {
        if destination != route.destination {
            return Err(refused(format!(
                "route {route_id} goes to destination {}, not {destination}",
                route.destination
            )));
        }
    }
    let destination = invocation
        .destinations
        .iter()
        .find(|d| d.id == route.destination)
        .ok_or_else(|| {
            refused(format!(
                "destination {} is not in the approved service profile",
                route.destination
            ))
        })?;
    if destination.scheme != DestinationScheme::Https || destination.mode != EgressMode::Brokered {
        return Err(refused(format!(
            "destination {} is not a brokered HTTPS destination",
            destination.id
        )));
    }

    let path =
        expand(&route.path, body.get("variables").unwrap_or(&Value::Null)).map_err(refused)?;
    let query = query(route, body.get("query").unwrap_or(&Value::Null)).map_err(refused)?;
    let auth = match &route.auth {
        Some(id) => Some(
            invocation
                .auths
                .iter()
                .find(|a| &a.id == id)
                .ok_or_else(|| refused(format!("auth {id} of route {route_id} is not declared")))?,
        ),
        None => None,
    };
    let auth_header = auth.map(|a| {
        a.header
            .clone()
            .unwrap_or_else(|| "Authorization".into())
            .to_ascii_lowercase()
    });
    let mut outgoing = reqwest::header::HeaderMap::new();
    for (name, value) in body
        .get("headers")
        .and_then(Value::as_object)
        .into_iter()
        .flatten()
    {
        let lower = name.to_ascii_lowercase();
        if FORBIDDEN.contains(&lower.as_str())
            || lower.starts_with("x-forwarded-")
            || Some(&lower) == auth_header.as_ref()
        {
            return Err(refused(format!("header {name} may not be set by a tool")));
        }
        if !route.headers.iter().any(|h| h.eq_ignore_ascii_case(name)) {
            return Err(refused(format!(
                "route {route_id} does not allow header {name}"
            )));
        }
        let value = value
            .as_str()
            .ok_or_else(|| refused(format!("header {name} is not a string")))?;
        let name = reqwest::header::HeaderName::from_bytes(lower.as_bytes())
            .map_err(|_| refused(format!("header {name} is not valid")))?;
        let value = reqwest::header::HeaderValue::from_str(value)
            .map_err(|_| refused(format!("header {name} has an invalid value")))?;
        outgoing.insert(name, value);
    }

    let request_body = request_body(body).map_err(refused)?;
    if let Some(bytes) = &request_body {
        if bytes.len() as u64 > route.max_request_bytes {
            return Err(refused(format!(
                "the request body is larger than route {route_id}'s maxRequestBytes"
            )));
        }
        let content_type = body.get("contentType").and_then(Value::as_str).unwrap_or(
            if body.get("body").is_some() {
                "application/json"
            } else {
                ""
            },
        );
        let essence = content_type.split(';').next().unwrap_or_default().trim();
        if !route
            .request_content_types
            .iter()
            .any(|t| t.eq_ignore_ascii_case(essence))
        {
            return Err(refused(format!(
                "route {route_id} does not accept content type {content_type:?}"
            )));
        }
        outgoing.insert(
            reqwest::header::CONTENT_TYPE,
            reqwest::header::HeaderValue::from_str(content_type)
                .map_err(|_| refused("the content type is not valid"))?,
        );
    }

    let plain = invocation.development_loopback_http && is_loopback_host(&destination.host);
    let policy = AddressPolicy {
        host: destination.host.clone(),
        port: u16::try_from(destination.port)
            .map_err(|_| refused("the destination port is not valid"))?,
        private_ranges: invocation.private_ranges.clone(),
        allow_loopback: invocation.development_loopback_http,
    };
    if let Ok(ip) = destination.host.trim_matches(['[', ']']).parse() {
        policy.check(ip).map_err(|e| refused(e.0))?;
    }
    let service_audience = match destination.id.as_str() {
        "olo-memory" => Some(olo_toolgate_contracts::ServiceAudience::OloMemory),
        "olo-rag" => Some(olo_toolgate_contracts::ServiceAudience::OloRag),
        _ => None,
    };
    if service_audience.is_some() && (plain || auth.is_some()) {
        return Err(refused(
            "service permits require mTLS HTTPS without package credentials",
        ));
    }
    let client = if service_audience.is_some() {
        egress::client_with_identity(
            destination,
            policy.clone(),
            Some(invocation.control.service_identity().map_err(refused)?),
        )
        .map_err(refused)?
    } else {
        let mut clients = invocation.clients.lock().unwrap();
        match clients.get(&destination.id) {
            Some(c) => c.clone(),
            None => {
                let c = egress::client(destination, policy.clone()).map_err(refused)?;
                clients.insert(destination.id.clone(), c.clone());
                c
            }
        }
    };

    let service_write =
        service_audience.is_some() && !matches!(path.as_str(), "/v1/memory.read" | "/v1/rag.query");
    if let Some(audience) = service_audience {
        let permit = invocation
            .control
            .service_permit(
                &invocation.invocation_id,
                &invocation.fencing_token,
                audience,
            )
            .await
            .map_err(|_| refused("downstream service authorization refused"))?;
        let mut value = reqwest::header::HeaderValue::from_str(&permit.jws)
            .map_err(|_| refused("invalid service permit"))?;
        value.set_sensitive(true);
        outgoing.insert("toolgate-service-permit", value);
    }
    // Credentials go in last, after every check, and never into the reply.
    if let Some(auth) = auth {
        let value = credential(invocation, auth).await.map_err(refused)?;
        let name =
            reqwest::header::HeaderName::from_bytes(auth_header.unwrap_or_default().as_bytes())
                .map_err(|_| refused("the auth header is not valid"))?;
        let mut value = reqwest::header::HeaderValue::from_str(&value)
            .map_err(|_| refused("the credential is not a valid header value"))?;
        value.set_sensitive(true);
        outgoing.insert(name, value);
    }

    let host = if destination.host.contains(':') && !destination.host.starts_with('[') {
        format!("[{}]", destination.host)
    } else {
        destination.host.clone()
    };
    let url = format!(
        "{}://{host}:{}{path}{query}",
        if plain { "http" } else { "https" },
        destination.port
    );
    let left = invocation
        .deadline_unix_ms
        .saturating_sub(crate::now_ms())
        .max(1);
    let timeout = Duration::from_millis(route.timeout_ms.clamp(1, left));
    let method = reqwest::Method::from_bytes(method.as_bytes())
        .map_err(|_| refused("the method is not valid"))?;
    let mut request = client
        .request(method, url)
        .headers(outgoing)
        .timeout(timeout);
    if let Some(bytes) = request_body {
        request = request.body(bytes);
    }
    if invocation.closed.load(Ordering::SeqCst) || crate::now_ms() >= invocation.deadline_unix_ms {
        return Err(refused("the invocation has ended"));
    }
    if service_write {
        invocation
            .service_effect_started
            .store(true, Ordering::SeqCst);
    }
    let response = request.send().await.map_err(failure)?;
    let status = response.status().as_u16();
    let mut reply_headers = Map::new();
    for (name, value) in response.headers() {
        if STRIPPED.contains(&name.as_str()) {
            continue;
        }
        let entry = reply_headers
            .entry(name.as_str().to_string())
            .or_insert_with(|| Value::Array(vec![]));
        if let (Value::Array(values), Ok(v)) = (entry, value.to_str()) {
            values.push(Value::String(v.to_string()));
        }
    }
    let upstream_id = ["x-request-id", "request-id"].iter().find_map(|h| {
        response
            .headers()
            .get(*h)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string)
    });
    // At most one byte past the route's cap, so the SDK reports the response as too large.
    let cap = usize::try_from(route.max_response_bytes)
        .unwrap_or(usize::MAX)
        .saturating_add(1);
    let mut bytes = Vec::new();
    let mut response = response;
    while let Some(chunk) = response.chunk().await.map_err(failure)? {
        let room = cap - bytes.len();
        bytes.extend_from_slice(&chunk[..chunk.len().min(room)]);
        if bytes.len() >= cap {
            break;
        }
    }
    Ok(Sent {
        bytes: bytes.len(),
        reply: json!({ "status": status, "headers": reply_headers, "bodyBase64": STANDARD.encode(&bytes) }),
        status,
        upstream_id,
    })
}

/// How a call that was attempted failed. A refused address is a refusal: nothing was sent.
fn failure(error: reqwest::Error) -> Value {
    let mut source: Option<&dyn std::error::Error> = Some(&error);
    while let Some(e) = source {
        if let Some(refusal) = e.downcast_ref::<AddressRefused>() {
            return refused(refusal.0.clone());
        }
        source = e.source();
    }
    let kind = if error.is_connect() {
        "CONNECTION_REFUSED"
    } else if error.is_timeout() {
        "TIMEOUT_AFTER_SEND"
    } else {
        "RESET_AFTER_SEND"
    };
    json!({ "failure": kind })
}

fn is_loopback_host(host: &str) -> bool {
    host.eq_ignore_ascii_case("localhost")
        || host
            .trim_matches(['[', ']'])
            .parse::<std::net::IpAddr>()
            .is_ok_and(|ip| ip.is_loopback())
}

/// The value the route's auth puts in its header, fetched from Control for this invocation.
async fn credential(invocation: &Invocation, auth: &HttpAuthDeclaration) -> Result<String, String> {
    let secret = |name: &Option<String>| -> Result<String, String> {
        name.clone()
            .ok_or_else(|| format!("auth {} names no secret", auth.id))
    };
    match auth.r#type {
        AuthType::ApiKey => {
            let name = secret(&auth.secret)?;
            fetch(invocation, &name).await
        }
        AuthType::Basic => {
            let name = secret(&auth.secret)?;
            let value = fetch(invocation, &name).await?;
            let pair = match serde_json::from_str::<Value>(&value) {
                Ok(Value::Object(o)) => match (
                    o.get("username").and_then(Value::as_str),
                    o.get("password").and_then(Value::as_str),
                ) {
                    (Some(u), Some(p)) => format!("{u}:{p}"),
                    _ => {
                        return Err(format!(
                            "auth {} needs a secret {{username, password}} or user:password",
                            auth.id
                        ));
                    }
                },
                _ if value.contains(':') => value,
                _ => {
                    return Err(format!(
                        "auth {} needs a secret {{username, password}} or user:password",
                        auth.id
                    ));
                }
            };
            Ok(format!("Basic {}", STANDARD.encode(pair)))
        }
        _ => Err(format!(
            "auth {} of type {:?} is not available under a Tool Host yet",
            auth.id, auth.r#type
        )),
    }
}

async fn fetch(invocation: &Invocation, name: &str) -> Result<String, String> {
    invocation
        .control
        .secret(&invocation.invocation_id, &invocation.fencing_token, name)
        .await
        .map_err(|e| format!("Control did not deliver secret {name}: {e:?}"))
}

fn request_body(body: &Value) -> Result<Option<Vec<u8>>, String> {
    if let Some(json) = body.get("body") {
        return serde_json::to_vec(json)
            .map(Some)
            .map_err(|e| e.to_string());
    }
    if let Some(text) = body.get("bodyText") {
        return text
            .as_str()
            .map(|t| Some(t.as_bytes().to_vec()))
            .ok_or_else(|| "bodyText is not a string".into());
    }
    if let Some(b64) = body.get("bodyBase64") {
        let text = b64.as_str().ok_or("bodyBase64 is not a string")?;
        return STANDARD
            .decode(text)
            .map(Some)
            .map_err(|_| "bodyBase64 is not base64".into());
    }
    Ok(None)
}

/// Percent-encodes everything outside RFC 3986 unreserved characters.
fn encode(value: &str) -> String {
    let mut out = String::new();
    for b in value.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Fills the route's path template. Every variable must be given, match its pattern, and not be
/// a path separator or traversal; the template's literal parts are the route's own.
pub fn expand(template: &str, variables: &Value) -> Result<String, String> {
    let given = match variables {
        Value::Null => Map::new(),
        Value::Object(map) => map.clone(),
        _ => return Err("variables is not an object".into()),
    };
    let mut out = String::new();
    let mut used = 0;
    let mut rest = template;
    while let Some(open) = rest.find('{') {
        out.push_str(&rest[..open]);
        let close = matching(rest, open).ok_or("the route template is malformed")?;
        let inner = &rest[open + 1..close];
        let (name, pattern) = match inner.split_once(':') {
            Some((n, p)) => (n, Some(p)),
            None => (inner, None),
        };
        let value = given
            .get(name)
            .ok_or_else(|| format!("path variable {name} is missing"))?;
        let value = match value {
            Value::String(s) => s.clone(),
            Value::Number(n) => n.to_string(),
            Value::Bool(b) => b.to_string(),
            _ => return Err(format!("path variable {name} is not a scalar")),
        };
        if value.is_empty()
            || value == "."
            || value == ".."
            || value.contains('/')
            || value.contains('\\')
        {
            return Err(format!(
                "path variable {name} is empty or a path separator or traversal"
            ));
        }
        let lower = value.to_ascii_lowercase();
        if lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e") {
            return Err(format!(
                "path variable {name} holds an encoded separator or dot"
            ));
        }
        if let Some(pattern) = pattern {
            let re = regex::Regex::new(&format!("^(?:{pattern})$"))
                .map_err(|_| format!("the pattern of {name} is not valid"))?;
            if !re.is_match(&value) {
                return Err(format!("path variable {name} does not match its pattern"));
            }
        }
        out.push_str(&encode(&value));
        used += 1;
        rest = &rest[close + 1..];
    }
    out.push_str(rest);
    if given.len() != used {
        return Err("a path variable is not in the route template".into());
    }
    Ok(out)
}

/// The `}` closing the `{` at `open`, counting braces inside a pattern such as `[0-9]{3}`.
fn matching(text: &str, open: usize) -> Option<usize> {
    let mut depth = 0;
    for (i, c) in text[open..].char_indices() {
        match c {
            '{' => depth += 1,
            '}' => {
                depth -= 1;
                if depth == 0 {
                    return Some(open + i);
                }
            }
            _ => {}
        }
    }
    None
}

fn query(route: &RouteDeclaration, query: &Value) -> Result<String, String> {
    let given = match query {
        Value::Null => return Ok(String::new()),
        Value::Object(map) => map,
        _ => return Err("query is not an object".into()),
    };
    let mut out = String::new();
    for (name, values) in given {
        if !route.query.iter().any(|q| q == name) {
            return Err(format!(
                "route {} does not allow query parameter {name}",
                route.id
            ));
        }
        let values = match values {
            Value::Array(items) => items.clone(),
            other => vec![other.clone()],
        };
        for value in values {
            let value = match value {
                Value::String(s) => s,
                Value::Number(n) => n.to_string(),
                Value::Bool(b) => b.to_string(),
                _ => return Err(format!("query parameter {name} is not a scalar")),
            };
            out.push(if out.is_empty() { '?' } else { '&' });
            out.push_str(&encode(name));
            out.push('=');
            out.push_str(&encode(&value));
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn templates_fill_checked_encoded_variables() {
        assert_eq!(
            expand("/items/{id}", &json!({"id": "a b"})).unwrap(),
            "/items/a%20b"
        );
        assert_eq!(
            expand("/items/{id:[0-9]+}/x", &json!({"id": 42})).unwrap(),
            "/items/42/x"
        );
        assert_eq!(
            expand("/c/{code:[A-Z]{3}}", &json!({"code": "EUR"})).unwrap(),
            "/c/EUR"
        );
        assert!(
            expand("/items/{id:[0-9]+}", &json!({"id": "4a"}))
                .unwrap_err()
                .contains("pattern")
        );
        for bad in ["..", ".", "a/b", "a%2Fb", "%2e%2e", ""] {
            assert!(
                expand("/items/{id}", &json!({ "id": bad })).is_err(),
                "{bad}"
            );
        }
        assert!(
            expand("/items/{id}", &json!({}))
                .unwrap_err()
                .contains("missing")
        );
        assert!(expand("/items", &json!({"id": "1"})).is_err());
    }
}
