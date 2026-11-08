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
/// The lease is bounded by the consumed invocation's expiry, the same bound
/// `execution::accepted_native` enforces. A relayed task's shorter delivery
/// window bounds the run itself, not the server-issued lease.
pub(crate) async fn supervise(
    control: &dyn ControlPort,
    identity: DeviceIdentity,
    accepted: NativePermitConsumed,
) {
    let deadline = accepted.invocation.expires_at_unix_ms;
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
    struct Renewing(std::sync::atomic::AtomicUsize);
    impl ControlPort for Renewing {
        fn ping(
            &self,
            _: olo_toolgate_contracts::EndpointPresencePing,
        ) -> crate::transport::Call<'_, olo_toolgate_contracts::EndpointConnectionDirective>
        {
            Box::pin(async { Err(Failure::Unsupported) })
        }
        fn discovery(
            &self,
        ) -> crate::transport::Call<'_, olo_toolgate_contracts::v2::SignedTenantDiscovery> {
            Box::pin(async { Err(Failure::Unsupported) })
        }
        fn start(
            &self,
            _: olo_toolgate_contracts::EndpointEnrollmentStart,
        ) -> crate::transport::Call<'_, olo_toolgate_contracts::EndpointEnrollmentChallenge>
        {
            Box::pin(async { Err(Failure::Unsupported) })
        }
        fn poll(
            &self,
            _: olo_toolgate_contracts::EndpointEnrollmentPoll,
        ) -> crate::transport::Call<'_, olo_toolgate_contracts::EndpointEnrollmentResult> {
            Box::pin(async { Err(Failure::Unsupported) })
        }
        fn check_in(
            &self,
            _: DeviceIdentity,
            _: olo_toolgate_contracts::EndpointCheckIn,
        ) -> crate::transport::Call<'_, olo_toolgate_contracts::EndpointCheckInAck> {
            Box::pin(async { Err(Failure::Unsupported) })
        }
        fn heartbeat(
            &self,
            _: DeviceIdentity,
            request: LeaseHeartbeat,
        ) -> crate::transport::Call<'_, LeaseRenewal> {
            self.0.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Box::pin(async move {
                Ok(LeaseRenewal {
                    invocation_id: request.invocation_id,
                    fencing_token: request.fencing_token,
                    lease_expires_at_unix_ms: crate::now() + 300,
                    cancel_requested: false,
                    input_responses: None,
                })
            })
        }
    }
    fn consumed(
        invocation_expiry: u64,
        lease_expiry: u64,
    ) -> (DeviceIdentity, NativePermitConsumed) {
        let fixtures: serde_json::Value = serde_json::from_str(include_str!(
            "../../../tests/fixtures/contracts/v1/valid.json"
        ))
        .unwrap();
        let identity = serde_json::from_value(fixtures["DeviceIdentity"].clone()).unwrap();
        let mut invocation: olo_toolgate_contracts::EnterpriseInvocation =
            serde_json::from_value(fixtures["RemoteToolTask"]["invocation"].clone()).unwrap();
        invocation.expires_at_unix_ms = invocation_expiry;
        let consumed = NativePermitConsumed {
            fencing_token: "attempt".into(),
            lease_expires_at_unix_ms: lease_expiry,
            authorization_epoch: 0,
            kill_scope: olo_toolgate_contracts::v2::KillExecutionScope {
                package_id: None,
                package_digest: format!("sha256:{}", "0".repeat(64)),
                publisher: None,
                tool_id: invocation.evaluation.tool_id.clone(),
                pool_id: None,
                image_digest: None,
            },
            invocation,
        };
        (identity, consumed)
    }
    /// A relayed task's delivery window may end before the server-issued lease;
    /// only the consumed invocation's expiry bounds the lease, so supervision
    /// must keep renewing instead of reporting revocation before the run starts.
    #[tokio::test]
    async fn lease_is_bounded_by_the_consumed_invocation_not_a_shorter_window() {
        let control = Renewing(Default::default());
        let (identity, accepted) = consumed(crate::now() + 60000, crate::now() + 300);
        assert!(
            tokio::time::timeout(
                Duration::from_millis(500),
                supervise(&control, identity, accepted)
            )
            .await
            .is_err(),
            "a lease inside the invocation expiry stays supervised"
        );
        assert!(control.0.load(std::sync::atomic::Ordering::SeqCst) >= 1);
        let control = Renewing(Default::default());
        let (identity, accepted) = consumed(crate::now() + 1000, crate::now() + 5000);
        tokio::time::timeout(
            Duration::from_millis(100),
            supervise(&control, identity, accepted),
        )
        .await
        .expect("a lease beyond the invocation expiry ends at once");
        assert_eq!(control.0.load(std::sync::atomic::Ordering::SeqCst), 0);
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
