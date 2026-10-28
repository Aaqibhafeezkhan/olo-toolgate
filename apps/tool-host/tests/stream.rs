// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The streaming egress reader against a scripted upstream: it returns as soon as it has what it needs, enforces its
//! limits and deadline, never follows a redirect, and dropping a reply closes the connection.

use reqwest::header::HeaderMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::time::Instant;
use tool_host::stream::{Body, Limits, StreamError, Upstream};

/// What the upstream sends after the request: the response head and body parts, each after a pause; `forever` keeps
/// sending `filler` every 20 ms once the parts are out.
#[derive(Clone, Default)]
struct Script {
    head: String,
    parts: Vec<(u64, Vec<u8>)>,
    forever: Option<Vec<u8>>,
    /// Keeps the connection open, silent, for this long after the parts.
    hold_ms: u64,
}

struct Server {
    url: String,
    requests: Arc<AtomicUsize>,
    closed: tokio::sync::watch::Receiver<bool>,
}

async fn serve(script: Script) -> Server {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    let requests = Arc::new(AtomicUsize::new(0));
    let (closed_tx, closed) = tokio::sync::watch::channel(false);
    let counter = requests.clone();
    tokio::spawn(async move {
        while let Ok((mut socket, _)) = listener.accept().await {
            counter.fetch_add(1, Ordering::SeqCst);
            let script = script.clone();
            let closed_tx = closed_tx.clone();
            tokio::spawn(async move {
                let mut request = Vec::new();
                let mut buf = [0u8; 4096];
                while !request.windows(4).any(|w| w == b"\r\n\r\n") {
                    match socket.read(&mut buf).await {
                        Ok(0) | Err(_) => return,
                        Ok(n) => request.extend_from_slice(&buf[..n]),
                    }
                }
                let mut ok = socket.write_all(script.head.as_bytes()).await.is_ok();
                for (pause, part) in &script.parts {
                    tokio::time::sleep(Duration::from_millis(*pause)).await;
                    ok = ok && socket.write_all(part).await.is_ok();
                }
                tokio::time::sleep(Duration::from_millis(script.hold_ms)).await;
                if let Some(filler) = &script.forever {
                    while ok {
                        tokio::time::sleep(Duration::from_millis(20)).await;
                        ok = socket.write_all(filler).await.is_ok() && socket.flush().await.is_ok();
                    }
                    let _ = closed_tx.send(true);
                }
            });
        }
    });
    Server {
        url,
        requests,
        closed,
    }
}

fn chunk(data: &str) -> Vec<u8> {
    format!("{:x}\r\n{data}\r\n", data.len()).into_bytes()
}

fn sse_head() -> String {
    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
        .into()
}

fn upstream(server: &Server) -> Upstream {
    Upstream::new(&server.url, &[], vec![], true).unwrap()
}

fn soon(ms: u64) -> Instant {
    Instant::now() + Duration::from_millis(ms)
}

async fn post(server: &Server, limits: &Limits, deadline: Instant) -> Result<Body, StreamError> {
    upstream(server)
        .post(HeaderMap::new(), None, b"{}".to_vec(), limits, deadline)
        .await
        .map(|reply| reply.body)
}

fn events(body: Body) -> tool_host::stream::Events {
    match body {
        Body::Events(events) => *events,
        _ => panic!("expected an event stream"),
    }
}

#[tokio::test]
async fn a_json_reply_is_read_within_its_cap() {
    let body = r#"{"jsonrpc":"2.0","id":1,"result":{}}"#;
    let head = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    );
    let server = serve(Script {
        head,
        ..Default::default()
    })
    .await;
    match post(&server, &Limits::MCP, soon(5000)).await.unwrap() {
        Body::Json(value) => assert_eq!(value["id"], 1),
        _ => panic!("expected JSON"),
    }
    let small = Limits {
        max_json_bytes: 8,
        ..Limits::MCP
    };
    assert_eq!(
        post(&server, &small, soon(5000)).await.err(),
        Some(StreamError::TooLarge("message bytes"))
    );
}

#[tokio::test]
async fn the_reader_returns_the_result_while_the_stream_stays_open_and_dropping_it_closes_the_connection(
) {
    let server = serve(Script {
        head: sse_head(),
        parts: vec![
            (
                0,
                chunk("event: message\ndata: {\"method\":\"notifications/progress\"}\n\n"),
            ),
            (
                0,
                chunk(": keep-alive\n\nevent: message\ndata: {\"id\":1,\n"),
            ),
            (0, chunk("data: \"result\":{}}\n\n")),
        ],
        forever: Some(chunk(": still here\n\n")),
        hold_ms: 0,
    })
    .await;
    let started = Instant::now();
    let mut stream = events(post(&server, &Limits::MCP, soon(10_000)).await.unwrap());
    let notification = stream.next().await.unwrap().unwrap();
    assert_eq!(notification.event.as_deref(), Some("message"));
    assert!(notification.data.contains("notifications/progress"));
    let result = stream.next().await.unwrap().unwrap();
    assert_eq!(result.data, "{\"id\":1,\n\"result\":{}}");
    assert!(
        started.elapsed() < Duration::from_secs(2),
        "returned as soon as the result arrived"
    );

    // The caller has what it needs and stops: the upstream sees the connection close.
    drop(stream);
    let mut closed = server.closed.clone();
    tokio::time::timeout(Duration::from_secs(5), closed.wait_for(|c| *c))
        .await
        .expect("the connection closes once the reply is dropped")
        .unwrap();
}

#[tokio::test]
async fn oversize_and_malformed_events_are_refused() {
    let big = format!("data: {}\n\n", "x".repeat(64));
    let server = serve(Script {
        head: sse_head(),
        parts: vec![(0, chunk(&big))],
        ..Default::default()
    })
    .await;
    let limits = Limits {
        max_event_bytes: 32,
        ..Limits::MCP
    };
    assert_eq!(
        events(post(&server, &limits, soon(5000)).await.unwrap())
            .next()
            .await
            .err(),
        Some(StreamError::TooLarge("event bytes"))
    );

    let many = "data: 1\n\n".repeat(5);
    let server = serve(Script {
        head: sse_head(),
        parts: vec![(0, chunk(&many))],
        ..Default::default()
    })
    .await;
    let limits = Limits {
        max_events: 3,
        ..Limits::MCP
    };
    let mut stream = events(post(&server, &limits, soon(5000)).await.unwrap());
    for _ in 0..3 {
        assert!(stream.next().await.unwrap().is_some());
    }
    assert_eq!(
        stream.next().await.err(),
        Some(StreamError::TooLarge("events"))
    );

    let server = serve(Script {
        head: sse_head(),
        parts: vec![(0, chunk(&"data: 1\n\n".repeat(20)))],
        ..Default::default()
    })
    .await;
    let limits = Limits {
        max_total_bytes: 64,
        ..Limits::MCP
    };
    let mut stream = events(post(&server, &limits, soon(5000)).await.unwrap());
    assert_eq!(
        stream.next().await.err(),
        Some(StreamError::TooLarge("stream bytes"))
    );

    let mut invalid = b"data: ".to_vec();
    invalid.extend_from_slice(&[0xff, 0xfe]);
    invalid.extend_from_slice(b"\n\n");
    let mut part = format!("{:x}\r\n", invalid.len()).into_bytes();
    part.extend_from_slice(&invalid);
    part.extend_from_slice(b"\r\n");
    let server = serve(Script {
        head: sse_head(),
        parts: vec![(0, part)],
        ..Default::default()
    })
    .await;
    assert!(matches!(
        events(post(&server, &Limits::MCP, soon(5000)).await.unwrap())
            .next()
            .await,
        Err(StreamError::Malformed(_))
    ));
}

#[tokio::test]
async fn a_never_ending_stream_is_cut_at_the_deadline_and_an_idle_one_at_the_idle_timeout() {
    // Bytes keep arriving but never form an event: only the deadline ends it.
    let server = serve(Script {
        head: sse_head(),
        parts: vec![(0, chunk("data: "))],
        forever: Some(chunk("x")),
        hold_ms: 0,
    })
    .await;
    let started = Instant::now();
    let mut stream = events(post(&server, &Limits::MCP, soon(600)).await.unwrap());
    assert_eq!(stream.next().await.err(), Some(StreamError::Deadline));
    assert!(started.elapsed() < Duration::from_secs(3));

    let server = serve(Script {
        head: sse_head(),
        parts: vec![(0, chunk("data: 1\n\n"))],
        hold_ms: 5000,
        ..Default::default()
    })
    .await;
    let limits = Limits {
        idle: Duration::from_millis(300),
        ..Limits::MCP
    };
    let mut stream = events(post(&server, &limits, soon(10_000)).await.unwrap());
    assert!(stream.next().await.unwrap().is_some());
    assert_eq!(stream.next().await.err(), Some(StreamError::Idle));
}

#[tokio::test]
async fn redirects_are_not_followed_and_errors_are_reported() {
    let server = serve(Script {
        head: "HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:1/elsewhere\r\nContent-Length: 0\r\n\r\n".into(),
        ..Default::default()
    })
    .await;
    assert_eq!(
        post(&server, &Limits::MCP, soon(5000)).await.err(),
        Some(StreamError::Redirect(307))
    );
    assert_eq!(server.requests.load(Ordering::SeqCst), 1);

    let server = serve(Script {
        head: "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".into(),
        ..Default::default()
    })
    .await;
    assert_eq!(
        post(&server, &Limits::MCP, soon(5000)).await.err(),
        Some(StreamError::Status(401))
    );

    let server = serve(Script {
        head: "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 0\r\n\r\n".into(),
        ..Default::default()
    })
    .await;
    assert_eq!(
        post(&server, &Limits::MCP, soon(5000)).await.err(),
        Some(StreamError::ContentType("text/html".into()))
    );

    let server = serve(Script {
        head: "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n\r\n".into(),
        ..Default::default()
    })
    .await;
    assert!(matches!(
        post(&server, &Limits::MCP, soon(5000)).await,
        Ok(Body::Empty)
    ));
}

#[tokio::test]
async fn a_destination_that_resolves_to_a_refused_address_is_never_contacted() {
    let server = serve(Script::default()).await;
    // Loopback is refused unless explicitly allowed (development only).
    let url = server.url.replace("http://", "https://");
    assert!(Upstream::new(&url, &[], vec![], false).is_err());
    // A name is checked at connect time, after resolution.
    let named = url.replace("127.0.0.1", "localhost");
    let upstream = Upstream::new(&named, &[], vec![], false).unwrap();
    let refused = upstream
        .post(HeaderMap::new(), None, vec![], &Limits::MCP, soon(5000))
        .await;
    assert!(
        matches!(refused, Err(StreamError::Connect(_))),
        "{:?}",
        refused.err()
    );
    assert_eq!(server.requests.load(Ordering::SeqCst), 0);
}
