// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Healthy-host supervision. Loss of Control never extends authority.
use crate::control::{Control, ControlError};
use olo_toolgate_contracts::v2::LeaseRenewal;
use std::time::Duration;

fn valid(reply: &LeaseRenewal, id: &str, fence: &str, deadline: u64) -> bool {
    reply.invocation_id == id
        && reply.fencing_token == fence
        && !reply.cancel_requested
        && reply.lease_expires_at_unix_ms > crate::now_ms()
        && reply.lease_expires_at_unix_ms <= deadline
}

/// Returns only when authority has ended; the caller drops the running worker future.
pub async fn supervise(
    control: &Control,
    id: &str,
    fence: &str,
    deadline: u64,
    initial: LeaseRenewal,
) {
    let mut reply = initial;
    loop {
        if !valid(&reply, id, fence, deadline) {
            return;
        }
        let remaining = reply
            .lease_expires_at_unix_ms
            .saturating_sub(crate::now_ms());
        // Anchor to a monotonic timer before doing network IO. A hung renewal cannot
        // keep the worker alive, even if the system wall clock moves backwards.
        let expiry = tokio::time::Instant::now() + Duration::from_millis(remaining);
        let renew = async {
            tokio::time::sleep(Duration::from_millis((remaining / 3).clamp(1, 10_000))).await;
            control.heartbeat(id, fence).await
        };
        match tokio::time::timeout_at(expiry, renew).await {
            Ok(Ok(next)) => reply = next,
            Ok(Err(ControlError::Unavailable(_))) => {
                tokio::time::sleep_until(expiry).await;
                return;
            }
            _ => return,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn renewal_cannot_change_identity_or_extend_the_deadline() {
        let mut r = LeaseRenewal {
            invocation_id: "i".into(),
            fencing_token: "f".into(),
            lease_expires_at_unix_ms: crate::now_ms() + 1000,
            cancel_requested: false,
            input_responses: None,
        };
        let end = r.lease_expires_at_unix_ms;
        assert!(valid(&r, "i", "f", end));
        assert!(!valid(&r, "other", "f", end));
        assert!(!valid(&r, "i", "stale", end));
        assert!(!valid(&r, "i", "f", end - 1));
        r.cancel_requested = true;
        assert!(!valid(&r, "i", "f", end));
        r.cancel_requested = false;
        r.lease_expires_at_unix_ms = 0;
        assert!(!valid(&r, "i", "f", end));
    }
}
