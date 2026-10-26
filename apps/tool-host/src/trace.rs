// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Execution correlation for one admitted attempt (ADR 031): the context the tool receives in `ToolInput` and the
//! Tool Host's own trace span. Nothing here is read for any admission, routing or policy decision.
use olo_toolgate_contracts::v2::{ExecutionContext, InvocationPermitClaims};
use sha2::{Digest, Sha256};

/// The attempt's `execution_id`: a one-way digest of the fencing token, so the token itself, a capability for secrets
/// and broker calls, never reaches telemetry. Matches `tests/fixtures/contracts/execution-id-vectors.json`.
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

/// A child of the Gateway's `traceparent`: the same trace and sampled flag with this host's own span ID. `None` when
/// the dispatch carried no well-formed parent.
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
    well_formed.then(|| {
        format!(
            "00-{}-{}-{}",
            &parent[3..35],
            crate::random_hex(8),
            &parent[53..]
        )
    })
}

/// The context handed to the tool: the caller's declared identifiers from the dispatch, plus the execution ID and the
/// attempt number from the Control-signed permit.
pub fn execution_context(
    declared: Option<&ExecutionContext>,
    claims: &InvocationPermitClaims,
    fencing_token: &str,
) -> ExecutionContext {
    ExecutionContext {
        execution_id: Some(execution_id(
            &claims.identity.tenant_id,
            &claims.invocation_id,
            fencing_token,
        )),
        attempt_number: Some(claims.attempt),
        tool_call_id: declared.and_then(|d| d.tool_call_id.clone()),
        session_id: declared.and_then(|d| d.session_id.clone()),
        agent_run_id: declared.and_then(|d| d.agent_run_id.clone()),
        parent_invocation_id: declared.and_then(|d| d.parent_invocation_id.clone()),
        tracestate: declared.and_then(|d| d.tracestate.clone()),
    }
}

/// The host's DETAILED Insights spans for one run: its execution, as the child span the tool also received, and each
/// brokered route call beneath it. Route ID, timing and status only.
#[allow(clippy::too_many_arguments)]
pub fn spans(
    parent: Option<&str>,
    own: &str,
    invocation_id: &str,
    execution_id: &str,
    started_unix_ms: u64,
    ended_unix_ms: u64,
    error_code: Option<&str>,
    egress: &[crate::broker::Egress],
) -> Vec<olo_toolgate_contracts::v2::InsightsSpan> {
    use olo_toolgate_contracts::v2::{InsightsSpan, InsightsSpanName, InsightsSpanStatus};
    let trace_id = own[3..35].to_owned();
    let span_id = own[36..52].to_owned();
    let code = error_code.filter(|c| error_code_shape(c));
    let mut out = vec![InsightsSpan {
        trace_id: trace_id.clone(),
        span_id: span_id.clone(),
        parent_span_id: parent
            .filter(|p| p.len() == 55)
            .map(|p| p[36..52].to_owned()),
        name: InsightsSpanName::HostExecute,
        source: None,
        invocation_id: invocation_id.to_owned(),
        execution_id: Some(execution_id.to_owned()),
        start_unix_ms: started_unix_ms,
        duration_ms: ended_unix_ms
            .saturating_sub(started_unix_ms)
            .min(86_400_000),
        status: if code.is_some() {
            InsightsSpanStatus::Error
        } else {
            InsightsSpanStatus::Ok
        },
        route_id: None,
        http_status: None,
        error_code: code.map(str::to_owned),
    }];
    for call in egress {
        let failed = call.refused || call.status.is_none_or(|s| s >= 500);
        out.push(InsightsSpan {
            trace_id: trace_id.clone(),
            span_id: crate::random_hex(8),
            parent_span_id: Some(span_id.clone()),
            name: InsightsSpanName::HostEgress,
            source: None,
            invocation_id: invocation_id.to_owned(),
            execution_id: Some(execution_id.to_owned()),
            start_unix_ms: call.started_unix_ms,
            duration_ms: call.duration_ms.min(86_400_000),
            status: if failed {
                InsightsSpanStatus::Error
            } else {
                InsightsSpanStatus::Ok
            },
            route_id: Some(call.route_id.clone()).filter(|r| identifier(r)),
            http_status: call
                .status
                .map(u64::from)
                .filter(|s| (100..600).contains(s)),
            error_code: call.refused.then(|| "BROKER_REFUSED".to_owned()),
        });
    }
    out
}

/// An allowlisted error code: upper-case letters, digits and underscores, as the protocol's error codes are.
fn error_code_shape(c: &str) -> bool {
    !c.is_empty()
        && c.len() <= 64
        && c.bytes().next().is_some_and(|b| b.is_ascii_uppercase())
        && c.bytes()
            .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_')
}

/// The contract `Identifier` shape.
pub fn identifier(v: &str) -> bool {
    let b = v.as_bytes();
    !b.is_empty()
        && b.len() <= 128
        && b[0].is_ascii_alphanumeric()
        && b[1..]
            .iter()
            .all(|c| c.is_ascii_alphanumeric() || b"._:/-".contains(c))
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
        let vectors = vectors["vectors"].as_array().unwrap();
        assert!(!vectors.is_empty());
        for v in vectors {
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
    fn the_execution_id_never_contains_the_fencing_token() {
        let token = "fence-token-for-a-test";
        assert!(!execution_id("t", "i", token).contains(token));
    }

    #[test]
    fn spans_describe_the_run_and_its_route_calls_without_urls() {
        let own = "00-0af7651916cd43dd8448eb211c80319c-1111111111111111-01";
        let parent = "00-0af7651916cd43dd8448eb211c80319c-2222222222222222-01";
        let egress = [
            crate::broker::Egress {
                route_id: "orders.get".into(),
                started_unix_ms: 10,
                duration_ms: 5,
                status: Some(503),
                refused: false,
            },
            crate::broker::Egress {
                route_id: "orders.get".into(),
                started_unix_ms: 20,
                duration_ms: 1,
                status: None,
                refused: true,
            },
        ];
        let execution = "ex-7576f712baed62964bc828cdba88bdbe";
        let spans = spans(
            Some(parent),
            own,
            "inv-1",
            execution,
            5,
            50,
            Some("DEADLINE_EXCEEDED"),
            &egress,
        );
        assert_eq!(spans.len(), 3);
        assert_eq!(spans[0].span_id, "1111111111111111");
        assert_eq!(spans[0].parent_span_id.as_deref(), Some("2222222222222222"));
        assert_eq!(spans[0].duration_ms, 45);
        assert_eq!(spans[0].error_code.as_deref(), Some("DEADLINE_EXCEEDED"));
        assert!(spans[1..]
            .iter()
            .all(|s| s.parent_span_id.as_deref() == Some("1111111111111111")
                && s.trace_id == spans[0].trace_id));
        assert_eq!(spans[1].http_status, Some(503));
        assert_eq!(spans[2].error_code.as_deref(), Some("BROKER_REFUSED"));
        // Free text is not an allowlisted error code.
        assert!(
            super::spans(None, own, "i", execution, 0, 1, Some("boom: x"), &[])[0]
                .error_code
                .is_none()
        );
    }

    #[test]
    fn child_spans_keep_the_trace_and_flags_with_a_new_span() {
        let parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        let child = child_traceparent(Some(parent)).unwrap();
        assert_eq!(&child[..36], &parent[..36]);
        assert_eq!(&child[52..], &parent[52..]);
        assert_ne!(&child[36..52], &parent[36..52]);
        assert_eq!(child.len(), 55);
        assert!(child_traceparent(None).is_none());
        assert!(child_traceparent(Some("00-short")).is_none());
        assert!(child_traceparent(Some(&parent.to_uppercase())).is_none());
    }
}
