// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Execution correlation for a call Control relayed to this device (ADR 031), the same derivation the Tool Host uses.
//! Nothing here is read for any authorization decision.
use olo_toolgate_contracts::v2::ExecutionContext;
use sha2::{Digest, Sha256};

/// The context and trace parent one tool run receives in its `ToolInput`.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct RunTrace {
    pub traceparent: Option<String>,
    pub context: Option<ExecutionContext>,
}

/// The attempt's `execution_id`: a one-way digest of the fencing token, which never reaches telemetry itself. Matches
/// `tests/fixtures/contracts/execution-id-vectors.json`.
pub fn execution_id(tenant_id: &str, invocation_id: &str, fencing_token: &str) -> String {
    let mut hash = Sha256::new();
    for (i, part) in [
        "toolgate-execution-v1",
        tenant_id,
        invocation_id,
        fencing_token,
    ]
    .iter()
    .enumerate()
    {
        if i > 0 {
            hash.update([0u8]);
        }
        hash.update(part.as_bytes());
    }
    let hex: String = hash.finalize().iter().map(|b| format!("{b:02x}")).collect();
    format!("ex-{}", &hex[..32])
}

/// A child of the Gateway's `traceparent` with this device's own span ID, keeping the trace and sampled flag.
pub fn child_traceparent(parent: Option<&str>) -> Option<String> {
    let parent = parent?;
    let bytes = parent.as_bytes();
    let well_formed = parent.len() == 55
        && parent.starts_with("00-")
        && bytes[35] == b'-'
        && bytes[52] == b'-'
        && parent[3..35]
            .bytes()
            .chain(parent[36..52].bytes())
            .chain(parent[53..].bytes())
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b));
    if !well_formed {
        return None;
    }
    let span = crate::identity::nonce().ok()?;
    Some(format!(
        "00-{}-{}-{}",
        &parent[3..35],
        &span[..16],
        &parent[53..]
    ))
}

/// The run's trace: the caller's declared identifiers plus the execution ID of the consumed permit. A relayed device
/// call has one attempt. `child` is this device's span, minted once per call with [`child_traceparent`].
pub fn run_trace(
    child: Option<String>,
    declared: Option<&ExecutionContext>,
    tenant_id: &str,
    invocation_id: &str,
    fencing_token: Option<&str>,
) -> RunTrace {
    RunTrace {
        traceparent: child,
        context: Some(ExecutionContext {
            execution_id: fencing_token.map(|f| execution_id(tenant_id, invocation_id, f)),
            attempt_number: Some(1),
            tool_call_id: declared.and_then(|d| d.tool_call_id.clone()),
            session_id: declared.and_then(|d| d.session_id.clone()),
            agent_run_id: declared.and_then(|d| d.agent_run_id.clone()),
            parent_invocation_id: declared.and_then(|d| d.parent_invocation_id.clone()),
            tracestate: declared.and_then(|d| d.tracestate.clone()),
        }),
    }
}

/// The device's DETAILED Insights span for one relayed run: its execution, as the span the tool also received.
pub fn execution_span(
    parent: Option<&str>,
    child: &str,
    invocation_id: &str,
    execution_id: Option<String>,
    started_unix_ms: u64,
    ended_unix_ms: u64,
    ok: bool,
) -> olo_toolgate_contracts::v2::InsightsSpan {
    use olo_toolgate_contracts::v2::{InsightsSpan, InsightsSpanName, InsightsSpanStatus};
    InsightsSpan {
        trace_id: child[3..35].to_owned(),
        span_id: child[36..52].to_owned(),
        parent_span_id: parent
            .filter(|p| p.len() == 55)
            .map(|p| p[36..52].to_owned()),
        name: InsightsSpanName::ClientExecute,
        source: None,
        invocation_id: invocation_id.to_owned(),
        execution_id,
        start_unix_ms: started_unix_ms,
        duration_ms: ended_unix_ms
            .saturating_sub(started_unix_ms)
            .min(86_400_000),
        status: if ok {
            InsightsSpanStatus::Ok
        } else {
            InsightsSpanStatus::Error
        },
        route_id: None,
        http_status: None,
        error_code: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn execution_ids_match_the_shared_vectors() {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../tests/fixtures/contracts/execution-id-vectors.json"
        );
        let vectors: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        for v in vectors["vectors"].as_array().unwrap() {
            assert_eq!(
                execution_id(
                    v["tenantId"].as_str().unwrap(),
                    v["invocationId"].as_str().unwrap(),
                    v["fencingToken"].as_str().unwrap()
                ),
                v["executionId"].as_str().unwrap()
            );
        }
    }

    #[test]
    fn the_execution_span_is_the_span_the_tool_received() {
        let parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        let child = child_traceparent(Some(parent)).unwrap();
        let span = execution_span(Some(parent), &child, "inv-1", None, 10, 70, false);
        assert_eq!(span.span_id, &child[36..52]);
        assert_eq!(span.parent_span_id.as_deref(), Some("b7ad6b7169203331"));
        assert_eq!(span.duration_ms, 60);
        assert_eq!(
            span.status,
            olo_toolgate_contracts::v2::InsightsSpanStatus::Error
        );
    }

    #[test]
    fn declared_identifiers_and_the_trace_reach_the_run() {
        let declared = ExecutionContext {
            execution_id: Some("ex-00000000000000000000000000000000".into()),
            attempt_number: Some(9),
            tool_call_id: Some("call_1".into()),
            session_id: Some("sess-1".into()),
            agent_run_id: None,
            parent_invocation_id: None,
            tracestate: Some("v=1".into()),
        };
        let parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        let trace = run_trace(
            child_traceparent(Some(parent)),
            Some(&declared),
            "t",
            "i",
            Some("f"),
        );
        let context = trace.context.unwrap();
        // Executor-owned members are never taken from the caller.
        assert_eq!(
            context.execution_id.as_deref(),
            Some(execution_id("t", "i", "f").as_str())
        );
        assert_eq!(context.attempt_number, Some(1));
        assert_eq!(context.tool_call_id.as_deref(), Some("call_1"));
        assert_eq!(context.tracestate.as_deref(), Some("v=1"));
        let child = trace.traceparent.unwrap();
        assert_eq!(&child[..36], &parent[..36]);
        assert_ne!(&child[36..52], &parent[36..52]);
        assert!(run_trace(None, None, "t", "i", None)
            .context
            .unwrap()
            .execution_id
            .is_none());
    }
}
