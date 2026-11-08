// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Trigger-mode stdio frames (plan 6.2). Every frame is a generated contract type from
//! `olo-toolgate-contracts`; this module only dispatches on `kind` and sets the constant `kind` values.

use olo_toolgate_contracts::v2::{
    TriggerAcceptedUpToFrame, TriggerCheckpointAckFrame, TriggerCheckpointFrame,
    TriggerCheckpointLevel, TriggerEmitAckFrame, TriggerEmitFrame, TriggerEmitStatus,
    TriggerExitFrame, TriggerHealthFrame, TriggerHeartbeatFrame, TriggerPauseFrame,
    TriggerReadyFrame, TriggerReconcileFrame, TriggerResumeFrame, TriggerStartFrame,
    TriggerStopFrame,
};
use serde_json::Value;
use std::fmt;

/// Longest stdout or stdin line accepted, in bytes. Event data is at most 16 KiB and a position at most
/// 5 464 base64 characters (the contract bounds); unchanged `LOG` and `BROKER_REQUEST` frames share the limit.
pub const MAX_FRAME_BYTES: usize = 64 * 1024;

/// Frames the supervisor writes to the trigger process's stdin.
#[derive(Debug, Clone, PartialEq)]
pub enum Inbound {
    Start(TriggerStartFrame),
    EmitAck(TriggerEmitAckFrame),
    AcceptedUpTo(TriggerAcceptedUpToFrame),
    CheckpointAck(TriggerCheckpointAckFrame),
    Reconcile(TriggerReconcileFrame),
    Pause(TriggerPauseFrame),
    Resume(TriggerResumeFrame),
    Stop(TriggerStopFrame),
}

/// Frames the trigger process writes to stdout.
#[derive(Debug, Clone, PartialEq)]
pub enum Outbound {
    Ready(TriggerReadyFrame),
    Emit(TriggerEmitFrame),
    Checkpoint(TriggerCheckpointFrame),
    Health(TriggerHealthFrame),
    Heartbeat(TriggerHeartbeatFrame),
    Exit(TriggerExitFrame),
    /// `LOG` and `BROKER_REQUEST` are the unchanged tool-protocol frames. The executor handles them
    /// (log redaction, the lease-bound broker); the supervisor only counts them as liveness.
    Passthrough {
        kind: String,
        frame: Value,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrameError {
    TooLong,
    NotJson(String),
    MissingKind,
    UnknownKind(String),
    Invalid { kind: String, detail: String },
}

impl fmt::Display for FrameError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::TooLong => write!(f, "frame longer than {MAX_FRAME_BYTES} bytes"),
            Self::NotJson(detail) => write!(f, "frame is not JSON: {detail}"),
            Self::MissingKind => write!(f, "frame has no string kind"),
            Self::UnknownKind(kind) => write!(f, "unknown trigger frame kind {kind}"),
            Self::Invalid { kind, detail } => write!(f, "invalid {kind} frame: {detail}"),
        }
    }
}

impl std::error::Error for FrameError {}

fn typed<T: serde::de::DeserializeOwned>(kind: &str, value: Value) -> Result<T, FrameError> {
    serde_json::from_value(value).map_err(|error| FrameError::Invalid {
        kind: kind.to_owned(),
        detail: error.to_string(),
    })
}

/// Parses one stdout line (without its newline).
pub fn parse_outbound(line: &[u8]) -> Result<Outbound, FrameError> {
    if line.len() > MAX_FRAME_BYTES {
        return Err(FrameError::TooLong);
    }
    let value: Value =
        serde_json::from_slice(line).map_err(|error| FrameError::NotJson(error.to_string()))?;
    let kind = value
        .get("kind")
        .and_then(Value::as_str)
        .ok_or(FrameError::MissingKind)?
        .to_owned();
    Ok(match kind.as_str() {
        "READY" => Outbound::Ready(typed(&kind, value)?),
        "EMIT" => Outbound::Emit(typed(&kind, value)?),
        "CHECKPOINT" => Outbound::Checkpoint(typed(&kind, value)?),
        "HEALTH" => Outbound::Health(typed(&kind, value)?),
        "HEARTBEAT" => Outbound::Heartbeat(typed(&kind, value)?),
        "EXIT" => Outbound::Exit(typed(&kind, value)?),
        "LOG" | "BROKER_REQUEST" => Outbound::Passthrough { kind, frame: value },
        _ => return Err(FrameError::UnknownKind(kind)),
    })
}

/// Serializes one stdin frame as a single line with its trailing newline.
pub fn encode_inbound(frame: &Inbound) -> String {
    let encoded = match frame {
        Inbound::Start(f) => serde_json::to_string(f),
        Inbound::EmitAck(f) => serde_json::to_string(f),
        Inbound::AcceptedUpTo(f) => serde_json::to_string(f),
        Inbound::CheckpointAck(f) => serde_json::to_string(f),
        Inbound::Reconcile(f) => serde_json::to_string(f),
        Inbound::Pause(f) => serde_json::to_string(f),
        Inbound::Resume(f) => serde_json::to_string(f),
        Inbound::Stop(f) => serde_json::to_string(f),
    };
    let mut line = encoded.expect("generated frame types always serialize");
    line.push('\n');
    line
}

/// Parses a stdin line; used by fake runtimes in tests and by SDK conformance fixtures.
pub fn parse_inbound(line: &[u8]) -> Result<Inbound, FrameError> {
    if line.len() > MAX_FRAME_BYTES {
        return Err(FrameError::TooLong);
    }
    let value: Value =
        serde_json::from_slice(line).map_err(|error| FrameError::NotJson(error.to_string()))?;
    let kind = value
        .get("kind")
        .and_then(Value::as_str)
        .ok_or(FrameError::MissingKind)?
        .to_owned();
    Ok(match kind.as_str() {
        "TRIGGER_START" => Inbound::Start(typed(&kind, value)?),
        "EMIT_ACK" => Inbound::EmitAck(typed(&kind, value)?),
        "ACCEPTED_UPTO" => Inbound::AcceptedUpTo(typed(&kind, value)?),
        "CHECKPOINT_ACK" => Inbound::CheckpointAck(typed(&kind, value)?),
        "RECONCILE" => Inbound::Reconcile(typed(&kind, value)?),
        "PAUSE" => Inbound::Pause(typed(&kind, value)?),
        "RESUME" => Inbound::Resume(typed(&kind, value)?),
        "STOP" => Inbound::Stop(typed(&kind, value)?),
        _ => return Err(FrameError::UnknownKind(kind)),
    })
}

impl Inbound {
    pub fn emit_ack(
        emit_seq: u64,
        status: TriggerEmitStatus,
        reason: Option<&str>,
        outbox_seq: Option<u64>,
    ) -> Self {
        Self::EmitAck(TriggerEmitAckFrame {
            kind: "EMIT_ACK".into(),
            emit_seq,
            status,
            reason: reason.map(str::to_owned),
            outbox_seq,
        })
    }

    pub fn accepted_up_to(emit_seq: u64) -> Self {
        Self::AcceptedUpTo(TriggerAcceptedUpToFrame {
            kind: "ACCEPTED_UPTO".into(),
            emit_seq,
        })
    }

    pub fn checkpoint_ack(checkpoint_id: u64, level: TriggerCheckpointLevel) -> Self {
        Self::CheckpointAck(TriggerCheckpointAckFrame {
            kind: "CHECKPOINT_ACK".into(),
            checkpoint_id,
            level,
        })
    }

    pub fn reconcile() -> Self {
        Self::Reconcile(TriggerReconcileFrame {
            kind: "RECONCILE".into(),
        })
    }

    pub fn pause() -> Self {
        Self::Pause(TriggerPauseFrame {
            kind: "PAUSE".into(),
        })
    }

    pub fn resume() -> Self {
        Self::Resume(TriggerResumeFrame {
            kind: "RESUME".into(),
        })
    }

    pub fn stop(reason: &str, grace_ms: u64) -> Self {
        Self::Stop(TriggerStopFrame {
            kind: "STOP".into(),
            reason: reason.into(),
            grace_ms,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stdout_frames_round_trip_through_the_generated_types() {
        let emit = br#"{"kind":"EMIT","emitSeq":3,"id":"m-1","data":{"n":1},"position":"AQ=="}"#;
        let Outbound::Emit(frame) = parse_outbound(emit).unwrap() else {
            panic!("not an emit")
        };
        assert_eq!(
            (
                frame.emit_seq,
                frame.id.as_deref(),
                frame.position.as_deref()
            ),
            (3, Some("m-1"), Some("AQ=="))
        );
        assert!(matches!(
            parse_outbound(br#"{"kind":"HEARTBEAT"}"#),
            Ok(Outbound::Heartbeat(_))
        ));
        assert!(matches!(
            parse_outbound(br#"{"kind":"LOG","level":"INFO"}"#),
            Ok(Outbound::Passthrough { .. })
        ));
    }

    #[test]
    fn malformed_frames_are_refused() {
        assert_eq!(parse_outbound(b"{}"), Err(FrameError::MissingKind));
        assert_eq!(
            parse_outbound(br#"{"kind":"EMIT_ACK","emitSeq":1,"status":"LOCAL_DURABLE"}"#),
            Err(FrameError::UnknownKind("EMIT_ACK".into()))
        );
        assert!(matches!(
            parse_outbound(br#"{"kind":"EMIT","emitSeq":1,"data":1,"seq":2}"#),
            Err(FrameError::Invalid { .. })
        ));
        assert_eq!(
            parse_outbound(&vec![b' '; MAX_FRAME_BYTES + 1]),
            Err(FrameError::TooLong)
        );
    }

    #[test]
    fn stdin_frames_carry_their_constant_kind() {
        let line = encode_inbound(&Inbound::emit_ack(
            7,
            TriggerEmitStatus::LocalDurable,
            None,
            Some(41),
        ));
        assert_eq!(
            line,
            "{\"kind\":\"EMIT_ACK\",\"emitSeq\":7,\"status\":\"LOCAL_DURABLE\",\"outboxSeq\":41}\n"
        );
        for frame in [
            Inbound::accepted_up_to(2),
            Inbound::checkpoint_ack(1, TriggerCheckpointLevel::Accepted),
            Inbound::reconcile(),
            Inbound::pause(),
            Inbound::resume(),
            Inbound::stop("Removed", 10_000),
        ] {
            let line = encode_inbound(&frame);
            assert_eq!(parse_inbound(line.trim_end().as_bytes()).unwrap(), frame);
        }
    }
}
