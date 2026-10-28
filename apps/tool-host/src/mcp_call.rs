// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The MCP proxy executor (ADR 030, Call). An admitted call of an `MCP_PROXY` tool:
//! 1. validates the arguments against the approved input schema;
//! 2. connects with the approved SPKI pins, so a changed TLS key fails before the credential is sent;
//! 3. checks the upstream inline (identity and the tool's definition digest) when the package is strict or the
//!    permit's freshness evidence is not CURRENT and at most 120 s old; a failed or incomplete check refuses the call
//!    and nothing is sent to the upstream tool;
//! 4. sends `tools/call`, reads the matching response within bounds and closes the stream;
//! 5. validates the result, which is returned marked `UNTRUSTED_UPSTREAM`.
//!
//! [`execute`] does all of it without Control; the caller fetches the secret and files quarantine reports.

use crate::mcp::{self, Failure, Session};
use crate::stream::Limits;
use olo_toolgate_contracts::v2::{
    McpCheckFailure, McpPermitVerification, McpProxyDocument, McpQuarantineReason,
    McpVerificationStatus, ResultFrame, ResultProvenance, ResultType, ToolErrorBody,
};
use serde_json::{Map, Value};
use std::collections::BTreeMap;
use tokio::time::Instant;

/// How old the freshness evidence in a permit may be.
pub const FRESHNESS_MS: u64 = 120_000;
/// The largest result an upstream may return.
pub const MAX_RESULT_BYTES: usize = 4 << 20;
const CONTENT_TYPES: [&str; 5] = ["text", "image", "audio", "resource_link", "resource"];

/// One call of an approved tool.
pub struct Call<'a> {
    pub document: &'a McpProxyDocument,
    pub tool_id: &'a str,
    pub input_schema: &'a Value,
    pub output_schema: Option<&'a Value>,
    pub arguments: Value,
    pub verification: Option<&'a McpPermitVerification>,
    pub now_ms: u64,
    pub secret: String,
    pub allow_loopback: bool,
    pub deadline: Instant,
}

/// What a quarantine report from this call would say.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Evidence {
    pub reason: McpQuarantineReason,
    pub observed_digest: Option<String>,
}

#[derive(Debug)]
pub enum Outcome {
    /// The upstream answered; its result, marked UNTRUSTED_UPSTREAM.
    Answered(ResultFrame),
    /// Refused before the upstream tool was called.
    Refused {
        code: &'static str,
        message: String,
        quarantine: Option<Evidence>,
    },
    /// Sent to the upstream tool, which did not answer usefully.
    Failed {
        frame: ResultFrame,
        timed_out: bool,
        quarantine: Option<Evidence>,
    },
}

fn refused(
    code: &'static str,
    message: impl Into<String>,
    quarantine: Option<Evidence>,
) -> Outcome {
    Outcome::Refused {
        code,
        message: message.into(),
        quarantine,
    }
}

fn evidence(reason: McpQuarantineReason, observed_digest: Option<String>) -> Option<Evidence> {
    Some(Evidence {
        reason,
        observed_digest,
    })
}

/// A result frame of this executor: always `UNTRUSTED_UPSTREAM`.
pub fn frame(
    content: Vec<BTreeMap<String, Value>>,
    structured: Option<BTreeMap<String, Value>>,
    error: Option<(&str, String)>,
    is_error: bool,
) -> ResultFrame {
    ResultFrame {
        kind: "RESULT".into(),
        result_type: ResultType::Complete,
        content,
        structured_content: structured,
        is_error: is_error || error.is_some(),
        error: error.map(|(code, message)| ToolErrorBody {
            code: code.into(),
            message,
            retryable: false,
            data: None,
        }),
        provenance: Some(ResultProvenance::UntrustedUpstream),
    }
}

/// Is the permit's freshness evidence enough to skip the inline check?
pub fn fresh(
    strict: bool,
    sequence: u64,
    verification: Option<&McpPermitVerification>,
    now_ms: u64,
) -> bool {
    !strict
        && verification.is_some_and(|v| {
            v.status == McpVerificationStatus::Current
                && v.approval_sequence == sequence
                && v.checked_at_unix_ms.is_some_and(|at| {
                    at <= now_ms + 2_000 && now_ms.saturating_sub(at) <= FRESHNESS_MS
                })
        })
}

pub async fn execute(call: Call<'_>) -> Outcome {
    execute_observed(call).await.0
}

/// [`execute`], and whether the upstream sent `notifications/tools/list_changed` during the call.
pub async fn execute_observed(call: Call<'_>) -> (Outcome, bool) {
    let mut list_changed = false;
    let outcome = attempt(call, &mut list_changed).await;
    (outcome, list_changed)
}

async fn attempt(call: Call<'_>, list_changed: &mut bool) -> Outcome {
    let document = call.document;
    let Some(approved) = document.tools.iter().find(|t| t.tool_id == call.tool_id) else {
        return refused(
            "TOOL_NOT_APPROVED",
            "the tool is not in the approved proxy document",
            None,
        );
    };
    match jsonschema::validator_for(call.input_schema) {
        Ok(validator) if validator.is_valid(&call.arguments) => {}
        Ok(_) => {
            return refused(
                "INVALID_ARGUMENTS",
                "the arguments do not match the approved input schema",
                None,
            )
        }
        Err(_) => {
            return refused(
                "INVALID_ARGUMENTS",
                "the approved input schema cannot be compiled",
                None,
            )
        }
    }
    let mut session = match Session::open(
        &document.server.url,
        &document.server.spki_pins,
        &document.server.auth,
        call.secret,
        call.allow_loopback,
        Limits::MCP,
        call.deadline,
    ) {
        Ok(session) => session,
        Err(e) => return refused("UPSTREAM_UNVERIFIED", e.to_string(), None),
    };
    let initialized = match session.initialize().await {
        Ok(initialized) => initialized,
        // A changed TLS key fails the handshake, before the credential is sent, and quarantines the server.
        Err(e) if e.check() == McpCheckFailure::PinMismatch => {
            return refused(
                "UPSTREAM_UNVERIFIED",
                e.to_string(),
                evidence(McpQuarantineReason::PinMismatch, None),
            )
        }
        Err(e) => return refused("UPSTREAM_UNVERIFIED", e.to_string(), None),
    };
    if initialized.server_info.get("name").and_then(Value::as_str)
        != Some(document.server.server_info.name.as_str())
    {
        return refused(
            "UPSTREAM_UNVERIFIED",
            "the upstream server is not the approved one",
            evidence(McpQuarantineReason::ServerChanged, None),
        );
    }
    if !fresh(
        document.strict,
        document.approval_sequence,
        call.verification,
        call.now_ms,
    ) {
        let tools = match session.list().await {
            Ok(tools) => tools,
            Err(e) => {
                return refused(
                    "UPSTREAM_UNVERIFIED",
                    format!("the inline check did not complete: {e}"),
                    None,
                )
            }
        };
        let Some(current) = tools.iter().find(|t| {
            t.get("name").and_then(Value::as_str) == Some(approved.upstream_name.as_str())
        }) else {
            return refused(
                "UPSTREAM_UNVERIFIED",
                "the approved tool is missing upstream",
                evidence(McpQuarantineReason::ToolMissing, None),
            );
        };
        let digest = mcp::project(current).and_then(|d| mcp::definition_digest(&d).ok());
        if digest.as_deref() != Some(approved.definition_digest.as_str()) {
            return refused(
                "UPSTREAM_UNVERIFIED",
                "the tool's definition changed since approval",
                evidence(McpQuarantineReason::DefinitionChanged, digest),
            );
        }
    }
    let called = session.call(&approved.upstream_name, call.arguments).await;
    *list_changed = session.list_changed;
    let result = match called {
        Ok(result) => result,
        Err(Failure::Upstream(message)) => {
            return Outcome::Failed {
                frame: frame(vec![], None, Some(("UPSTREAM_ERROR", message)), true),
                timed_out: false,
                quarantine: None,
            }
        }
        Err(e) => {
            let timed_out = e.check() == McpCheckFailure::DeadlineExceeded;
            return Outcome::Failed {
                frame: frame(
                    vec![],
                    None,
                    Some((
                        if timed_out {
                            "DEADLINE_EXCEEDED"
                        } else {
                            "UPSTREAM_ERROR"
                        },
                        e.to_string(),
                    )),
                    true,
                ),
                timed_out,
                quarantine: None,
            };
        }
    };
    match validated(&result, call.output_schema) {
        Ok(frame) => Outcome::Answered(frame),
        Err(why) => Outcome::Failed {
            frame: frame(vec![], None, Some(("OUTPUT_INVALID", why)), true),
            timed_out: false,
            quarantine: evidence(McpQuarantineReason::OutputInvalid, None),
        },
    }
}

/// Runs an admitted call of an MCP_PROXY tool for the Tool Host: redeems the upstream credential under the call's
/// consumed permit, executes it, and files a quarantine report bound to this invocation when the upstream failed a
/// check. Returns the outcome, the result frame and, when nothing reached the upstream tool, why.
pub(crate) async fn run(
    host: &crate::server::Host,
    claims: &olo_toolgate_contracts::v2::InvocationPermitClaims,
    prepared: &crate::store::Prepared,
    dispatch: &olo_toolgate_contracts::v2::Dispatch,
    fencing_token: &str,
    deadline_ms: u64,
    document: &McpProxyDocument,
) -> (
    olo_toolgate_contracts::v2::InvocationOutcome,
    ResultFrame,
    Option<olo_toolgate_contracts::v2::RefusalReason>,
) {
    use olo_toolgate_contracts::v2::{InvocationOutcome, McpQuarantineReport, RefusalReason};
    let control = host.control();
    let id = &claims.invocation_id;
    let secret = match control
        .secret(id, fencing_token, &document.server.auth.secret_name)
        .await
    {
        Ok(secret) => secret,
        Err(e) => {
            tracing::warn!(invocation_id = %id, error = %e, "no upstream credential for the call");
            let failed = frame(
                vec![],
                None,
                Some((
                    "TOOL_HOST_UNAVAILABLE",
                    "The upstream credential is unavailable".into(),
                )),
                true,
            );
            return (
                InvocationOutcome::Failed,
                failed,
                Some(RefusalReason::StoreUnavailable),
            );
        }
    };
    let descriptor = &prepared.tool.descriptor;
    let input_schema = Value::Object(descriptor.input_schema.clone().into_iter().collect());
    let output_schema = descriptor
        .output_schema
        .clone()
        .map(|m| Value::Object(m.into_iter().collect()));
    let now = crate::now_ms();
    let (outcome, list_changed) = execute_observed(Call {
        document,
        tool_id: &claims.target.tool_id,
        input_schema: &input_schema,
        output_schema: output_schema.as_ref(),
        arguments: Value::Object(dispatch.arguments.clone().into_iter().collect()),
        verification: claims.mcp_verification.as_ref(),
        now_ms: now,
        secret,
        allow_loopback: host.allow_loopback(),
        deadline: Instant::now()
            + std::time::Duration::from_millis(deadline_ms.saturating_sub(now)),
    })
    .await;
    if list_changed {
        let request = olo_toolgate_contracts::v2::McpRefreshRequest {
            invocation_id: id.clone(),
            fencing_token: fencing_token.into(),
        };
        if let Err(e) = control.refresh(&request).await {
            tracing::warn!(invocation_id = %id, error = %e, "Control did not take the refresh request");
        }
    }
    let quarantine = match &outcome {
        Outcome::Refused { quarantine, .. } | Outcome::Failed { quarantine, .. } => {
            quarantine.clone()
        }
        Outcome::Answered(_) => None,
    };
    if let Some(evidence) = quarantine {
        tracing::info!(target: "toolgate::audit", invocation_id = %id, reason = ?evidence.reason, "MCP upstream quarantine reported");
        let report = McpQuarantineReport {
            invocation_id: Some(id.clone()),
            scan_id: None,
            fencing_token: fencing_token.into(),
            reason: evidence.reason,
            observed_digest: evidence.observed_digest,
            observed_server_info: None,
        };
        if let Err(e) = control.quarantine(&report).await {
            tracing::warn!(invocation_id = %id, error = %e, "Control did not take the quarantine report");
        }
    }
    match outcome {
        Outcome::Answered(result) if result.is_error => (InvocationOutcome::Failed, result, None),
        Outcome::Answered(result) => (InvocationOutcome::Completed, result, None),
        // Nothing reached the upstream tool.
        Outcome::Refused { code, message, .. } => (InvocationOutcome::Failed, frame(vec![], None, Some((code, message)), true), None),
        Outcome::Failed { frame, timed_out: true, .. } if claims.requires_execution_ledger => (
            InvocationOutcome::OutcomeUnknown,
            ResultFrame { error: Some(ToolErrorBody { code: "OUTCOME_UNKNOWN".into(), message: "The upstream did not answer before the deadline; its effect may have happened".into(), retryable: false, data: None }), ..frame },
            None,
        ),
        Outcome::Failed { frame, .. } => (InvocationOutcome::Failed, frame, None),
    }
}

/// A CallToolResult as a result frame: bounded, content blocks of known types, structured content valid against the
/// approved output schema.
fn validated(result: &Value, output_schema: Option<&Value>) -> Result<ResultFrame, String> {
    if serde_json::to_vec(result).map_or(usize::MAX, |b| b.len()) > MAX_RESULT_BYTES {
        return Err("the result is over 4 MiB".into());
    }
    let mut content = Vec::new();
    for block in result
        .get("content")
        .and_then(Value::as_array)
        .ok_or("the result has no content")?
    {
        let block: Map<String, Value> = block
            .as_object()
            .cloned()
            .ok_or("a content block is not an object")?;
        if !block
            .get("type")
            .and_then(Value::as_str)
            .is_some_and(|t| CONTENT_TYPES.contains(&t))
        {
            return Err("a content block has an unknown type".into());
        }
        content.push(block.into_iter().collect());
    }
    let is_error = result
        .get("isError")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let structured = match result.get("structuredContent") {
        None => None,
        Some(Value::Object(map)) => Some(map.clone()),
        Some(_) => return Err("structuredContent is not an object".into()),
    };
    if let (Some(schema), Some(structured), false) = (output_schema, &structured, is_error) {
        let validator = jsonschema::validator_for(schema)
            .map_err(|_| "the approved output schema cannot be compiled")?;
        if !validator.is_valid(&Value::Object(structured.clone())) {
            return Err("structuredContent does not match the approved output schema".into());
        }
    }
    if output_schema.is_some() && structured.is_none() && !is_error {
        return Err("the tool declares an output schema but returned no structuredContent".into());
    }
    Ok(frame(
        content,
        structured.map(|m| m.into_iter().collect()),
        None,
        is_error,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn verification(
        status: McpVerificationStatus,
        sequence: u64,
        at: Option<u64>,
    ) -> McpPermitVerification {
        McpPermitVerification {
            status,
            approval_sequence: sequence,
            checked_at_unix_ms: at,
        }
    }

    #[test]
    fn only_current_recent_evidence_for_this_approval_skips_the_inline_check() {
        let now = 1_000_000;
        assert!(fresh(
            false,
            2,
            Some(&verification(
                McpVerificationStatus::Current,
                2,
                Some(now - 1000)
            )),
            now
        ));
        assert!(
            !fresh(
                true,
                2,
                Some(&verification(McpVerificationStatus::Current, 2, Some(now))),
                now
            ),
            "strict"
        );
        assert!(!fresh(false, 2, None, now), "missing");
        assert!(
            !fresh(
                false,
                2,
                Some(&verification(
                    McpVerificationStatus::Unverified,
                    2,
                    Some(now)
                )),
                now
            ),
            "failed"
        );
        assert!(
            !fresh(
                false,
                2,
                Some(&verification(McpVerificationStatus::Current, 1, Some(now))),
                now
            ),
            "other approval"
        );
        assert!(
            !fresh(
                false,
                2,
                Some(&verification(
                    McpVerificationStatus::Current,
                    2,
                    Some(now - FRESHNESS_MS - 1)
                )),
                now
            ),
            "older than 120 s"
        );
        assert!(
            !fresh(
                false,
                2,
                Some(&verification(McpVerificationStatus::Current, 2, None)),
                now
            ),
            "no time"
        );
    }

    #[test]
    fn results_are_bounded_typed_and_match_the_output_schema() {
        let schema = serde_json::json!({"type":"object","properties":{"total":{"type":"number"}},"required":["total"]});
        let good = serde_json::json!({"content":[{"type":"text","text":"7"}],"structuredContent":{"total":7}});
        let frame = validated(&good, Some(&schema)).unwrap();
        assert_eq!(frame.provenance, Some(ResultProvenance::UntrustedUpstream));
        assert!(!frame.is_error);
        assert!(validated(&serde_json::json!({"content":[{"type":"script"}]}), None).is_err());
        assert!(validated(
            &serde_json::json!({"content":[],"structuredContent":{"total":"x"}}),
            Some(&schema)
        )
        .is_err());
        assert!(validated(&serde_json::json!({"content":[]}), Some(&schema)).is_err());
        assert!(validated(
            &serde_json::json!({"content":[{"type":"text","text":"x".repeat(MAX_RESULT_BYTES)}]}),
            None
        )
        .is_err());
        // An upstream error result is relayed as an error, still untrusted.
        assert!(
            validated(
                &serde_json::json!({"content":[{"type":"text","text":"no"}],"isError":true}),
                Some(&schema)
            )
            .unwrap()
            .is_error
        );
    }
}
