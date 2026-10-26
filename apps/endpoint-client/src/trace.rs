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
/// call has one attempt.
pub fn run_trace(
    traceparent: Option<&str>,
    declared: Option<&ExecutionContext>,
    tenant_id: &str,
    invocation_id: &str,
    fencing_token: Option<&str>,
) -> RunTrace {
    RunTrace {
        traceparent: child_traceparent(traceparent),
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
        let trace = run_trace(Some(parent), Some(&declared), "t", "i", Some("f"));
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
