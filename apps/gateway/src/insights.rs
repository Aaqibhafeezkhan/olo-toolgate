// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Operator Insights admission counters (ADR 031 §5): requests the Gateway answered without starting an execution,
//! counted per closed minute, tool and category, and sent to Control in numbered delta batches. Best-effort: memory is
//! bounded, a batch that cannot be delivered is retried with its sequence, and anything dropped is reported so Control
//! can show the gap. Nothing here affects how a request is answered.
use crate::relay::RelayPort;
use axum::http::StatusCode;
use olo_toolgate_contracts::v2::{
    InsightsAdmissionBatch, InsightsAdmissionCategory, InsightsAdmissionCount, InsightsSpan,
    InsightsSpanBatch, InsightsSpanName, InsightsSpanStatus,
};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, VecDeque};
use std::sync::Mutex;

/// Distinct (minute, tool, category) counters held between flushes.
const MAX_KEYS: usize = 4096;
/// Undelivered batches kept for retry.
const MAX_PENDING: usize = 64;
const CATEGORIES: [InsightsAdmissionCategory; 6] = [
    InsightsAdmissionCategory::Unauthenticated,
    InsightsAdmissionCategory::PolicyDenied,
    InsightsAdmissionCategory::ApprovalRequired,
    InsightsAdmissionCategory::RateLimited,
    InsightsAdmissionCategory::KillSwitch,
    InsightsAdmissionCategory::InvalidRequest,
];

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Settings {
    /// NONE, BASIC or DETAILED; `TOOLGATE_INSIGHTS_MODE` overrides it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mode: Option<String>,
    /// Names this Gateway in source-gap reports; defaults to its host name.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gateway_id: Option<String>,
}
impl Settings {
    pub fn validate(&self) -> Result<(), &'static str> {
        match self.mode.as_deref() {
            None | Some("NONE" | "BASIC" | "DETAILED") => {}
            _ => return Err("insights.mode must be NONE, BASIC or DETAILED"),
        }
        if self.gateway_id.as_deref().is_some_and(|id| {
            !crate::http::valid_declared_id(id) || id.contains('@') || id.contains('_')
        }) {
            return Err("insights.gatewayId must be an identifier");
        }
        Ok(())
    }
    pub fn enabled(&self) -> bool {
        matches!(self.mode.as_deref(), Some("BASIC" | "DETAILED"))
    }
    pub fn detailed(&self) -> bool {
        self.mode.as_deref() == Some("DETAILED")
    }
}

/// Spans held between flushes.
const MAX_SPANS: usize = 4096;

/// DETAILED Insights spans the Gateway records (ADR 031 §9): the request, the Control authorization and the wait for
/// the executor. Bounded and best-effort; what cannot be kept is counted and reported with the next batch.
pub struct Spans {
    enabled: bool,
    buffer: Mutex<(Vec<InsightsSpan>, u64)>,
}
impl Spans {
    pub fn new(settings: &Settings) -> Self {
        Self {
            enabled: settings.detailed(),
            buffer: Mutex::new((Vec::new(), 0)),
        }
    }
    pub fn enabled(&self) -> bool {
        self.enabled
    }
    /// Records one span of `trace` (a `traceparent`). `own` is the span ID; `parent` its parent's, if any.
    #[allow(clippy::too_many_arguments)]
    pub fn record(
        &self,
        name: InsightsSpanName,
        traceparent: &str,
        own: String,
        parent: Option<String>,
        invocation_id: &str,
        started_unix_ms: u64,
        ended_unix_ms: u64,
        ok: bool,
    ) {
        if !self.enabled || traceparent.len() != 55 {
            return;
        }
        let span = InsightsSpan {
            trace_id: traceparent[3..35].to_owned(),
            span_id: own,
            parent_span_id: parent,
            name,
            source: None,
            invocation_id: invocation_id.to_owned(),
            execution_id: None,
            start_unix_ms: started_unix_ms,
            duration_ms: ended_unix_ms
                .saturating_sub(started_unix_ms)
                .min(86_400_000),
            status: if ok {
                InsightsSpanStatus::Ok
            } else {
                InsightsSpanStatus::Error
            },
            route_id: None,
            http_status: None,
            error_code: None,
        };
        let Ok(mut buffer) = self.buffer.lock() else {
            return;
        };
        if buffer.0.len() < MAX_SPANS {
            buffer.0.push(span);
        } else {
            buffer.1 += 1;
        }
    }
    /// Sends what is buffered in batches of at most 512. A failed batch is dropped and counted, never retried: spans
    /// are diagnostics, and Control's own buffer bounds what it can use.
    pub async fn flush(&self, relay: &dyn RelayPort) {
        if !self.enabled {
            return;
        }
        let (spans, dropped) = match self.buffer.lock() {
            Ok(mut buffer) => (std::mem::take(&mut buffer.0), std::mem::take(&mut buffer.1)),
            Err(_) => return,
        };
        let mut dropped = Some(dropped).filter(|d| *d > 0);
        for chunk in spans.chunks(512) {
            let batch = InsightsSpanBatch {
                spans: chunk.to_vec(),
                dropped_spans: dropped.take(),
            };
            if relay.insights_spans(&batch).await.is_err() {
                if let Ok(mut buffer) = self.buffer.lock() {
                    buffer.1 += chunk.len() as u64;
                }
            }
        }
    }
    #[cfg(test)]
    fn held(&self) -> usize {
        self.buffer.lock().unwrap().0.len()
    }
}

#[derive(Default)]
struct State {
    /// Keyed by (minute, tool, category index into [`CATEGORIES`]).
    counts: BTreeMap<(u64, String, usize), u64>,
    pending: VecDeque<InsightsAdmissionBatch>,
    next_sequence: u64,
    dropped: u64,
}

pub struct Admissions {
    enabled: bool,
    gateway_id: String,
    epoch: String,
    state: Mutex<State>,
}

impl Admissions {
    pub fn new(settings: &Settings, epoch: &str) -> Self {
        let host = std::env::var("HOSTNAME").unwrap_or_default();
        let fallback: String = host
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-'))
            .take(64)
            .collect();
        let gateway_id = settings.gateway_id.clone().unwrap_or(
            if fallback
                .chars()
                .next()
                .is_some_and(|c| c.is_ascii_alphanumeric())
            {
                fallback
            } else {
                "gateway".into()
            },
        );
        Self {
            enabled: settings.enabled(),
            gateway_id,
            epoch: format!("e-{}", &epoch[..epoch.len().min(32)]),
            state: Mutex::new(State {
                next_sequence: 1,
                ..State::default()
            }),
        }
    }
    pub fn enabled(&self) -> bool {
        self.enabled
    }

    /// The category an answered request belongs to, from its status. Other statuses are not admissions decisions.
    pub fn category(status: StatusCode) -> Option<InsightsAdmissionCategory> {
        match status {
            StatusCode::UNAUTHORIZED => Some(InsightsAdmissionCategory::Unauthenticated),
            StatusCode::FORBIDDEN => Some(InsightsAdmissionCategory::PolicyDenied),
            StatusCode::TOO_MANY_REQUESTS => Some(InsightsAdmissionCategory::RateLimited),
            StatusCode::BAD_REQUEST
            | StatusCode::REQUEST_HEADER_FIELDS_TOO_LARGE
            | StatusCode::PAYLOAD_TOO_LARGE => Some(InsightsAdmissionCategory::InvalidRequest),
            _ => None,
        }
    }

    /// Counts one decision. A tool name that is not a plausible tool ID is counted without it.
    pub fn record(&self, now_ms: u64, tool: Option<&str>, category: InsightsAdmissionCategory) {
        if !self.enabled {
            return;
        }
        let tool = tool
            .filter(|t| !t.is_empty() && t.len() <= 256 && t.bytes().all(|b| b.is_ascii_graphic()))
            .unwrap_or("")
            .to_owned();
        let Some(index) = CATEGORIES.iter().position(|c| *c == category) else {
            return;
        };
        let key = (now_ms - now_ms % 60_000, tool, index);
        let Ok(mut state) = self.state.lock() else {
            return;
        };
        if let Some(count) = state.counts.get_mut(&key) {
            *count += 1;
        } else if state.counts.len() < MAX_KEYS {
            state.counts.insert(key, 1);
        } else {
            state.dropped += 1;
        }
    }

    /// Closes the minutes before `now_ms` (all of them when `last`) into the next numbered batch.
    fn close(&self, now_ms: u64, last: bool) {
        let Ok(mut state) = self.state.lock() else {
            return;
        };
        let current = now_ms - now_ms % 60_000;
        let closed: Vec<_> = state
            .counts
            .keys()
            .filter(|(bucket, _, _)| last || *bucket < current)
            .cloned()
            .collect();
        if closed.is_empty() && state.dropped == 0 && !last {
            return;
        }
        let mut counts = Vec::with_capacity(closed.len());
        for key in closed {
            let count = state.counts.remove(&key).unwrap_or(0);
            counts.push(InsightsAdmissionCount {
                bucket_start_unix_ms: key.0,
                tool_id: (!key.1.is_empty()).then(|| key.1.clone()),
                category: CATEGORIES[key.2].clone(),
                count,
            });
        }
        if state.pending.len() >= MAX_PENDING {
            if let Some(oldest) = state.pending.pop_front() {
                state.dropped += oldest.counts.iter().map(|c| c.count).sum::<u64>();
            }
        }
        let sequence = state.next_sequence;
        state.next_sequence += 1;
        let dropped = std::mem::take(&mut state.dropped);
        state.pending.push_back(InsightsAdmissionBatch {
            gateway_id: self.gateway_id.clone(),
            epoch: self.epoch.clone(),
            sequence,
            last_batch: last.then_some(true),
            dropped_requests: (dropped > 0).then_some(dropped),
            counts,
        });
    }

    /// Closes finished minutes and sends pending batches in order, stopping at the first failure so sequences stay
    /// in order. `last` sends everything, marked final, on graceful shutdown.
    pub async fn flush(&self, relay: &dyn RelayPort, now_ms: u64, last: bool) {
        if !self.enabled {
            return;
        }
        self.close(now_ms, last);
        loop {
            let next = match self.state.lock() {
                Ok(state) => state.pending.front().cloned(),
                Err(_) => return,
            };
            let Some(batch) = next else { return };
            match relay.insights_admissions(&batch).await {
                Ok(ack) if ack.sequence == batch.sequence => {
                    if let Ok(mut state) = self.state.lock() {
                        if state
                            .pending
                            .front()
                            .is_some_and(|b| b.sequence == batch.sequence)
                        {
                            state.pending.pop_front();
                        }
                    }
                }
                _ => return,
            }
        }
    }

    #[cfg(test)]
    fn pending(&self) -> Vec<InsightsAdmissionBatch> {
        self.state.lock().unwrap().pending.iter().cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::relay::PortFuture;
    use olo_toolgate_contracts::v2::InsightsAdmissionAck;
    use olo_toolgate_contracts::ErrorCode;
    use std::sync::atomic::{AtomicBool, Ordering};

    struct Control {
        up: AtomicBool,
        seen: Mutex<Vec<InsightsAdmissionBatch>>,
    }
    impl RelayPort for Control {
        fn invoke<'a>(
            &'a self,
            _: &'a olo_toolgate_contracts::EnterpriseInvocationRequest,
        ) -> PortFuture<'a, Result<olo_toolgate_contracts::EnterpriseInvocation, ErrorCode>>
        {
            Box::pin(async { Err(ErrorCode::Unsupported) })
        }
        fn reserve<'a>(
            &'a self,
            _: &'a olo_toolgate_contracts::EnterpriseReservationRequest,
        ) -> PortFuture<'a, Result<olo_toolgate_contracts::EnterpriseReservation, ErrorCode>>
        {
            Box::pin(async { Err(ErrorCode::Unsupported) })
        }
        fn ready(&self, _: u64) -> bool {
            true
        }
        fn health(&self) -> PortFuture<'_, Result<(), ErrorCode>> {
            Box::pin(async { Ok(()) })
        }
        fn catalog<'a>(
            &'a self,
            _: &'a olo_toolgate_contracts::v2::RequestContext,
        ) -> PortFuture<'a, Result<olo_toolgate_contracts::LocalToolCatalog, ErrorCode>> {
            Box::pin(async { Err(ErrorCode::Unsupported) })
        }
        fn submit<'a>(
            &'a self,
            _: &'a olo_toolgate_contracts::RemoteToolSubmission,
        ) -> PortFuture<'a, Result<olo_toolgate_contracts::RemoteToolResponse, ErrorCode>> {
            Box::pin(async { Err(ErrorCode::Unsupported) })
        }
        fn response<'a>(
            &'a self,
            _: &'a olo_toolgate_contracts::v2::RequestContext,
        ) -> PortFuture<'a, Result<olo_toolgate_contracts::RemoteToolResponse, ErrorCode>> {
            Box::pin(async { Err(ErrorCode::Unsupported) })
        }
        fn insights_admissions<'a>(
            &'a self,
            batch: &'a InsightsAdmissionBatch,
        ) -> PortFuture<'a, Result<InsightsAdmissionAck, ErrorCode>> {
            Box::pin(async move {
                if !self.up.load(Ordering::SeqCst) {
                    return Err(ErrorCode::DependencyUnavailable);
                }
                self.seen.lock().unwrap().push(batch.clone());
                Ok(InsightsAdmissionAck {
                    sequence: batch.sequence,
                    duplicate: false,
                })
            })
        }
    }
    fn admissions() -> Admissions {
        Admissions::new(
            &Settings {
                mode: Some("BASIC".into()),
                gateway_id: Some("gw-1".into()),
            },
            "0123456789abcdef",
        )
    }
    const T: u64 = 1_791_676_800_000;

    #[tokio::test]
    async fn closed_minutes_are_sent_in_numbered_batches_and_retried_in_order() {
        let a = admissions();
        let control = Control {
            up: AtomicBool::new(false),
            seen: Mutex::new(vec![]),
        };
        a.record(
            T + 5,
            Some("acme/x"),
            InsightsAdmissionCategory::PolicyDenied,
        );
        a.record(
            T + 6,
            Some("acme/x"),
            InsightsAdmissionCategory::PolicyDenied,
        );
        a.record(T + 7, None, InsightsAdmissionCategory::Unauthenticated);
        // The current minute stays open.
        a.flush(&control, T + 30_000, false).await;
        assert!(a.pending().is_empty());
        // Control is down: the batch waits with its sequence.
        a.flush(&control, T + 60_000, false).await;
        a.record(
            T + 61_000,
            Some("acme/y"),
            InsightsAdmissionCategory::RateLimited,
        );
        a.flush(&control, T + 120_000, false).await;
        assert_eq!(
            a.pending().iter().map(|b| b.sequence).collect::<Vec<_>>(),
            vec![1, 2]
        );
        control.up.store(true, Ordering::SeqCst);
        a.flush(&control, T + 130_000, true).await;
        let seen = control.seen.lock().unwrap();
        assert_eq!(
            seen.iter().map(|b| b.sequence).collect::<Vec<_>>(),
            vec![1, 2, 3]
        );
        assert_eq!(seen[0].counts.len(), 2);
        assert_eq!(seen[0].counts.iter().map(|c| c.count).sum::<u64>(), 3);
        assert_eq!(seen[2].last_batch, Some(true));
        assert!(seen
            .iter()
            .all(|b| b.epoch == "e-0123456789abcdef" && b.gateway_id == "gw-1"));
    }

    #[tokio::test]
    async fn bounded_memory_reports_what_it_drops() {
        let a = admissions();
        for i in 0..(MAX_KEYS as u64 + 10) {
            a.record(
                T,
                Some(&format!("tool-{i}")),
                InsightsAdmissionCategory::InvalidRequest,
            );
        }
        let control = Control {
            up: AtomicBool::new(true),
            seen: Mutex::new(vec![]),
        };
        a.flush(&control, T + 60_000, false).await;
        let seen = control.seen.lock().unwrap();
        assert_eq!(seen[0].counts.len(), MAX_KEYS);
        assert_eq!(seen[0].dropped_requests, Some(10));
    }

    #[test]
    fn spans_are_recorded_only_in_detailed_mode() {
        let detailed = Spans::new(&Settings {
            mode: Some("DETAILED".into()),
            gateway_id: None,
        });
        let basic = Spans::new(&Settings {
            mode: Some("BASIC".into()),
            gateway_id: None,
        });
        let parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        for spans in [&detailed, &basic] {
            spans.record(
                InsightsSpanName::GatewayRequest,
                parent,
                "b7ad6b7169203331".into(),
                None,
                "inv-1",
                10,
                40,
                true,
            );
        }
        assert_eq!(detailed.held(), 1);
        assert_eq!(basic.held(), 0);
    }

    #[test]
    fn off_records_nothing_and_categories_follow_status() {
        let off = Admissions::new(&Settings::default(), "e");
        off.record(T, None, InsightsAdmissionCategory::Unauthenticated);
        assert!(off.pending().is_empty() && !off.enabled());
        assert_eq!(
            Admissions::category(StatusCode::FORBIDDEN),
            Some(InsightsAdmissionCategory::PolicyDenied)
        );
        assert_eq!(Admissions::category(StatusCode::OK), None);
        assert!(Settings {
            mode: Some("FULL".into()),
            gateway_id: None
        }
        .validate()
        .is_err());
    }
}
