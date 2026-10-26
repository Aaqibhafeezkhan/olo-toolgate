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
