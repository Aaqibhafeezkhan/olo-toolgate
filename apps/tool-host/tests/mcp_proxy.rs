// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The MCP scan worker and proxy executor against a scripted upstream MCP server over real TLS: pins, paging, limits,
//! deadlines, the inline check, and result validation (docs/planning/mcp-proxy/plan.md T5).

use olo_toolgate_contracts::v2::{
    McpCheckFailure, McpPermitVerification, McpProxyDocument, McpQuarantineReason, McpScanClaims,
    McpVerificationStatus, ResultProvenance,
};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::time::Instant;
use tool_host::mcp_call::{execute, Call, Outcome};
use tool_host::mcp_scan::observe;

/// How the upstream answers.
#[derive(Clone)]
struct Script {
    name: String,
    tools: Vec<Value>,
    page_size: usize,
    result: Value,
    error: Option<String>,
    /// Frame tools/call as SSE: a notification first, the response, then the stream stays open.
    sse: bool,
    /// tools/list replies trickle forever.
    trickle: bool,
    /// tools/call answers after this long.
    delay_ms: u64,
}

impl Default for Script {
    fn default() -> Script {
        Script {
            name: "orders-mcp".into(),
            tools: vec![tool("get_order", "Read one order.")],
            page_size: 100,
            result: json!({"content":[{"type":"text","text":"order 7"}],"structuredContent":{"id":"7"}}),
            error: None,
            sse: false,
            trickle: false,
            delay_ms: 0,
        }
    }
}

fn tool(name: &str, description: &str) -> Value {
    json!({"name": name, "description": description, "_meta": {"vendor": "x"},
           "inputSchema": {"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}})
}

struct Upstream {
    url: String,
    pin: String,
    script: Arc<Mutex<Script>>,
    /// Every request's method and Authorization header.
    seen: Arc<Mutex<Vec<(String, String)>>>,
}

fn serve(script: Script) -> Upstream {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let certified = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).unwrap();
    let der = certified.cert.der().to_vec();
    let pin = format!("sha256/{}", tool_host::egress::spki_pin(&der).unwrap());
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        certified.signing_key.serialize_der(),
    ));
    let config = Arc::new(
        rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![CertificateDer::from(der)], key)
            .unwrap(),
    );
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!(
        "https://localhost:{}/mcp",
        listener.local_addr().unwrap().port()
    );
    let script = Arc::new(Mutex::new(script));
    let seen = Arc::new(Mutex::new(Vec::new()));
    let (s, w) = (script.clone(), seen.clone());
    std::thread::spawn(move || {
        for socket in listener.incoming().flatten() {
            let (config, script, seen) = (config.clone(), s.clone(), w.clone());
            std::thread::spawn(move || {
                let connection = rustls::ServerConnection::new(config).unwrap();
                let mut stream = rustls::StreamOwned::new(connection, socket);
                while let Some((auth, body)) = request(&mut stream) {
                    let message: Value = serde_json::from_slice(&body).unwrap_or(Value::Null);
                    let method = message["method"].as_str().unwrap_or_default().to_string();
                    seen.lock().unwrap().push((method.clone(), auth));
                    let script = script.lock().unwrap().clone();
                    if !answer(&mut stream, &script, &message, &method) {
                        return;
                    }
                }
            });
        }
    });
    Upstream {
        url,
        pin,
        script,
        seen,
    }
}

/// One HTTP/1.1 request: its Authorization header and body; `None` when the connection closed.
fn request(stream: &mut impl Read) -> Option<(String, Vec<u8>)> {
    let mut head = Vec::new();
    let mut byte = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") {
        if stream.read(&mut byte).ok()? == 0 {
            return None;
        }
        head.push(byte[0]);
    }
    let head = String::from_utf8_lossy(&head).to_string();
    let header = |name: &str| {
        head.lines()
            .find(|l| l.to_ascii_lowercase().starts_with(&format!("{name}:")))
            .map(|l| l[name.len() + 1..].trim().to_string())
    };
    let length: usize = header("content-length")
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);
    let mut body = vec![0u8; length];
    stream.read_exact(&mut body).ok()?;
    Some((header("authorization").unwrap_or_default(), body))
}

/// Answers one message; false closes the connection.
fn answer(stream: &mut impl Write, script: &Script, message: &Value, method: &str) -> bool {
    let id = message.get("id").cloned();
    let json_reply = |stream: &mut dyn Write, value: Value| {
        let body = value.to_string();
        let _ = write!(stream, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nMcp-Session-Id: s-1\r\nContent-Length: {}\r\n\r\n{body}", body.len());
        let _ = stream.flush();
    };
    let Some(id) = id else {
        let _ = write!(stream, "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n\r\n");
        let _ = stream.flush();
        return true;
    };
    match method {
        "initialize" => json_reply(
            stream,
            json!({"jsonrpc":"2.0","id":id,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},
            "serverInfo":{"name":script.name,"version":"1.0.0","websiteUrl":"https://example.com"}}}),
        ),
        "tools/list" => {
            if script.trickle {
                let _ = write!(stream, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n");
                loop {
                    if write!(stream, "1\r\n \r\n")
                        .and_then(|()| stream.flush())
                        .is_err()
                    {
                        return false;
                    }
                    std::thread::sleep(Duration::from_millis(50));
                }
            }
            let start: usize = message["params"]["cursor"]
                .as_str()
                .and_then(|c| c.parse().ok())
                .unwrap_or(0);
            let end = (start + script.page_size).min(script.tools.len());
            let mut page = json!({"tools": script.tools[start..end]});
            if end < script.tools.len() {
                page["nextCursor"] = json!(end.to_string());
            }
            json_reply(stream, json!({"jsonrpc":"2.0","id":id,"result":page}));
        }
        "tools/call" => {
            std::thread::sleep(Duration::from_millis(script.delay_ms));
            let response = match &script.error {
                Some(message) => {
                    json!({"jsonrpc":"2.0","id":id,"error":{"code":-32000,"message":message}})
                }
                None => json!({"jsonrpc":"2.0","id":id,"result":script.result}),
            };
            if !script.sse {
                json_reply(stream, response);
                return true;
            }
            let _ = write!(stream, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n");
            for event in [
                json!({"jsonrpc":"2.0","method":"notifications/tools/list_changed"}),
                response,
            ] {
                let data = format!("event: message\ndata: {event}\n\n");
                let _ = write!(stream, "{:x}\r\n{data}\r\n", data.len());
            }
            let _ = stream.flush();
            // The stream is left open; the client must not wait for its end.
            for _ in 0..200 {
                std::thread::sleep(Duration::from_millis(50));
                if write!(stream, "e\r\n: keep-alive\n\n\r\n")
                    .and_then(|()| stream.flush())
                    .is_err()
                {
                    break;
                }
            }
            return false;
        }
        _ => json_reply(
            stream,
            json!({"jsonrpc":"2.0","id":id,"error":{"code":-32601,"message":"unknown"}}),
        ),
    }
    true
}

fn now() -> u64 {
    tool_host::now_ms()
}

fn claims(
    upstream: &Upstream,
    purpose: &str,
    pins: Vec<String>,
    deadline_ms: u64,
) -> McpScanClaims {
    serde_json::from_value(json!({"jti":"j","iss":"control","aud":"host-a","iat":now(),"exp":now()+10_000,"scanId":"scan-1","slug":"orders",
        "registrationRevision":2,"bindingId":"b","purpose":purpose,"url":upstream.url,"auth":{"secretName":"orders/mcp-token","scheme":"BEARER"},
        "spkiPins":pins,"fencingToken":"fence","deadlineUnixMs":now()+deadline_ms})).unwrap()
}

fn methods(upstream: &Upstream) -> Vec<String> {
    upstream
        .seen
        .lock()
        .unwrap()
        .iter()
        .map(|(m, _)| m.clone())
        .collect()
}

#[tokio::test]
async fn a_scan_pages_through_the_tools_and_reports_raw_definitions_with_the_pin() {
    let script = Script {
        tools: (0..5).map(|i| tool(&format!("t{i}"), "d")).collect(),
        page_size: 2,
        ..Script::default()
    };
    let upstream = serve(script);
    let result = observe(
        &claims(&upstream, "DISCOVER", vec![upstream.pin.clone()], 30_000),
        "s3cret".into(),
        true,
    )
    .await
    .unwrap();
    assert_eq!(result.tools.len(), 5);
    assert_eq!(result.spki_pin, upstream.pin);
    assert_eq!(result.server_info.name, "orders-mcp");
    // Only the definition members are reported: _meta is dropped.
    assert!(serde_json::to_value(&result.tools[0])
        .unwrap()
        .get("_meta")
        .is_none());
    assert_eq!(
        methods(&upstream),
        [
            "initialize",
            "notifications/initialized",
            "tools/list",
            "tools/list",
            "tools/list"
        ]
    );
    assert!(upstream
        .seen
        .lock()
        .unwrap()
        .iter()
        .all(|(_, auth)| auth == "Bearer s3cret"));
}

#[tokio::test]
async fn a_refresh_with_a_changed_key_fails_before_the_credential_is_sent() {
    let upstream = serve(Script::default());
    let other = format!("sha256/{}", "A".repeat(43) + "=");
    let failed = observe(
        &claims(&upstream, "REFRESH", vec![other], 30_000),
        "s3cret".into(),
        true,
    )
    .await
    .unwrap_err();
    assert_eq!(failed.0, McpCheckFailure::PinMismatch);
    assert!(
        upstream.seen.lock().unwrap().is_empty(),
        "no request, so no credential, reached the upstream"
    );
    // A refresh is never unpinned.
    assert_eq!(
        observe(
            &claims(&upstream, "REFRESH", vec![], 30_000),
            "s".into(),
            true
        )
        .await
        .unwrap_err()
        .0,
        McpCheckFailure::PinMismatch
    );
}

#[tokio::test]
async fn scan_limits_and_the_signed_deadline_end_a_scan() {
    let pages = serve(Script {
        tools: (0..17).map(|i| tool(&format!("t{i}"), "d")).collect(),
        page_size: 1,
        ..Script::default()
    });
    assert_eq!(
        observe(
            &claims(&pages, "DISCOVER", vec![pages.pin.clone()], 30_000),
            "s".into(),
            true
        )
        .await
        .unwrap_err()
        .0,
        McpCheckFailure::ScanLimitExceeded
    );
    let many = serve(Script {
        tools: (0..257).map(|i| tool(&format!("t{i}"), "d")).collect(),
        ..Script::default()
    });
    assert_eq!(
        observe(
            &claims(&many, "DISCOVER", vec![many.pin.clone()], 30_000),
            "s".into(),
            true
        )
        .await
        .unwrap_err()
        .0,
        McpCheckFailure::ScanLimitExceeded
    );
    // A trickling tools/list never finishes: the deadline cuts it.
    let slow = serve(Script {
        trickle: true,
        ..Script::default()
    });
    let started = std::time::Instant::now();
    let failed = observe(
        &claims(&slow, "DISCOVER", vec![slow.pin.clone()], 1_500),
        "s".into(),
        true,
    )
    .await
    .unwrap_err();
    assert_eq!(failed.0, McpCheckFailure::DeadlineExceeded);
    assert!(started.elapsed() < Duration::from_secs(5));
}

fn document(upstream: &Upstream, strict: bool, pins: Vec<String>) -> McpProxyDocument {
    let digest = tool_host::mcp::definition_digest(
        &tool_host::mcp::project(&tool("get_order", "Read one order.")).unwrap(),
    )
    .unwrap();
    serde_json::from_value(json!({"formatVersion":1,"server":{"slug":"orders","url":upstream.url,"auth":{"secretName":"orders/mcp-token","scheme":"BEARER"},
        "serverInfo":{"name":"orders-mcp","version":"1.0.0"},"spkiPins":pins},"approvalSequence":2,"strict":strict,
        "tools":[{"toolId":"mcp.orders/get-order","upstreamName":"get_order","definitionDigest":digest}]})).unwrap()
}

fn verification(at: u64) -> McpPermitVerification {
    McpPermitVerification {
        status: McpVerificationStatus::Current,
        approval_sequence: 2,
        checked_at_unix_ms: Some(at),
    }
}

async fn call(
    document: &McpProxyDocument,
    arguments: Value,
    evidence: Option<&McpPermitVerification>,
    output: Option<&Value>,
    deadline_ms: u64,
) -> Outcome {
    let input = json!({"type":"object","properties":{"id":{"type":"string"}},"required":["id"]});
    execute(Call {
        document,
        tool_id: "mcp.orders/get-order",
        input_schema: &input,
        output_schema: output,
        arguments,
        verification: evidence,
        now_ms: now(),
        secret: "s3cret".into(),
        allow_loopback: true,
        deadline: Instant::now() + Duration::from_millis(deadline_ms),
    })
    .await
}

fn refused(outcome: Outcome) -> (String, Option<McpQuarantineReason>) {
    match outcome {
        Outcome::Refused {
            code, quarantine, ..
        } => (code.to_string(), quarantine.map(|q| q.reason)),
        other => panic!("expected a refusal, got {other:?}"),
    }
}

#[tokio::test]
async fn a_call_with_fresh_evidence_goes_straight_to_the_tool_and_is_marked_untrusted() {
    let upstream = serve(Script::default());
    let doc = document(&upstream, false, vec![upstream.pin.clone()]);
    let output = json!({"type":"object","properties":{"id":{"type":"string"}},"required":["id"]});
    match call(
        &doc,
        json!({"id":"7"}),
        Some(&verification(now())),
        Some(&output),
        10_000,
    )
    .await
    {
        Outcome::Answered(frame) => {
            assert_eq!(frame.provenance, Some(ResultProvenance::UntrustedUpstream));
            assert_eq!(frame.structured_content.unwrap()["id"], "7");
        }
        other => panic!("{other:?}"),
    }
    assert_eq!(
        methods(&upstream),
        ["initialize", "notifications/initialized", "tools/call"]
    );
}

#[tokio::test]
async fn stale_missing_or_strict_evidence_checks_inline_and_a_failing_check_refuses_the_call() {
    let upstream = serve(Script::default());
    let doc = document(&upstream, false, vec![upstream.pin.clone()]);
    for evidence in [None, Some(verification(now() - 121_000))] {
        upstream.seen.lock().unwrap().clear();
        assert!(matches!(
            call(&doc, json!({"id":"7"}), evidence.as_ref(), None, 10_000).await,
            Outcome::Answered(_)
        ));
        assert_eq!(
            methods(&upstream),
            [
                "initialize",
                "notifications/initialized",
                "tools/list",
                "tools/call"
            ]
        );
    }
    let strict = document(&upstream, true, vec![upstream.pin.clone()]);
    upstream.seen.lock().unwrap().clear();
    assert!(matches!(
        call(
            &strict,
            json!({"id":"7"}),
            Some(&verification(now())),
            None,
            10_000
        )
        .await,
        Outcome::Answered(_)
    ));
    assert!(methods(&upstream).contains(&"tools/list".to_string()));

    // The definition changed upstream: refused and quarantined, and the tool is never called.
    upstream.script.lock().unwrap().tools =
        vec![tool("get_order", "Read one order. Also delete it.")];
    upstream.seen.lock().unwrap().clear();
    assert_eq!(
        refused(call(&doc, json!({"id":"7"}), None, None, 10_000).await),
        (
            "UPSTREAM_UNVERIFIED".into(),
            Some(McpQuarantineReason::DefinitionChanged)
        )
    );
    upstream.script.lock().unwrap().tools = vec![tool("other", "x")];
    assert_eq!(
        refused(call(&doc, json!({"id":"7"}), None, None, 10_000).await).1,
        Some(McpQuarantineReason::ToolMissing)
    );
    assert!(!methods(&upstream).contains(&"tools/call".to_string()));
    // Another server behind the same URL.
    upstream.script.lock().unwrap().name = "impostor".into();
    assert_eq!(
        refused(
            call(
                &doc,
                json!({"id":"7"}),
                Some(&verification(now())),
                None,
                10_000
            )
            .await
        )
        .1,
        Some(McpQuarantineReason::ServerChanged)
    );
}

#[tokio::test]
async fn bad_arguments_a_changed_key_and_bad_output_are_refused_or_failed() {
    let upstream = serve(Script::default());
    let doc = document(&upstream, false, vec![upstream.pin.clone()]);
    assert_eq!(
        refused(
            call(
                &doc,
                json!({"id":7}),
                Some(&verification(now())),
                None,
                10_000
            )
            .await
        )
        .0,
        "INVALID_ARGUMENTS"
    );
    let repinned = document(
        &upstream,
        false,
        vec![format!("sha256/{}=", "B".repeat(43))],
    );
    assert_eq!(
        refused(
            call(
                &repinned,
                json!({"id":"7"}),
                Some(&verification(now())),
                None,
                10_000
            )
            .await
        )
        .1,
        Some(McpQuarantineReason::PinMismatch)
    );
    assert!(
        upstream.seen.lock().unwrap().is_empty(),
        "nothing, credential included, was sent"
    );

    upstream.script.lock().unwrap().result = json!({"content":[{"type":"script","text":"x"}]});
    match call(
        &doc,
        json!({"id":"7"}),
        Some(&verification(now())),
        None,
        10_000,
    )
    .await
    {
        Outcome::Failed {
            frame, quarantine, ..
        } => {
            assert_eq!(frame.error.unwrap().code, "OUTPUT_INVALID");
            assert_eq!(
                quarantine.unwrap().reason,
                McpQuarantineReason::OutputInvalid
            );
            assert_eq!(frame.provenance, Some(ResultProvenance::UntrustedUpstream));
        }
        other => panic!("{other:?}"),
    }
    upstream.script.lock().unwrap().error = Some("upstream broke".into());
    match call(
        &doc,
        json!({"id":"7"}),
        Some(&verification(now())),
        None,
        10_000,
    )
    .await
    {
        Outcome::Failed {
            frame,
            quarantine: None,
            timed_out: false,
        } => assert_eq!(frame.error.unwrap().code, "UPSTREAM_ERROR"),
        other => panic!("{other:?}"),
    }
}

#[tokio::test]
async fn an_open_stream_with_notifications_returns_at_the_response_and_the_deadline_bounds_the_call(
) {
    let upstream = serve(Script {
        sse: true,
        ..Script::default()
    });
    let doc = document(&upstream, false, vec![upstream.pin.clone()]);
    let started = std::time::Instant::now();
    assert!(matches!(
        call(
            &doc,
            json!({"id":"7"}),
            Some(&verification(now())),
            None,
            20_000
        )
        .await,
        Outcome::Answered(_)
    ));
    assert!(
        started.elapsed() < Duration::from_secs(5),
        "did not wait for the open stream to end"
    );

    let slow = serve(Script {
        delay_ms: 3_000,
        ..Script::default()
    });
    let doc = document(&slow, false, vec![slow.pin.clone()]);
    match call(
        &doc,
        json!({"id":"7"}),
        Some(&verification(now())),
        None,
        1_000,
    )
    .await
    {
        Outcome::Failed { timed_out, .. } => assert!(timed_out),
        other => panic!("{other:?}"),
    }
}

/// The package layout Control's McpPackages generates, as the Tool Host validates it before unpacking: kind
/// MCP_PROXY, no SDK block, descriptor generation 1, runtime mcp-proxy and one mcp-proxy.json artifact.
#[test]
fn a_generated_mcp_proxy_package_passes_the_tool_host_package_rules() {
    use std::collections::BTreeMap;
    use toolgate_cli::{archive, canonical, package};
    let build = |generation: u64| {
        let upstream = tool("get_order", "Read one order.");
        let descriptor = json!({"descriptorGeneration":generation,"toolId":"mcp.orders/get-order","mcpName":"mcp.orders.get-order","kind":"SERVER",
            "title":"get_order","description":"Read one order.","inputSchema":upstream["inputSchema"],"errors":[],
            "effect":{"safety":"UNKNOWN","destructive":true,"openWorld":true},"businessKey":{"required":false,"namespace":"TENANT"},
            "execution":{"async":false,"maxDurationMs":60000,"stateless":true,"cancelIfAbandoned":true},
            "routes":[],"destinations":[],"httpAuth":[],"tcp":[],"variables":{"globals":[],"secrets":[],"claims":[]},"permissions":[],"toolsets":[]});
        let proxy = br#"{"approvalSequence":1}"#.to_vec();
        let canonical_descriptor = canonical::canonical_bytes(&descriptor).unwrap();
        let digest = canonical::sha256_tagged(&canonical_descriptor);
        let set = canonical::digest(
            &json!([{"toolId":"mcp.orders/get-order","toolDescriptorDigest":digest}]),
        )
        .unwrap();
        let manifest = json!({"schemaVersion":2,"kind":"MCP_PROXY","id":"mcp.orders","version":"1.0.1","publisher":"ToolGate",
            "compatibility":{"contractsVersion":"0.10.0-dev"},"runtimes":[{"runtimeId":"mcp-proxy","language":"mcp-proxy","version":"2"}],
            "tools":[{"descriptor":descriptor,"toolDescriptorDigest":digest,"runtimeId":"mcp-proxy","artifactIds":["mcp-proxy"]}],
            "descriptorSetDigest":set,"artifacts":[{"artifactId":"mcp-proxy","path":"artifacts/mcp-proxy.json",
            "mediaType":"application/vnd.ololabs.toolgate.mcp-proxy+json","runtimeId":"mcp-proxy","artifactDigest":canonical::sha256_tagged(&proxy),"sizeBytes":proxy.len()}],
            "credentialReferences":[],"connections":[],"composition":[],"limits":{"maxTools":64,"maxDescriptorBytes":1048576}});
        let mut files = BTreeMap::new();
        files.insert("artifacts/mcp-proxy.json".to_string(), proxy);
        files.insert("tools/get-order.json".to_string(), canonical_descriptor);
        files.insert(
            "toolgate-package.json".to_string(),
            serde_json::to_vec(&manifest).unwrap(),
        );
        let checksums = package::render_checksums(&files);
        files.insert("CHECKSUMS.sha256".to_string(), checksums);
        archive::write(&files).unwrap()
    };
    package::validate(&build(1)).unwrap();
    assert!(
        package::validate(&build(2)).is_err(),
        "MCP_PROXY descriptors are generation 1"
    );
}

#[tokio::test]
async fn a_list_changed_notification_during_a_call_is_noticed() {
    let upstream = serve(Script {
        sse: true,
        ..Script::default()
    });
    let doc = document(&upstream, false, vec![upstream.pin.clone()]);
    let input = json!({"type":"object","properties":{"id":{"type":"string"}},"required":["id"]});
    let (outcome, list_changed) = tool_host::mcp_call::execute_observed(Call {
        document: &doc,
        tool_id: "mcp.orders/get-order",
        input_schema: &input,
        output_schema: None,
        arguments: json!({"id":"7"}),
        verification: Some(&verification(now())),
        now_ms: now(),
        secret: "s3cret".into(),
        allow_loopback: true,
        deadline: Instant::now() + Duration::from_secs(20),
    })
    .await;
    assert!(matches!(outcome, Outcome::Answered(_)));
    assert!(list_changed);
}
