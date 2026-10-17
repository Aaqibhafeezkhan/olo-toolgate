// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Protocol envelopes contain only allowlisted metadata, never arguments or credentials.
use serde_json::{json, Value};
pub(crate) fn summary(value: &Value) -> Value {
    fn redact(value: &Value, depth: usize) -> Value {
        if depth > 8 {
            return json!("[redacted]");
        }
        let Some(object) = value.as_object() else {
            return json!("[redacted]");
        };
        let mut result = serde_json::Map::new();
        for (key, value) in object.iter().take(40) {
            let visible = matches!(
                key.as_str(),
                "requestId"
                    | "deviceId"
                    | "toolId"
                    | "action"
                    | "state"
                    | "code"
                    | "error"
                    | "sequence"
                    | "reportSequence"
                    | "clientVersion"
                    | "platform"
                    | "revision"
                    | "appliedRevision"
                    | "serverTimeUnixMs"
                    | "nextIntervalMs"
                    | "expiresAtUnixMs"
                    | "digest"
                    | "permissionDigest"
                    | "enabled"
                    | "decision"
                    | "protocolVersion"
            );
            let nested = matches!(
                key.as_str(),
                "report"
                    | "localTools"
                    | "configuration"
                    | "permissions"
                    | "task"
                    | "request"
                    | "record"
                    | "context"
                    | "input"
            );
            let safe = if visible
                && (value.is_number()
                    || value.is_boolean()
                    || value.is_null()
                    || value.as_str().is_some_and(|s| {
                        s.len() <= 128
                            && s.bytes()
                                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
                    })) {
                value.clone()
            } else if nested && value.is_object() {
                redact(value, depth + 1)
            } else if value.is_array() {
                json!({"count":value.as_array().map_or(0, Vec::len)})
            } else {
                json!("[redacted]")
            };
            if key.len() <= 64 && key.bytes().all(|b| b.is_ascii_alphanumeric()) {
                result.insert(key.clone(), safe);
            }
        }
        Value::Object(result)
    }
    redact(value, 0)
}
pub(crate) fn packet(
    direction: &str,
    path: &str,
    request_id: &str,
    status: Option<u16>,
    message: Value,
) {
    tracing::info!(event="protocol_packet",service="gateway",direction,path,request_id,status, message=%message);
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn relay_arguments_results_and_leases_are_redacted() {
        let safe = summary(
            &json!({"record":{"requestId":"mcp-123","state":"DONE"},"request":{"toolId":"hotfolder.write_text","arguments":{"text":"private-text"}},"result":{"leaseId":"private-lease","output":{"text":"private-output"}}}),
        );
        assert!(!safe.to_string().contains("private-"));
        assert_eq!(safe["record"]["state"], "DONE");
    }
}
