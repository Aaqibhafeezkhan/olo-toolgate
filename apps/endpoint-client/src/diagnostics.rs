// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Bounded protocol diagnostics. Values outside the metadata allowlist never enter logs.
use crate::{storage::ProtectedStore, Failure, Result};
use serde_json::{json, Value};
use std::{path::PathBuf, sync::Mutex};

static LOG_LOCK: Mutex<()> = Mutex::new(());
const LIMIT: usize = 65536;

pub fn summary(value: &Value) -> Value {
    summarize(value, 0)
}
fn summarize(value: &Value, depth: usize) -> Value {
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
                | "nextIntervalSeconds"
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
            summarize(value, depth + 1)
        } else if value.is_array() {
            json!({"count":value.as_array().map_or(0, Vec::len)})
        } else {
            json!("[redacted]")
        };
        // Unknown field names can themselves contain private data.
        if key.len() <= 64 && key.bytes().all(|b| b.is_ascii_alphanumeric()) {
            result.insert(key.clone(), safe);
        }
    }
    Value::Object(result)
}

pub struct PacketLog {
    store: ProtectedStore,
}
impl PacketLog {
    pub fn open(directory: PathBuf) -> Result<Self> {
        Ok(Self {
            store: ProtectedStore::open(directory)?,
        })
    }
    pub fn record(
        &self,
        direction: &str,
        path: &str,
        request_id: &str,
        reply: (Option<u16>, Option<&str>),
        bytes: usize,
        message: Value,
    ) {
        let (status, peer_id) = reply;
        let event = json!({"timestampUnixMs":crate::now(),"service":"client","event":"protocol_packet","direction":direction,"path":path,"requestId":request_id,"peerRequestId":peer_id,"status":status,"bytes":bytes,"message":message});
        tracing::info!(event="protocol_packet", packet=%event);
        if self.save(event).is_err() {
            tracing::warn!(event = "packet_log", result = "unavailable");
        }
    }
    fn save(&self, event: Value) -> Result<()> {
        let _guard = LOG_LOCK.lock().map_err(|_| Failure::Unavailable)?;
        let previous = self.store.read("packets.jsonl")?.unwrap_or_default();
        let line = serde_json::to_vec(&event).map_err(|_| Failure::Validation)?;
        if line.len() + 1 > LIMIT {
            return Err(Failure::Validation);
        }
        let mut lines: std::collections::VecDeque<&[u8]> = previous
            .split(|b| *b == b'\n')
            .filter(|line| !line.is_empty())
            .collect();
        let mut size = previous.len() + line.len() + 1;
        while lines.len() >= 100 || size > LIMIT {
            let Some(first) = lines.pop_front() else {
                break;
            };
            size = size.saturating_sub(first.len() + 1);
        }
        let mut content = Vec::new();
        for old in lines {
            content.extend_from_slice(old);
            content.push(b'\n');
        }
        content.extend_from_slice(&line);
        content.push(b'\n');
        self.store.write("packets.jsonl", &content)
    }
    pub fn latest(&self) -> Result<Value> {
        let _guard = LOG_LOCK.lock().map_err(|_| Failure::Unavailable)?;
        let bytes = self
            .store
            .read("packets.jsonl")?
            .ok_or(Failure::Unavailable)?;
        let line = bytes
            .split(|b| *b == b'\n')
            .rfind(|line| !line.is_empty())
            .ok_or(Failure::Unavailable)?;
        serde_json::from_slice(line).map_err(|_| Failure::Validation)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn secrets_and_tool_content_are_not_packet_metadata() {
        let value = summary(
            &json!({"sequence":2,"deviceCode":"private-device-code","identity":{"certificatePem":"private-cert"},"task":{"requestId":"mcp-123","request":{"toolId":"hotfolder.write_text","arguments":{"text":"private-text","path":"private-path"}},"leaseId":"private-lease"},"configuration":{"digest":"abc123","permissions":[{"resource":"private-resource"}]}}),
        );
        let text = value.to_string();
        assert!(!text.contains("private-"));
        assert_eq!(value["task"]["request"]["toolId"], "hotfolder.write_text");
        assert_eq!(value["configuration"]["permissions"]["count"], 1);
    }
    #[test]
    fn journal_retains_one_hundred_entries_and_returns_exactly_one() {
        let directory = crate::storage::test_directory("packet-log");
        let log = PacketLog::open(directory.clone()).unwrap();
        for sequence in 0..105 {
            log.save(json!({"sequence":sequence})).unwrap();
        }
        assert_eq!(log.latest().unwrap(), json!({"sequence":104}));
        let content = log.store.read("packets.jsonl").unwrap().unwrap();
        assert_eq!(
            content
                .split(|b| *b == b'\n')
                .filter(|line| !line.is_empty())
                .count(),
            100
        );
        drop(log);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
