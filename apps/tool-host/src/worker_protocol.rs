// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Fail-closed stdout boundary for the explicitly qualified isolated worker tier.
use serde_json::Value;
use std::collections::HashSet;

const MAX_FRAMES: usize = 1000;
const MAX_DEPTH: usize = 64;

pub(crate) struct Protocol {
    output_limit: usize,
    frames: usize,
    terminal: bool,
    failed: bool,
    requests: HashSet<String>,
}
impl Protocol {
    pub fn new(output_limit: usize) -> Self {
        Self {
            output_limit,
            frames: 0,
            terminal: false,
            failed: false,
            requests: HashSet::new(),
        }
    }
    pub fn accept(&mut self, line: &[u8]) -> Result<Option<Value>, &'static str> {
        if self.failed {
            return Err("worker protocol has ended");
        }
        let result = self.frame(line);
        if result.is_err() {
            self.failed = true;
        }
        result
    }
    fn frame(&mut self, line: &[u8]) -> Result<Option<Value>, &'static str> {
        if line.iter().all(u8::is_ascii_whitespace) {
            return Ok(None);
        }
        if self.terminal {
            return Err("worker emitted output after its terminal result");
        }
        self.frames += 1;
        if self.frames > MAX_FRAMES || line.len() > self.output_limit {
            return Err("worker frame or count limit exceeded");
        }
        let value = toolgate_cli::strict_json(line).map_err(|_| "invalid worker JSON frame")?;
        if depth(&value) > MAX_DEPTH {
            return Err("worker frame nesting limit exceeded");
        }
        let name = match value.get("kind").and_then(Value::as_str) {
            Some("BROKER_REQUEST") => "BrokerRequestFrame",
            Some("RESULT") => "ResultFrame",
            Some("LOG") => "LogFrame",
            Some("PROGRESS") => "ProgressFrame",
            Some("INPUT_REQUEST") => "InputRequestFrame",
            _ => return Err("unknown worker frame kind"),
        };
        if !toolgate_cli::schemas::errors(name, &value).is_ok_and(|errors| errors.is_empty()) {
            return Err("worker frame violates its canonical schema");
        }
        if name == "BrokerRequestFrame" {
            let id = value["requestId"]
                .as_str()
                .ok_or("worker request ID missing")?;
            if !self.requests.insert(id.into()) {
                return Err("worker reused a broker request ID");
            }
        }
        if name == "ResultFrame" {
            self.terminal = true;
        }
        Ok(Some(value))
    }
    pub fn finish(&mut self, trailing: &[u8]) -> Result<(), &'static str> {
        if self.failed {
            return Err("worker protocol has ended");
        }
        if !trailing.iter().all(u8::is_ascii_whitespace) {
            self.failed = true;
            return Err("worker output ended in a truncated frame");
        }
        if !self.terminal {
            self.failed = true;
            return Err("worker terminal result missing");
        }
        Ok(())
    }
}
fn depth(value: &Value) -> usize {
    match value {
        Value::Array(values) => 1 + values.iter().map(depth).max().unwrap_or(0),
        Value::Object(values) => 1 + values.values().map(depth).max().unwrap_or(0),
        _ => 1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn terminal() -> Vec<u8> {
        serde_json::to_vec(
            &json!({"kind":"RESULT","resultType":"complete","isError":false,"content":[]}),
        )
        .unwrap()
    }
    #[test]
    fn valid_canonical_frames_end_once_and_leave_no_partial_line() {
        let mut protocol = Protocol::new(4096);
        protocol
            .accept(br#"{"kind":"LOG","level":"info","message":"bounded"}"#)
            .unwrap();
        protocol
            .accept(br#"{"kind":"PROGRESS","fraction":0.5}"#)
            .unwrap();
        protocol.accept(&terminal()).unwrap();
        protocol.finish(b" \r\n").unwrap();
        assert!(protocol.accept(&terminal()).is_err());
    }
    #[test]
    fn malformed_duplicate_unknown_and_out_of_range_frames_poison_the_channel() {
        for bad in [
            b"{".as_slice(), b"\xff", b"{}",
            br#"{"kind":"RESULT","kind":"LOG","resultType":"complete","isError":false,"content":[]}"#,
            br#"{"kind":"LOG","level":"info","message":"a","unknown":true}"#,
            br#"{"kind":"PROGRESS","fraction":2}"#,
            br#"{"kind":"RESULT","resultType":"complete","isError":false,"content":[],"structuredContent":{"x":1,"x":2}}"#,
            br#"{"kind":"AUTHORITY","operation":"SECRET"}"#,
        ] {
            let mut protocol = Protocol::new(4096);
            assert!(protocol.accept(bad).is_err());
            assert!(protocol.accept(&terminal()).is_err(), "failure cannot be repaired by later success");
        }
    }
    #[test]
    fn terminal_order_truncation_and_missing_results_are_refused() {
        let mut protocol = Protocol::new(4096);
        protocol.accept(&terminal()).unwrap();
        assert!(protocol
            .accept(br#"{"kind":"LOG","level":"info","message":"late"}"#)
            .is_err());
        assert!(Protocol::new(4096).finish(&terminal()).is_err());
        assert!(Protocol::new(4096).finish(b"").is_err());
    }
    #[test]
    fn frame_byte_count_depth_and_log_limits_are_enforced() {
        assert!(Protocol::new(16).accept(&terminal()).is_err());
        let progress = br#"{"kind":"PROGRESS","fraction":0.5}"#;
        let mut protocol = Protocol::new(4096);
        for _ in 0..MAX_FRAMES {
            protocol.accept(progress).unwrap();
        }
        assert!(protocol.accept(progress).is_err());
        let mut value = Value::Null;
        for _ in 0..MAX_DEPTH {
            value = json!({"nested":value});
        }
        let deep = json!({"kind":"RESULT","resultType":"complete","isError":false,"content":[],"structuredContent":{"data":value}});
        assert!(Protocol::new(8192)
            .accept(&serde_json::to_vec(&deep).unwrap())
            .is_err());
        let log = json!({"kind":"LOG","level":"info","message":"x".repeat(8193)});
        assert!(Protocol::new(16384)
            .accept(&serde_json::to_vec(&log).unwrap())
            .is_err());
    }
    #[test]
    fn a_broker_request_identifier_cannot_be_reused_or_follow_a_terminal_result() {
        let request = br#"{"kind":"BROKER_REQUEST","requestId":"one","operation":"SECRET_REDEEM","request":{"handle":"owned"}}"#;
        let mut protocol = Protocol::new(4096);
        protocol.accept(request).unwrap();
        assert!(protocol.accept(request).is_err());
        let mut protocol = Protocol::new(4096);
        protocol.accept(&terminal()).unwrap();
        assert!(protocol.accept(request).is_err());
    }
    #[test]
    fn deterministic_mutations_never_accept_without_a_canonical_schema() {
        let seeds = [
            terminal(),
            br#"{"kind":"PROGRESS","fraction":0.5}"#.to_vec(),
        ];
        for seed in seeds {
            for index in 0..seed.len() {
                for byte in [0, b'{', b'"', 0xff] {
                    let mut mutated = seed.clone();
                    mutated[index] = byte;
                    if let Ok(Some(value)) = Protocol::new(4096).accept(&mutated) {
                        let name = if value["kind"] == "RESULT" {
                            "ResultFrame"
                        } else {
                            "ProgressFrame"
                        };
                        assert!(toolgate_cli::schemas::errors(name, &value)
                            .unwrap()
                            .is_empty());
                    }
                }
            }
        }
    }
}
