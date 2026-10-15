// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Bounded protocol diagnostics. Values outside the metadata allowlist never enter logs.
use crate::{storage::ProtectedStore, Failure, Result};
use serde_json::{json, Value};
use std::{
    collections::{HashMap, VecDeque},
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock, Weak},
    time::Duration,
};

const LIMIT: usize = 65536;
const FLUSH_INTERVAL: Duration = Duration::from_secs(5);
const BATCH_SIZE: usize = 32;
static LOGS: OnceLock<Mutex<HashMap<PathBuf, Weak<SharedLog>>>> = OnceLock::new();

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
    shared: Arc<SharedLog>,
}
impl Drop for PacketLog {
    fn drop(&mut self) {
        if self.shared.flush().is_err() {
            tracing::warn!(event = "packet_log", result = "close_flush_failed");
        }
    }
}
struct Buffer {
    lines: VecDeque<Vec<u8>>,
    bytes: usize,
    pending: usize,
}
struct SharedLog {
    store: ProtectedStore,
    buffer: Mutex<Buffer>,
}
impl SharedLog {
    fn flush(&self) -> Result<()> {
        let mut buffer = self.buffer.lock().map_err(|_| Failure::Unavailable)?;
        if buffer.pending == 0 {
            return Ok(());
        }
        let mut content = Vec::with_capacity(buffer.bytes);
        for line in &buffer.lines {
            content.extend_from_slice(line);
            content.push(b'\n');
        }
        self.store.write("packets.jsonl", &content)?;
        buffer.pending = 0;
        Ok(())
    }
}
impl Drop for SharedLog {
    fn drop(&mut self) {
        if self.flush().is_err() {
            tracing::warn!(event = "packet_log", result = "shutdown_flush_failed");
        }
    }
}
impl PacketLog {
    pub fn open(directory: PathBuf) -> Result<Self> {
        // The transport and read-log built-in share one buffer for each protected directory.
        let store = ProtectedStore::open(directory.clone())?;
        let directory = std::fs::canonicalize(directory).map_err(|_| Failure::Unavailable)?;
        let mut logs = LOGS
            .get_or_init(|| Mutex::new(HashMap::new()))
            .lock()
            .map_err(|_| Failure::Unavailable)?;
        logs.retain(|_, value| value.strong_count() > 0);
        if let Some(shared) = logs.get(&directory).and_then(Weak::upgrade) {
            return Ok(Self { shared });
        }
        let previous = store.read("packets.jsonl")?.unwrap_or_default();
        let mut lines: VecDeque<Vec<u8>> = previous
            .split(|b| *b == b'\n')
            .filter(|line| !line.is_empty())
            .map(<[u8]>::to_vec)
            .collect();
        let mut bytes: usize = lines.iter().map(|line| line.len() + 1).sum();
        while lines.len() > 100 || bytes > LIMIT {
            if let Some(line) = lines.pop_front() {
                bytes = bytes.saturating_sub(line.len() + 1);
            }
        }
        let shared = Arc::new(SharedLog {
            store,
            buffer: Mutex::new(Buffer {
                lines,
                bytes,
                pending: 0,
            }),
        });
        let weak = Arc::downgrade(&shared);
        std::thread::Builder::new()
            .name("packet-log".into())
            .spawn(move || loop {
                std::thread::sleep(FLUSH_INTERVAL);
                let Some(shared) = weak.upgrade() else {
                    break;
                };
                if shared.flush().is_err() {
                    tracing::warn!(event = "packet_log", result = "flush_failed");
                }
            })
            .map_err(|_| Failure::Unavailable)?;
        logs.insert(directory, Arc::downgrade(&shared));
        Ok(Self { shared })
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
        let line = serde_json::to_vec(&event).map_err(|_| Failure::Validation)?;
        if line.len() + 1 > LIMIT {
            return Err(Failure::Validation);
        }
        let mut buffer = self
            .shared
            .buffer
            .lock()
            .map_err(|_| Failure::Unavailable)?;
        while buffer.lines.len() >= 100 || buffer.bytes + line.len() + 1 > LIMIT {
            let Some(first) = buffer.lines.pop_front() else {
                break;
            };
            buffer.bytes = buffer.bytes.saturating_sub(first.len() + 1);
        }
        buffer.bytes += line.len() + 1;
        buffer.lines.push_back(line);
        buffer.pending = buffer.pending.saturating_add(1);
        let flush = buffer.pending >= BATCH_SIZE;
        drop(buffer);
        if flush {
            self.shared.flush()?;
        }
        Ok(())
    }
    pub fn latest(&self) -> Result<Value> {
        let buffer = self
            .shared
            .buffer
            .lock()
            .map_err(|_| Failure::Unavailable)?;
        let line = buffer.lines.back().ok_or(Failure::Unavailable)?;
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
        log.shared.flush().unwrap();
        let content = log.shared.store.read("packets.jsonl").unwrap().unwrap();
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
    #[test]
    fn batching_shares_live_entries_and_flushes_on_last_close() {
        let directory = crate::storage::test_directory("packet-batch");
        let writer = PacketLog::open(directory.clone()).unwrap();
        let reader = PacketLog::open(directory.clone()).unwrap();
        writer.save(json!({"sequence":1})).unwrap();
        assert_eq!(reader.latest().unwrap(), json!({"sequence":1}));
        assert!(writer.shared.store.read("packets.jsonl").unwrap().is_none());
        for sequence in 2..=32 {
            writer.save(json!({"sequence":sequence})).unwrap();
        }
        let persisted = writer.shared.store.read("packets.jsonl").unwrap().unwrap();
        writer.save(json!({"sequence":33})).unwrap();
        assert_eq!(
            writer.shared.store.read("packets.jsonl").unwrap().unwrap(),
            persisted
        );
        drop(writer);
        drop(reader);
        let reopened = PacketLog::open(directory.clone()).unwrap();
        assert_eq!(reopened.latest().unwrap(), json!({"sequence":33}));
        drop(reopened);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[test]
    fn periodic_flush_persists_even_when_no_more_packets_arrive() {
        let directory = crate::storage::test_directory("packet-periodic");
        let log = PacketLog::open(directory.clone()).unwrap();
        log.save(json!({"sequence":1})).unwrap();
        let deadline = std::time::Instant::now() + Duration::from_secs(8);
        loop {
            if log.shared.store.read("packets.jsonl").unwrap().is_some() {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "periodic flush timed out"
            );
            std::thread::sleep(Duration::from_millis(50));
        }
        drop(log);
        let reopened = PacketLog::open(directory.clone()).unwrap();
        assert_eq!(reopened.latest().unwrap(), json!({"sequence":1}));
        drop(reopened);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
