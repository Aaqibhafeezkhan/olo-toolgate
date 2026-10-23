// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Streaming egress for the Tool Host's own native executors (the MCP proxy runtime). It is not a broker operation and
//! package code cannot reach it. A connection uses the same address policy, connect-time checks and pinned TLS as
//! `ROUTE_CALL` ([`crate::egress::client`]) and never follows a redirect. The reply is read under bounds: a JSON body
//! within a cap, or a `text/event-stream` parsed one event at a time with limits on event bytes, events and total bytes,
//! an idle timeout and the caller's deadline. Dropping a reply closes its connection, so a caller that has what it needs
//! stops reading an upstream that keeps the stream open.

use crate::egress::{self, AddressPolicy};
use olo_toolgate_contracts::v2::{
    DestinationScheme, EgressMode, PrivateRangeApproval, ServiceDestination, TlsSettings,
};
use reqwest::header::{HeaderMap, HeaderName, HeaderValue, CONTENT_TYPE};
use serde_json::Value;
use std::time::Duration;
use tokio::time::Instant;

/// The bounds one reply is read under.
#[derive(Debug, Clone)]
pub struct Limits {
    /// The largest `application/json` body.
    pub max_json_bytes: usize,
    /// The largest single event of an event stream.
    pub max_event_bytes: usize,
    /// The most events one event stream may carry.
    pub max_events: usize,
    /// The most bytes one event stream may carry.
    pub max_total_bytes: usize,
    /// The longest wait for the next bytes.
    pub idle: Duration,
}

impl Limits {
    /// MCP proxy defaults (plan §5): 1 MiB per message, 4 MiB per reply.
    pub const MCP: Limits = Limits {
        max_json_bytes: 1 << 20,
        max_event_bytes: 1 << 20,
        max_events: 1024,
        max_total_bytes: 4 << 20,
        idle: Duration::from_secs(15),
    };
}

/// Why a request or its reply was cut off.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StreamError {
    /// Refused by destination policy, or the connection or TLS (including a pin) failed.
    Connect(String),
    /// The upstream answered with a redirect, which is never followed.
    Redirect(u16),
    /// The upstream answered with an error status.
    Status(u16),
    /// The reply is neither JSON nor an event stream.
    ContentType(String),
    /// A limit was reached.
    TooLarge(&'static str),
    /// The reply is not valid JSON or a valid event stream.
    Malformed(String),
    /// No bytes arrived within the idle timeout.
    Idle,
    /// The caller's deadline passed.
    Deadline,
}

impl std::fmt::Display for StreamError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StreamError::Connect(e) => write!(f, "connection failed: {e}"),
            StreamError::Redirect(s) => write!(f, "redirect {s} not followed"),
            StreamError::Status(s) => write!(f, "upstream answered {s}"),
            StreamError::ContentType(t) => write!(f, "unexpected content type {t}"),
            StreamError::TooLarge(what) => write!(f, "{what} limit reached"),
            StreamError::Malformed(e) => write!(f, "malformed reply: {e}"),
            StreamError::Idle => write!(f, "upstream went idle"),
            StreamError::Deadline => write!(f, "deadline reached"),
        }
    }
}

/// One server-sent event.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Event {
    pub event: Option<String>,
    pub id: Option<String>,
    pub data: String,
}

/// An upstream endpoint: one URL and the client that may reach it.
pub struct Upstream {
    client: reqwest::Client,
    url: reqwest::Url,
}

impl Upstream {
    /// `pins` are `sha256/<base64>` SPKI pins; with none, the system roots verify the server. Plain `http` is allowed
    /// only to a loopback address when `allow_loopback` is set, for local development and tests.
    pub fn new(
        url: &str,
        pins: &[String],
        private_ranges: Vec<PrivateRangeApproval>,
        allow_loopback: bool,
    ) -> Result<Upstream, String> {
        let url = reqwest::Url::parse(url).map_err(|e| e.to_string())?;
        let host = url.host_str().ok_or("the URL has no host")?.to_string();
        let loopback = host
            .trim_matches(['[', ']'])
            .parse::<std::net::IpAddr>()
            .is_ok_and(|ip| ip.is_loopback());
        match url.scheme() {
            "https" => {}
            "http" if allow_loopback && loopback => {}
            scheme => return Err(format!("{scheme} is not allowed")),
        }
        if !url.username().is_empty() || url.password().is_some() {
            return Err("the URL carries user information".into());
        }
        let port = url.port_or_known_default().ok_or("the URL has no port")?;
        let host = host.trim_matches(['[', ']']).to_string();
        let destination = ServiceDestination {
            id: "mcp-upstream".into(),
            scheme: DestinationScheme::Https,
            host: host.clone(),
            port: u64::from(port),
            tls: TlsSettings {
                ca_bundle: None,
                spki_pins: pins.to_vec(),
            },
            mode: EgressMode::Brokered,
        };
        let policy = AddressPolicy {
            host,
            port,
            private_ranges,
            allow_loopback,
        };
        // An address literal never reaches the resolver, so it is checked here.
        if let Ok(ip) = policy.host.parse::<std::net::IpAddr>() {
            policy.check(ip).map_err(|e| e.0)?;
        }
        Ok(Upstream {
            client: egress::client(&destination, policy)?,
            url,
        })
    }

    /// POSTs `body` with `headers` and reads the reply's head before `deadline`. `secret` is one header carrying the
    /// upstream credential, marked sensitive so it never appears in logs.
    pub async fn post(
        &self,
        mut headers: HeaderMap,
        secret: Option<(HeaderName, String)>,
        body: Vec<u8>,
        limits: &Limits,
        deadline: Instant,
    ) -> Result<Reply, StreamError> {
        if let Some((name, value)) = secret {
            let mut value = HeaderValue::from_str(&value)
                .map_err(|_| StreamError::Connect("the credential is not a header value".into()))?;
            value.set_sensitive(true);
            headers.insert(name, value);
        }
        let request = self
            .client
            .post(self.url.clone())
            .headers(headers)
            .body(body);
        let response = match tokio::time::timeout_at(deadline, request.send()).await {
            Err(_) => return Err(StreamError::Deadline),
            Ok(Err(e)) => return Err(StreamError::Connect(error_chain(&e))),
            Ok(Ok(response)) => response,
        };
        let status = response.status().as_u16();
        if response.status().is_redirection() {
            return Err(StreamError::Redirect(status));
        }
        if !response.status().is_success() {
            return Err(StreamError::Status(status));
        }
        let observed_pin = response
            .extensions()
            .get::<reqwest::tls::TlsInfo>()
            .and_then(|info| info.peer_certificate())
            .and_then(egress::spki_pin)
            .map(|pin| format!("sha256/{pin}"));
        let headers = response.headers().clone();
        let media = headers
            .get(CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(|v| {
                v.split(';')
                    .next()
                    .unwrap_or("")
                    .trim()
                    .to_ascii_lowercase()
            })
            .unwrap_or_default();
        let mut reader = Reader {
            response,
            limits: limits.clone(),
            deadline,
            total: 0,
        };
        let body = match media.as_str() {
            "application/json" => {
                let bytes = reader.read_all(limits.max_json_bytes).await?;
                let value = serde_json::from_slice(&bytes)
                    .map_err(|e| StreamError::Malformed(e.to_string()))?;
                Body::Json(value)
            }
            "text/event-stream" => Body::Events(Box::new(Events {
                reader,
                buffer: Vec::new(),
                events: 0,
                done: false,
            })),
            "" if status == 202 || status == 204 => Body::Empty,
            other => return Err(StreamError::ContentType(other.to_string())),
        };
        Ok(Reply {
            status,
            headers,
            observed_pin,
            body,
        })
    }
}

/// The reply to one request.
pub struct Reply {
    pub status: u16,
    pub headers: HeaderMap,
    /// The `sha256/<base64>` SPKI pin of the server's certificate, over TLS.
    pub observed_pin: Option<String>,
    pub body: Body,
}

pub enum Body {
    Json(Value),
    Events(Box<Events>),
    /// An accepted notification: 202 or 204 with no body.
    Empty,
}

struct Reader {
    response: reqwest::Response,
    limits: Limits,
    deadline: Instant,
    total: usize,
}

impl Reader {
    /// The next bytes, `None` at the end of the body.
    async fn chunk(&mut self) -> Result<Option<Vec<u8>>, StreamError> {
        let idle = Instant::now() + self.limits.idle;
        let until = idle.min(self.deadline);
        match tokio::time::timeout_at(until, self.response.chunk()).await {
            Err(_) if until == self.deadline => Err(StreamError::Deadline),
            Err(_) => Err(StreamError::Idle),
            Ok(Err(e)) => Err(StreamError::Malformed(error_chain(&e))),
            Ok(Ok(chunk)) => Ok(chunk.map(|c| c.to_vec())),
        }
    }

    async fn read_all(&mut self, cap: usize) -> Result<Vec<u8>, StreamError> {
        let mut out = Vec::new();
        while let Some(chunk) = self.chunk().await? {
            if out.len() + chunk.len() > cap {
                return Err(StreamError::TooLarge("message bytes"));
            }
            out.extend_from_slice(&chunk);
        }
        Ok(out)
    }
}

/// An event stream, read one event at a time.
pub struct Events {
    reader: Reader,
    buffer: Vec<u8>,
    events: usize,
    done: bool,
}

impl Events {
    /// The next event, or `None` when the upstream ends the stream. An event cut off by the end is discarded, as the
    /// event-stream format requires.
    pub async fn next(&mut self) -> Result<Option<Event>, StreamError> {
        let limits = self.reader.limits.clone();
        loop {
            if let Some(end) = event_end(&self.buffer) {
                if end.0 > limits.max_event_bytes {
                    return Err(StreamError::TooLarge("event bytes"));
                }
                let raw: Vec<u8> = self.buffer.drain(..end.0 + end.1).collect();
                let text = std::str::from_utf8(&raw[..end.0])
                    .map_err(|_| StreamError::Malformed("an event is not UTF-8".into()))?;
                if let Some(event) = parse(text) {
                    self.events += 1;
                    if self.events > limits.max_events {
                        return Err(StreamError::TooLarge("events"));
                    }
                    return Ok(Some(event));
                }
                continue; // comments and empty events
            }
            if self.buffer.len() > limits.max_event_bytes {
                return Err(StreamError::TooLarge("event bytes"));
            }
            if self.done {
                return Ok(None);
            }
            match self.reader.chunk().await? {
                None => self.done = true,
                Some(chunk) => {
                    self.reader.total += chunk.len();
                    if self.reader.total > limits.max_total_bytes {
                        return Err(StreamError::TooLarge("stream bytes"));
                    }
                    self.buffer.extend_from_slice(&chunk);
                }
            }
        }
    }
}

/// Where the first event in `buffer` ends: its length and the length of the blank line after it.
fn event_end(buffer: &[u8]) -> Option<(usize, usize)> {
    let mut i = 0;
    while i < buffer.len() {
        for terminator in [&b"\r\n\r\n"[..], b"\n\n", b"\r\r"] {
            if buffer[i..].starts_with(terminator) {
                return Some((i, terminator.len()));
            }
        }
        i += 1;
    }
    None
}

/// One event's fields; `None` when it has no data (comments only).
fn parse(text: &str) -> Option<Event> {
    let (mut event, mut id, mut data, mut has_data) = (None, None, String::new(), false);
    for line in text.split(['\n']).map(|l| l.trim_end_matches('\r')) {
        if line.is_empty() || line.starts_with(':') {
            continue;
        }
        let (field, value) = match line.split_once(':') {
            Some((f, v)) => (f, v.strip_prefix(' ').unwrap_or(v)),
            None => (line, ""),
        };
        match field {
            "data" => {
                if has_data {
                    data.push('\n');
                }
                data.push_str(value);
                has_data = true;
            }
            "event" => event = Some(value.to_string()),
            "id" => id = Some(value.to_string()),
            _ => {}
        }
    }
    has_data.then_some(Event { event, id, data })
}

fn error_chain(error: &dyn std::error::Error) -> String {
    let mut out = error.to_string();
    let mut source = error.source();
    while let Some(e) = source {
        out.push_str(": ");
        out.push_str(&e.to_string());
        source = e.source();
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn events_split_on_blank_lines_and_join_data_lines() {
        assert_eq!(event_end(b"data: a\n\ndata: b"), Some((7, 2)));
        assert_eq!(event_end(b"data: a\r\n\r\n"), Some((7, 4)));
        assert_eq!(event_end(b"data: a\n"), None);
        assert_eq!(
            parse("event: message\nid: 7\ndata: {\"a\":\ndata: 1}"),
            Some(Event {
                event: Some("message".into()),
                id: Some("7".into()),
                data: "{\"a\":\n1}".into()
            })
        );
        assert_eq!(parse(": keep-alive"), None);
    }

    #[test]
    fn only_https_or_loopback_http_is_allowed() {
        assert!(Upstream::new("http://example.com/mcp", &[], vec![], true).is_err());
        assert!(Upstream::new("http://127.0.0.1:9/mcp", &[], vec![], false).is_err());
        assert!(Upstream::new("http://127.0.0.1:9/mcp", &[], vec![], true).is_ok());
        assert!(Upstream::new("https://user:pw@example.com/mcp", &[], vec![], false).is_err());
        assert!(Upstream::new("https://example.com/mcp", &[], vec![], false).is_ok());
    }
}
