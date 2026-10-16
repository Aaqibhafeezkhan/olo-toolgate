// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Forwards the SDK response frames of one admitted attempt to Control (plan.md §12). The runner offers each complete
//! stdout line; LOG, PROGRESS and INPUT_REQUEST frames that match the protocol get the next per-attempt sequence and are
//! posted in batches bound to the invocation and its fencing token. Resending a batch is idempotent at Control, so a
//! transient failure is retried with the same sequences. Frames beyond the bounds, malformed frames and frames that
//! could not be delivered are counted and reported as dropped, never silently lost. Frames grant nothing: the result
//! report stays the only outcome, and it is sent after the forwarder has finished.

use crate::control::{Control, ControlError};
use olo_toolgate_contracts::v2::{
    InputRequestFrame, LogFrame, ProgressFrame, RunEventBatch, RunEventFrame, RunEventKind,
};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;

/// Frames forwarded per attempt; Control retains at most as many events per invocation.
pub const MAX_FRAMES: u64 = 1000;
/// Frames per batch, matching the RunEventBatch contract.
const BATCH_FRAMES: usize = 64;
/// Serialized frame bytes per batch, below Control's request body limit.
const BATCH_BYTES: usize = 48 * 1024;
const QUEUE: usize = 256;
const RETRIES: [u64; 3] = [100, 400, 1600];

/// The runner's side: assigns sequences and enqueues without blocking the tool's output.
pub struct Sink {
    sender: mpsc::Sender<RunEventFrame>,
    next: AtomicU64,
    dropped: Arc<AtomicU64>,
}

/// The forwarder's side.
pub struct Source {
    receiver: mpsc::Receiver<RunEventFrame>,
    dropped: Arc<AtomicU64>,
}

pub fn channel() -> (Sink, Source) {
    let (sender, receiver) = mpsc::channel(QUEUE);
    let dropped = Arc::new(AtomicU64::new(0));
    (
        Sink {
            sender,
            next: AtomicU64::new(1),
            dropped: dropped.clone(),
        },
        Source { receiver, dropped },
    )
}

impl Sink {
    /// One complete stdout line. RESULT and blank lines are not response frames; anything else that is not a valid
    /// LOG, PROGRESS or INPUT_REQUEST frame is counted as dropped.
    pub fn offer(&self, line: &str) {
        let line = line.trim();
        if line.is_empty() {
            return;
        }
        let Ok(value) = serde_json::from_str::<serde_json::Value>(line) else {
            self.drop_one();
            return;
        };
        let frame = match value.get("kind").and_then(|k| k.as_str()) {
            Some("RESULT") => return,
            Some("LOG") => serde_json::from_value::<LogFrame>(value)
                .ok()
                .map(|f| RunEventFrame {
                    sequence: 0,
                    emitted_at_unix_ms: crate::now_ms(),
                    kind: RunEventKind::Log,
                    level: Some(f.level),
                    message: Some(f.message),
                    fraction: None,
                    request_id: None,
                    request: None,
                }),
            Some("PROGRESS") => serde_json::from_value::<ProgressFrame>(value)
                .ok()
                .map(|f| RunEventFrame {
                    sequence: 0,
                    emitted_at_unix_ms: crate::now_ms(),
                    kind: RunEventKind::Progress,
                    level: None,
                    message: f.message,
                    fraction: Some(f.fraction),
                    request_id: None,
                    request: None,
                }),
            Some("INPUT_REQUEST") => {
                serde_json::from_value::<InputRequestFrame>(value)
                    .ok()
                    .map(|f| RunEventFrame {
                        sequence: 0,
                        emitted_at_unix_ms: crate::now_ms(),
                        kind: RunEventKind::InputRequest,
                        level: None,
                        message: None,
                        fraction: None,
                        request_id: Some(f.request_id),
                        request: Some(f.request),
                    })
            }
            _ => None,
        };
        let Some(mut frame) = frame else {
            self.drop_one();
            return;
        };
        let sequence = self.next.fetch_add(1, Ordering::SeqCst);
        if sequence > MAX_FRAMES {
            self.drop_one();
            return;
        }
        frame.sequence = sequence;
        if self.sender.try_send(frame).is_err() {
            self.drop_one();
        }
    }

    fn drop_one(&self) {
        self.dropped.fetch_add(1, Ordering::SeqCst);
    }
}

/// Posts batches until the sink is dropped and the queue is empty.
pub async fn forward(
    control: Arc<Control>,
    invocation_id: String,
    fencing_token: String,
    attempt: u64,
    mut source: Source,
) {
    let mut reported_dropped = 0u64;
    let mut stopped = false;
    loop {
        let Some(first) = source.receiver.recv().await else {
            break;
        };
        let mut events = vec![first];
        let mut bytes = serde_json::to_vec(&events[0]).map_or(0, |b| b.len());
        while events.len() < BATCH_FRAMES && bytes < BATCH_BYTES {
            match source.receiver.try_recv() {
                Ok(frame) => {
                    bytes += serde_json::to_vec(&frame).map_or(0, |b| b.len());
                    events.push(frame);
                }
                Err(_) => break,
            }
        }
        if stopped {
            source
                .dropped
                .fetch_add(events.len() as u64, Ordering::SeqCst);
            continue;
        }
        let dropped = source.dropped.load(Ordering::SeqCst);
        let count = events.len() as u64;
        let batch = RunEventBatch {
            invocation_id: invocation_id.clone(),
            fencing_token: fencing_token.clone(),
            attempt,
            events,
            dropped_frames: Some(dropped - reported_dropped),
        };
        match send(&control, &batch).await {
            Ok(()) => reported_dropped = dropped,
            Err(ControlError::Refused(status)) => {
                // Control refused the binding (revoked, fenced or bounded): stop forwarding; the rest is dropped.
                tracing::info!(invocation_id = %invocation_id, status, "Control refused run events");
                stopped = true;
                source.dropped.fetch_add(count, Ordering::SeqCst);
            }
            Err(ControlError::Unavailable(why)) => {
                tracing::warn!(invocation_id = %invocation_id, why, "run events not delivered");
                source.dropped.fetch_add(count, Ordering::SeqCst);
            }
        }
    }
    let dropped = source.dropped.load(Ordering::SeqCst);
    if !stopped && dropped > reported_dropped {
        let batch = RunEventBatch {
            invocation_id: invocation_id.clone(),
            fencing_token,
            attempt,
            events: Vec::new(),
            dropped_frames: Some(dropped - reported_dropped),
        };
        if let Err(e) = send(&control, &batch).await {
            tracing::warn!(invocation_id = %invocation_id, error = %e, "dropped-frame count not delivered");
        }
    }
}

async fn send(control: &Control, batch: &RunEventBatch) -> Result<(), ControlError> {
    let mut last = ControlError::Unavailable("not sent".into());
    for delay in RETRIES {
        match control.events(batch).await {
            Ok(_) => return Ok(()),
            Err(refused @ ControlError::Refused(_)) => return Err(refused),
            Err(e) => last = e,
        }
        tokio::time::sleep(Duration::from_millis(delay)).await;
    }
    Err(last)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn drain(source: &mut Source) -> Vec<RunEventFrame> {
        let mut frames = Vec::new();
        while let Ok(frame) = source.receiver.try_recv() {
            frames.push(frame);
        }
        frames
    }

    #[test]
    fn frames_are_sequenced_and_invalid_lines_counted() {
        let (sink, mut source) = channel();
        sink.offer(r#"{"kind":"LOG","level":"info","message":"started"}"#);
        sink.offer(r#"{"kind":"PROGRESS","fraction":0.5,"message":"half"}"#);
        sink.offer("not json");
        sink.offer(r#"{"kind":"LOG","level":"info","message":"x","stdout":"extra"}"#);
        sink.offer(
            r#"{"kind":"INPUT_REQUEST","requestId":"approve-1","request":{"message":"Continue?"}}"#,
        );
        sink.offer(r#"{"kind":"RESULT","resultType":"complete","content":[],"isError":false}"#);
        sink.offer("   ");
        let frames = drain(&mut source);
        assert_eq!(
            frames.iter().map(|f| f.sequence).collect::<Vec<_>>(),
            vec![1, 2, 3]
        );
        assert_eq!(frames[0].kind, RunEventKind::Log);
        assert_eq!(frames[1].fraction, Some(0.5));
        assert_eq!(frames[2].request_id.as_deref(), Some("approve-1"));
        assert_eq!(source.dropped.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn frames_beyond_the_bound_are_dropped_not_queued() {
        let (sink, mut source) = channel();
        let mut forwarded = 0;
        for _ in 0..(MAX_FRAMES + 5) {
            sink.offer(r#"{"kind":"LOG","level":"debug","message":"tick"}"#);
            forwarded += drain(&mut source).len() as u64;
        }
        assert_eq!(forwarded, MAX_FRAMES);
        assert_eq!(source.dropped.load(Ordering::SeqCst), 5);
    }
}
