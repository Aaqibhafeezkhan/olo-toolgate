// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Native healthy-host authority has its own monotonic expiry, independent of check-in scheduling.
use crate::{transport::ControlPort, Failure, Result};
use olo_toolgate_contracts::{
    v2::{LeaseHeartbeat, LeaseRenewal, NativePermitConsumed},
    DeviceIdentity,
};
use std::{future::Future, time::Duration};
fn valid(reply: &LeaseRenewal, id: &str, fence: &str, deadline: u64) -> bool {
    reply.invocation_id == id
        && reply.fencing_token == fence
        && !reply.cancel_requested
        && reply.lease_expires_at_unix_ms > crate::now()
        && reply.lease_expires_at_unix_ms <= deadline
}
async fn supervise_with<F: Future<Output = Result<LeaseRenewal>>>(
    id: &str,
    fence: &str,
    deadline: u64,
    mut reply: LeaseRenewal,
    mut renew: impl FnMut() -> F,
) {
    loop {
        if !valid(&reply, id, fence, deadline) {
            return;
        }
        let remaining = reply.lease_expires_at_unix_ms.saturating_sub(crate::now());
        let expiry = tokio::time::Instant::now() + Duration::from_millis(remaining);
        let renewal = async {
            tokio::time::sleep(Duration::from_millis((remaining / 3).clamp(1, 10000))).await;
            renew().await
        };
        match tokio::time::timeout_at(expiry, renewal).await {
            Ok(Ok(next)) => reply = next,
            Ok(Err(Failure::Unavailable)) => {
                tokio::time::sleep_until(expiry).await;
                return;
            }
            _ => return,
        }
    }
}
pub(crate) async fn supervise(
    control: &dyn ControlPort,
    identity: DeviceIdentity,
    accepted: NativePermitConsumed,
    deadline: u64,
) {
    let id = accepted.invocation.id;
    let fence = accepted.fencing_token;
    let initial = LeaseRenewal {
        invocation_id: id.clone(),
        fencing_token: fence.clone(),
        lease_expires_at_unix_ms: accepted.lease_expires_at_unix_ms,
        cancel_requested: false,
        input_responses: None,
    };
    supervise_with(&id, &fence, deadline, initial, || {
        control.heartbeat(
            identity.clone(),
            LeaseHeartbeat {
                invocation_id: id.clone(),
                fencing_token: fence.clone(),
                progress: None,
                input_request: None,
            },
        )
    })
    .await;
}
#[cfg(test)]
mod tests {
    use super::*;
    fn reply(expiry: u64) -> LeaseRenewal {
        LeaseRenewal {
            invocation_id: "operation".into(),
            fencing_token: "attempt".into(),
            lease_expires_at_unix_ms: expiry,
            cancel_requested: false,
            input_responses: None,
        }
    }
    #[tokio::test]
    async fn malformed_cancelled_and_expired_authority_never_requests_a_renewal() {
        for mut bad in [reply(0), reply(crate::now() + 1000)] {
            if bad.lease_expires_at_unix_ms != 0 {
                bad.cancel_requested = true;
            }
            supervise_with("operation", "attempt", crate::now() + 2000, bad, || async {
                panic!("invalid lease renewed")
            })
            .await;
        }
        let good = reply(crate::now() + 1000);
        assert!(!valid(
            &good,
            "other",
            "attempt",
            good.lease_expires_at_unix_ms
        ));
        assert!(!valid(
            &good,
            "operation",
            "other",
            good.lease_expires_at_unix_ms
        ));
        assert!(!valid(
            &good,
            "operation",
            "attempt",
            good.lease_expires_at_unix_ms - 1
        ));
    }
    #[tokio::test]
    async fn unavailable_or_hung_heartbeat_cannot_extend_the_original_monotonic_lease() {
        for hang in [false, true] {
            let start = tokio::time::Instant::now();
            let expiry = crate::now() + 240;
            tokio::time::timeout(
                Duration::from_secs(3),
                supervise_with(
                    "operation",
                    "attempt",
                    expiry + 1000,
                    reply(expiry),
                    || async move {
                        if hang {
                            std::future::pending::<Result<LeaseRenewal>>().await
                        } else {
                            Err(Failure::Unavailable)
                        }
                    },
                ),
            )
            .await
            .unwrap();
            assert!(start.elapsed() >= Duration::from_millis(180));
        }
    }
    #[tokio::test]
    async fn explicit_cancellation_ends_a_live_lease_without_waiting_for_its_deadline() {
        let expiry = crate::now() + 600;
        let mut cancelled = reply(expiry);
        cancelled.cancel_requested = true;
        tokio::time::timeout(
            Duration::from_millis(500),
            supervise_with("operation", "attempt", expiry, reply(expiry), || async {
                Ok(cancelled.clone())
            }),
        )
        .await
        .unwrap();
    }
}
