// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Device-authenticated leased calls use installed local definitions and fresh server authorization.
use crate::{
    config::Config,
    execution::{self, AuthorizationPort},
    transport::{Call, ControlPort},
    Failure, Result,
};
use olo_toolgate_contracts::*;
use std::{collections::BTreeMap, sync::Arc};
type Output = BTreeMap<String, serde_json::Value>;
struct Authorization {
    control: Arc<dyn ControlPort>,
    identity: DeviceIdentity,
    task: RemoteToolTask,
    consumed: tokio::sync::Mutex<bool>,
    /// The fencing token permit consumption returned; run events are bound to it.
    fencing: std::sync::Mutex<Option<String>>,
    /// This device's span of the call, minted once: the tool's trace parent and the Insights execution span.
    child: Option<String>,
}
impl AuthorizationPort for Authorization {
    fn authorize(&self, request: AuthorizationRequest) -> Call<'_, ()> {
        Box::pin(async move { self.authorize_bound(request).await.map(|_| ()) })
    }
    fn trace(&self) -> crate::trace::RunTrace {
        let fencing = self.fencing.lock().ok().and_then(|f| f.clone());
        crate::trace::run_trace(
            self.child.clone(),
            self.task.execution_context.as_ref(),
            &self.task.invocation.evaluation.context.tenant_id,
            &self.task.invocation.id,
            fencing.as_deref(),
        )
    }
    fn authorize_bound(&self, request: AuthorizationRequest) -> Call<'_, Option<u64>> {
        Box::pin(async move {
            if request != self.task.request {
                return Err(Failure::Unauthorized);
            }
            let mut consumed = self.consumed.lock().await;
            if !*consumed {
                let e = &self.task.invocation.evaluation;
                let invocation = self
                    .control
                    .consume_effect(
                        self.identity.clone(),
                        EnterprisePermitConsumption {
                            invocation_id: self.task.invocation.id.clone(),
                            permit: self.task.permit.clone(),
                            arguments_digest: e.arguments_digest.clone(),
                            resources: e.resources.clone(),
                            tool_digest: e.tool_digest.clone(),
                            package_digest: e.package_digest.clone(),
                        },
                    )
                    .await?;
                if invocation.id != self.task.invocation.id
                    || invocation.evaluation != self.task.invocation.evaluation
                    || invocation.state != EnterpriseInvocationState::Executing
                {
                    return Err(Failure::Unauthorized);
                }
                *consumed = true;
                if let Ok(mut fencing) = self.fencing.lock() {
                    *fencing = invocation.reserved_nonce.clone();
                }
            }
            let ack = self
                .control
                .remote_authorize(
                    self.identity.clone(),
                    RemoteToolAuthorization {
                        request_id: self.task.request_id.clone(),
                        lease_id: self.task.lease_id.clone(),
                        request,
                    },
                )
                .await?;
            if ack.expires_at_unix_ms != self.task.expires_at_unix_ms
                || ack.expires_at_unix_ms <= crate::now()
            {
                return Err(Failure::Unauthorized);
            }
            Ok(Some(ack.expires_at_unix_ms))
        })
    }
}

async fn execute(
    _config: Config,
    settings: Option<execution::Settings>,
    auth: Arc<Authorization>,
    mut cancelled: tokio::sync::watch::Receiver<bool>,
) -> Result<Output> {
    let task = auth.task.clone();
    if *cancelled.borrow() || crate::now() >= task.expires_at_unix_ms {
        return Err(Failure::Expired);
    }
    let valid_until = task.expires_at_unix_ms;
    // Only a signed managed registration is executable; anything else is refused, never served another way.
    let mut settings = settings.ok_or(Failure::Unauthorized)?;
    let registration = settings
        .tools
        .iter()
        .find(|t| t.tool_id == task.request.tool_id && t.action == task.request.action)
        .ok_or(Failure::Unauthorized)?;
    let runtime = settings
        .runtimes
        .iter()
        .find(|r| r.id == registration.runtime_id)
        .ok_or(Failure::Unauthorized)?;
    let installed = crate::authorization_profile::managed_info(registration, runtime)?;
    if installed.tool_digest != task.invocation.evaluation.tool_digest
        || installed.package_digest != task.invocation.evaluation.package_digest
    {
        return Err(Failure::Unauthorized);
    }
    settings.state_directory = settings.state_directory.join("remote");
    let mut manager = execution::Manager::new(settings, auth.clone())?;
    let input = LocalToolInput {
        protocol_version: 1,
        request_id: task.request_id,
        tool_id: task.request.tool_id,
        arguments: task.request.arguments,
    };
    let started = crate::now();
    let result = tokio::select! {
        _=cancelled.changed()=>Err(Failure::Revoked),
        outcome=tokio::time::timeout(std::time::Duration::from_millis(valid_until.saturating_sub(crate::now())),manager.invoke(input,valid_until))=>outcome.unwrap_or(Err(Failure::Expired)).map(|output|output.output),
    };
    let (frames, dropped) = manager.take_frames();
    manager.shutdown().await?;
    forward_frames(&auth, frames, dropped).await;
    forward_span(&auth, started, result.is_ok()).await;
    result
}

/// DETAILED Insights: the run's execution span, when Control asked for spans. Best-effort, after the frames.
async fn forward_span(auth: &Authorization, started: u64, ok: bool) {
    let (Some(true), Some(child)) = (auth.task.collect_spans, auth.child.as_deref()) else {
        return;
    };
    let execution = crate::execution::AuthorizationPort::trace(auth)
        .context
        .and_then(|c| c.execution_id);
    let span = crate::trace::execution_span(
        auth.task.traceparent.as_deref(),
        child,
        &auth.task.invocation.id,
        execution,
        started,
        crate::now(),
        ok,
    );
    let batch = olo_toolgate_contracts::v2::InsightsSpanBatch {
        spans: vec![span],
        dropped_spans: None,
    };
    if let Err(failure) = auth
        .control
        .insights_spans(
            auth.identity.clone(),
            auth.task.invocation.id.clone(),
            batch,
        )
        .await
    {
        tracing::debug!(event = "insights_spans", result = "not_delivered", error = ?failure);
    }
}

/// Sends the run's response frames to Control before the result, once retried; the result never waits on a failure.
async fn forward_frames(
    auth: &Authorization,
    events: Vec<olo_toolgate_contracts::v2::RunEventFrame>,
    dropped: u64,
) {
    let fencing = auth.fencing.lock().ok().and_then(|f| f.clone());
    let Some(fencing_token) = fencing else { return };
    if events.is_empty() && dropped == 0 {
        return;
    }
    let batch = olo_toolgate_contracts::v2::RunEventBatch {
        invocation_id: auth.task.invocation.id.clone(),
        fencing_token,
        attempt: 1,
        events,
        dropped_frames: (dropped > 0).then_some(dropped.min(i32::MAX as u64)),
    };
    for _ in 0..2 {
        match auth
            .control
            .run_events(auth.identity.clone(), batch.clone())
            .await
        {
            Ok(_) => return,
            Err(failure) => {
                tracing::warn!(event = "run_events", result = "not_delivered", error = ?failure)
            }
        }
    }
}

pub(crate) struct Job {
    pub task: RemoteToolTask,
    handle: Option<tokio::task::JoinHandle<Result<Output>>>,
    cancel: tokio::sync::watch::Sender<bool>,
}
impl Job {
    #[cfg(test)]
    pub(crate) fn waiting(task: RemoteToolTask) -> Self {
        let (cancel, mut receiver) = tokio::sync::watch::channel(false);
        let handle = tokio::spawn(async move {
            let _ = receiver.changed().await;
            Err(Failure::Revoked)
        });
        Self {
            task,
            handle: Some(handle),
            cancel,
        }
    }
    pub fn start(
        config: Config,
        settings: Option<execution::Settings>,
        control: Arc<dyn ControlPort>,
        identity: DeviceIdentity,
        task: RemoteToolTask,
        activity: Arc<crate::activity::Activity>,
    ) -> Self {
        let (cancel, receiver) = tokio::sync::watch::channel(false);
        let auth = Arc::new(Authorization {
            control,
            identity,
            task: task.clone(),
            consumed: tokio::sync::Mutex::new(false),
            fencing: std::sync::Mutex::new(None),
            child: crate::trace::child_traceparent(task.traceparent.as_deref()),
        });
        let command = activity.begin(&task.request.tool_id);
        let handle = tokio::spawn(async move {
            let result = execute(config, settings, auth, receiver).await;
            command.finish(result.is_ok());
            result
        });
        Self {
            task,
            handle: Some(handle),
            cancel,
        }
    }
    pub fn cancel(&self) {
        let _ = self.cancel.send(true);
    }
    pub async fn completed(&mut self) -> Option<RemoteToolResult> {
        if !self.handle.as_ref()?.is_finished() {
            return None;
        }
        let outcome = self
            .handle
            .take()?
            .await
            .unwrap_or(Err(Failure::Unavailable));
        let (output, error) = match outcome {
            Ok(output) => (Some(output), None),
            Err(failure) => (
                None,
                Some(match failure {
                    Failure::Unauthorized | Failure::Revoked => ErrorCode::Forbidden,
                    Failure::Expired => ErrorCode::Timeout,
                    Failure::Unsupported => ErrorCode::Unsupported,
                    _ => ErrorCode::Validation,
                }),
            ),
        };
        let mut result = RemoteToolResult {
            request_id: self.task.request_id.clone(),
            lease_id: self.task.lease_id.clone(),
            output,
            error,
        };
        // Fit Control's result budget before durable persistence and posting.
        // Oversized output must not become an indefinitely retried invalid result.
        if serde_json::to_vec(&result).map_or(true, |bytes| bytes.len() > 65536) {
            result.output = None;
            result.error = Some(ErrorCode::Validation);
        }
        Some(result)
    }
    pub async fn shutdown(&mut self) -> Result<()> {
        self.cancel();
        if let Some(mut handle) = self.handle.take() {
            if tokio::time::timeout(std::time::Duration::from_secs(10), &mut handle)
                .await
                .is_err()
            {
                handle.abort();
                let _ = handle.await;
                return Err(Failure::Unavailable);
            }
        }
        Ok(())
    }
}
impl Drop for Job {
    fn drop(&mut self) {
        self.cancel();
    }
}
