// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Shared supervisor boundary for generic HTTP and the trusted native MCP executor.
use crate::{
    control::{Control, ControlError},
    egress::AddressPolicy,
};
use olo_toolgate_contracts::v2::{EffectGrantDispatch, EffectGrantIssue, ServiceDestination};
use std::sync::{atomic::AtomicBool, Arc};
#[cfg(unix)]
use std::{sync::atomic::Ordering, time::Duration};
#[cfg(unix)]
use tokio::time::Instant;

pub(crate) struct BoundedTransport {
    pub(crate) client: reqwest::Client,
    pub(crate) downstream_key: Option<olo_toolgate_contracts::v2::DownstreamEffectKey>,
    #[cfg(unix)]
    _window: crate::send_window::SendWindow,
}
fn refused() -> ControlError {
    ControlError::Refused(403)
}
#[cfg(unix)]
fn live(closed: &AtomicBool, deadline: u64) -> bool {
    !closed.load(Ordering::SeqCst) && crate::now_ms() < deadline
}

/// Caller fixes admission and target from trusted state, burns the owner locally and independently binds prepared bytes.
pub(crate) async fn authorize(
    control: &Control,
    request: &EffectGrantIssue,
    destination: &ServiceDestination,
    policy: AddressPolicy,
    deadline: u64,
    effect_started: &Arc<AtomicBool>,
    closed: &Arc<AtomicBool>,
) -> Result<BoundedTransport, ControlError> {
    #[cfg(not(unix))]
    {
        let _ = (
            control,
            request,
            destination,
            policy,
            deadline,
            effect_started,
            closed,
        );
        Err(refused())
    }
    #[cfg(unix)]
    {
        if !live(closed, deadline) {
            return Err(refused());
        }
        let started = Instant::now();
        let response_until =
            started + Duration::from_millis(deadline.saturating_sub(crate::now_ms()));
        let grant = control.effect_grant(request).await?;
        if grant.valid_for_ms == 0 || grant.valid_for_ms > 2000 {
            return Err(refused());
        }
        let send_until = (started + Duration::from_millis(grant.valid_for_ms)).min(response_until);
        if !live(closed, deadline) || Instant::now() >= send_until {
            return Err(refused());
        }
        let allowed = control
            .effect_sending(&EffectGrantDispatch {
                grant: grant.grant,
                broker_instance_id: request.broker_instance_id.clone(),
                handler_id: request.handler_id.clone(),
            })
            .await?;
        if !allowed.authorized {
            return Err(refused());
        }
        // An acknowledged SENDING decision is not an authoritative target outcome, even if later preparation fails.
        effect_started.store(true, Ordering::SeqCst);
        if !live(closed, deadline) || Instant::now() >= send_until {
            return Err(refused());
        }
        let window =
            crate::send_window::SendWindow::open(policy.clone(), send_until, response_until)
                .map_err(|_| refused())?;
        let client = crate::egress::client_with_socket(destination, policy, window.socket())
            .map_err(|_| refused())?;
        Ok(BoundedTransport {
            client,
            downstream_key: grant.downstream_key,
            _window: window,
        })
    }
}
