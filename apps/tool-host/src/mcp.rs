// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The MCP proxy runtime (ADR 030). `definition_digest` is the definition digest of
//! tool-sdk/spec/mcp-proxy: `sha256:` over the RFC 8785 form, without NFC, of an upstream tool's six definition members
//! exactly as returned; Control's `McpDefinitions.digest` computes the same value. [`Session`] is one Streamable HTTP
//! session with an upstream MCP server over the streaming egress client ([`crate::stream`]): pinned TLS, the
//! destination address policy, bounded replies and one deadline for everything it does.

use crate::stream::{Body, Limits, StreamError, Upstream};
use olo_toolgate_contracts::v2::{McpAuthScheme, McpCheckFailure, McpUpstreamAuth};
use reqwest::header::{HeaderMap, HeaderName, HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE};
use serde_json::{json, Map, Value};
use tokio::time::Instant;

/// The members a definition digest covers; every other member is dropped.
pub const DEFINITION_MEMBERS: [&str; 6] = [
    "name",
    "title",
    "description",
    "inputSchema",
    "outputSchema",
    "annotations",
];
/// The MCP revision this client speaks.
pub const PROTOCOL_VERSION: &str = "2025-11-25";
/// Scan limits (plan §5), enforced before anything is aggregated.
pub const MAX_PAGES: usize = 16;
pub const MAX_TOOLS: usize = 256;
pub const MAX_SCAN_BYTES: usize = 4 << 20;

pub fn definition_digest(tool: &Value) -> Result<String, String> {
    let tool = tool
        .as_object()
        .ok_or("an MCP tool definition is a JSON object")?;
    let kept: Map<String, Value> = DEFINITION_MEMBERS
        .iter()
        .filter_map(|member| tool.get(*member).map(|v| (member.to_string(), v.clone())))
        .collect();
    crate::digest::jcs(&Value::Object(kept))
}

/// The definition members of an upstream tool, as Control stores and digests them; `None` when it has no name or no
/// object input schema.
pub fn project(tool: &Value) -> Option<Value> {
    let tool = tool.as_object()?;
    tool.get("name")?.as_str()?;
    tool.get("inputSchema")?.as_object()?;
    Some(Value::Object(
        DEFINITION_MEMBERS
            .iter()
            .filter_map(|member| tool.get(*member).map(|v| (member.to_string(), v.clone())))
            .collect(),
    ))
}

/// Why talking to the upstream failed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Failure {
    /// The check or connection failed in a way a scan reports.
    Check(McpCheckFailure, String),
    /// The upstream answered with a JSON-RPC error.
    Upstream(String),
}

impl Failure {
    /// The scan failure this maps to.
    pub fn check(&self) -> McpCheckFailure {
        match self {
            Failure::Check(failure, _) => failure.clone(),
            Failure::Upstream(_) => McpCheckFailure::UpstreamError,
        }
    }
}

impl std::fmt::Display for Failure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Failure::Check(failure, why) => write!(f, "{failure:?}: {why}"),
            Failure::Upstream(why) => write!(f, "upstream error: {why}"),
        }
    }
}

fn protocol(why: impl Into<String>) -> Failure {
    Failure::Check(McpCheckFailure::ProtocolError, why.into())
}

fn limit(why: impl Into<String>) -> Failure {
    Failure::Check(McpCheckFailure::ScanLimitExceeded, why.into())
}

/// Maps a streaming egress failure to the check failure a scan reports.
pub fn failure(error: StreamError) -> Failure {
    let why = error.to_string();
    let check = match &error {
        StreamError::Connect(e) if e.contains("SPKI pins") => McpCheckFailure::PinMismatch,
        StreamError::Connect(e)
            if e.contains("certificate")
                || e.contains("TLS")
                || e.contains("tls")
                || e.contains("handshake") =>
        {
            McpCheckFailure::TlsError
        }
        StreamError::Connect(_) => McpCheckFailure::UpstreamUnreachable,
        StreamError::Redirect(_) | StreamError::Status(_) => McpCheckFailure::UpstreamError,
        StreamError::ContentType(_) | StreamError::Malformed(_) => McpCheckFailure::ProtocolError,
        StreamError::TooLarge(_) => McpCheckFailure::ScanLimitExceeded,
        StreamError::Idle | StreamError::Deadline => McpCheckFailure::DeadlineExceeded,
    };
    Failure::Check(check, why)
}

/// What `initialize` told us.
#[derive(Debug, Clone)]
pub struct Initialized {
    pub server_info: Value,
    pub protocol_version: String,
}

/// One Streamable HTTP session with an upstream MCP server.
pub struct Session {
    upstream: Upstream,
    credential: (HeaderName, String),
    session: Option<String>,
    protocol: Option<String>,
    next_id: u64,
    limits: Limits,
    deadline: Instant,
    /// The upstream's leaf SPKI pin, `sha256/<base64>`, once a TLS reply arrived.
    pub observed_pin: Option<String>,
    /// The upstream sent `notifications/tools/list_changed` during this session.
    pub list_changed: bool,
}

impl Session {
    /// A session to `url`. With `pins`, TLS is verified against them before any request (and so any credential) is
    /// sent; without, against the system roots. Nothing is sent until the first request.
    pub fn open(
        url: &str,
        pins: &[String],
        auth: &McpUpstreamAuth,
        secret: String,
        allow_loopback: bool,
        limits: Limits,
        deadline: Instant,
    ) -> Result<Session, Failure> {
        let upstream = Upstream::new(url, pins, vec![], allow_loopback)
            .map_err(|e| Failure::Check(McpCheckFailure::UpstreamUnreachable, e))?;
        let credential = match auth.scheme {
            McpAuthScheme::Header => {
                let name = auth.header.as_deref().unwrap_or_default();
                let name = HeaderName::from_bytes(name.as_bytes())
                    .map_err(|_| protocol("the credential header name is invalid"))?;
                (name, secret)
            }
            _ => (AUTHORIZATION, format!("Bearer {secret}")),
        };
        Ok(Session {
            upstream,
            credential,
            session: None,
            protocol: None,
            next_id: 1,
            limits,
            deadline,
            observed_pin: None,
            list_changed: false,
        })
    }

    fn headers(&self) -> HeaderMap {
        let mut headers = HeaderMap::new();
        headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
        headers.insert(
            ACCEPT,
            HeaderValue::from_static("application/json, text/event-stream"),
        );
        if let Some(value) = self
            .session
            .as_deref()
            .and_then(|s| HeaderValue::from_str(s).ok())
        {
            headers.insert("mcp-session-id", value);
        }
        if let Some(value) = self
            .protocol
            .as_deref()
            .and_then(|p| HeaderValue::from_str(p).ok())
        {
            headers.insert("mcp-protocol-version", value);
        }
        headers
    }

    /// Sends one JSON-RPC request and returns its `result`. Notifications the upstream sends first are read and
    /// noted; the reply stream is closed as soon as the response arrived.
    async fn request(&mut self, method: &str, params: Value) -> Result<Value, Failure> {
        let id = self.next_id;
        self.next_id += 1;
        let body =
            serde_json::to_vec(&json!({"jsonrpc":"2.0","id":id,"method":method,"params":params}))
                .map_err(|e| protocol(e.to_string()))?;
        let reply = self
            .upstream
            .post(
                self.headers(),
                Some(self.credential.clone()),
                body,
                &self.limits,
                self.deadline,
            )
            .await
            .map_err(failure)?;
        if self.observed_pin.is_none() {
            self.observed_pin = reply.observed_pin.clone();
        }
        if self.session.is_none() {
            self.session = reply
                .headers
                .get("mcp-session-id")
                .and_then(|v| v.to_str().ok())
                .map(str::to_string);
        }
        let message = match reply.body {
            Body::Json(value) => value,
            Body::Events(mut events) => loop {
                let Some(event) = events.next().await.map_err(failure)? else {
                    return Err(protocol("the event stream ended without a response"));
                };
                let value: Value = serde_json::from_str(&event.data)
                    .map_err(|e| protocol(format!("an event is not JSON: {e}")))?;
                if value.get("id") == Some(&json!(id)) {
                    break value;
                }
                self.notice(&value);
            },
            Body::Empty => return Err(protocol("the upstream sent no response")),
        };
        if message.get("id") != Some(&json!(id)) {
            return Err(protocol("the response answers another request"));
        }
        if let Some(error) = message.get("error") {
            let text = error
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("upstream error");
            return Err(Failure::Upstream(text.chars().take(512).collect()));
        }
        message
            .get("result")
            .filter(|r| r.is_object())
            .cloned()
            .ok_or_else(|| protocol("the response has no result object"))
    }

    fn notice(&mut self, message: &Value) {
        if message.get("method").and_then(Value::as_str) == Some("notifications/tools/list_changed")
        {
            self.list_changed = true;
        }
    }

    /// Sends one notification; whatever the upstream answers is dropped.
    async fn notify(&mut self, method: &str) -> Result<(), Failure> {
        let body = serde_json::to_vec(&json!({"jsonrpc":"2.0","method":method}))
            .map_err(|e| protocol(e.to_string()))?;
        self.upstream
            .post(
                self.headers(),
                Some(self.credential.clone()),
                body,
                &self.limits,
                self.deadline,
            )
            .await
            .map_err(failure)?;
        Ok(())
    }

    /// `initialize` and `notifications/initialized`.
    pub async fn initialize(&mut self) -> Result<Initialized, Failure> {
        let result = self
            .request(
                "initialize",
                json!({"protocolVersion": PROTOCOL_VERSION, "capabilities": {},
                       "clientInfo": {"name": "olo-toolgate-tool-host", "version": env!("CARGO_PKG_VERSION")}}),
            )
            .await?;
        let protocol_version = result
            .get("protocolVersion")
            .and_then(Value::as_str)
            .filter(|v| !v.is_empty() && v.len() <= 32)
            .ok_or_else(|| protocol("initialize has no protocolVersion"))?
            .to_string();
        let server_info = result
            .get("serverInfo")
            .filter(|i| {
                i.get("name").is_some_and(Value::is_string)
                    && i.get("version").is_some_and(Value::is_string)
            })
            .cloned()
            .ok_or_else(|| protocol("initialize has no serverInfo"))?;
        self.protocol = Some(protocol_version.clone());
        self.notify("notifications/initialized").await?;
        Ok(Initialized {
            server_info,
            protocol_version,
        })
    }

    /// Every tool, paging through `tools/list` within the scan limits: at most 16 pages, 256 tools and 4 MiB.
    pub async fn list(&mut self) -> Result<Vec<Value>, Failure> {
        let mut tools = Vec::new();
        let mut cursor: Option<String> = None;
        let mut total = 0usize;
        for _ in 0..MAX_PAGES {
            let params = cursor
                .take()
                .map_or_else(|| json!({}), |c| json!({ "cursor": c }));
            let page = self.request("tools/list", params).await?;
            total += serde_json::to_vec(&page).map_or(usize::MAX, |b| b.len());
            if total > MAX_SCAN_BYTES {
                return Err(limit("tools/list is over 4 MiB"));
            }
            let items = page
                .get("tools")
                .and_then(Value::as_array)
                .ok_or_else(|| protocol("a tools/list page has no tools"))?;
            tools.extend(items.iter().cloned());
            if tools.len() > MAX_TOOLS {
                return Err(limit("tools/list has more than 256 tools"));
            }
            match page.get("nextCursor").and_then(Value::as_str) {
                Some(next) if !next.is_empty() => cursor = Some(next.to_string()),
                _ => return Ok(tools),
            }
        }
        Err(limit("tools/list has more than 16 pages"))
    }

    /// `tools/call`, returning the CallToolResult.
    pub async fn call(&mut self, name: &str, arguments: Value) -> Result<Value, Failure> {
        self.request(
            "tools/call",
            json!({ "name": name, "arguments": arguments }),
        )
        .await
    }
}
