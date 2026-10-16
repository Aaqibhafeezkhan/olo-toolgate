// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Device-authenticated leased calls use installed local definitions and fresh server authorization.
use crate::{
    builtins::AuthorizationPort,
    config::Config,
    execution,
    transport::{Call, ControlPort},
    Failure, Result,
};
use olo_toolgate_contracts::*;
use std::{collections::BTreeMap, sync::Arc};
type Output = BTreeMap<String, serde_json::Value>;
pub(crate) fn builtin_settings(config: &Config) -> crate::builtins::Settings {
    crate::builtins::Settings {
        hotfolder: crate::hotfolder::Settings {
            root: config.state_directory.join("hotfolder"),
            max_file_bytes: 65536,
            max_entries: 256,
            extensions: vec!["txt".into(), "md".into(), "json".into(), "csv".into()],
        },
        gateway_url: config.server_url.clone(),
        gateway_token_path: config.state_directory.join("unused-remote-token"),
        gateway_ca_path: None,
        device_id: "remote".into(),
        authorization_profiles: vec![],
        web_search_token_path: None,
        web_search_allowed_domains: vec![],
    }
}

struct Authorization {
    control: Arc<dyn ControlPort>,
    identity: DeviceIdentity,
    task: RemoteToolTask,
    consumed: tokio::sync::Mutex<bool>,
}
impl AuthorizationPort for Authorization {
    fn secret(&self, name: String) -> Call<'_, String> {
        Box::pin(async move {
            if !*self.consumed.lock().await {
                return Err(Failure::Unauthorized);
            }
            Ok(self
                .control
                .deliver_secret(
                    self.identity.clone(),
                    EnterpriseSecretDeliveryRequest {
                        invocation_id: self.task.invocation.id.clone(),
                        name,
                    },
                )
                .await?
                .value)
        })
    }
    fn authorize(&self, request: AuthorizationRequest) -> Call<'_, ()> {
        Box::pin(async move { self.authorize_bound(request).await.map(|_| ()) })
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
    config: Config,
    settings: Option<execution::Settings>,
    auth: Arc<Authorization>,
    mut cancelled: tokio::sync::watch::Receiver<bool>,
) -> Result<Output> {
    let task = auth.task.clone();
    if *cancelled.borrow() || crate::now() >= task.expires_at_unix_ms {
        return Err(Failure::Expired);
    }
    let valid_until = task.expires_at_unix_ms;
    if let Some(mut settings) = settings.filter(|s| {
        s.tools
            .iter()
            .any(|t| t.tool_id == task.request.tool_id && t.action == task.request.action)
    }) {
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
        let mut manager = execution::Manager::new(settings, auth)?;
        let input = LocalToolInput {
            protocol_version: 1,
            request_id: task.request_id,
            tool_id: task.request.tool_id,
            arguments: task.request.arguments,
        };
        let result = tokio::select! {
            _=cancelled.changed()=>Err(Failure::Revoked),
            outcome=tokio::time::timeout(std::time::Duration::from_millis(valid_until.saturating_sub(crate::now())),manager.invoke(input,valid_until))=>outcome.unwrap_or(Err(Failure::Expired)).map(|output|output.output),
        };
        manager.shutdown().await?;
        result
    } else {
        let settings = config
            .tools
            .clone()
            .unwrap_or_else(|| builtin_settings(&config));
        let mut executor = crate::builtins::Executor::new(&settings, auth)?.with_packet_log(
            crate::diagnostics::PacketLog::open(config.state_directory.clone())?,
        );
        if !executor.catalog().iter().any(|t| {
            t.enabled
                && t.tool_id == task.request.tool_id
                && t.action == task.request.action
                && t.tool_digest == task.invocation.evaluation.tool_digest
                && t.package_digest == task.invocation.evaluation.package_digest
        }) {
            return Err(Failure::Unauthorized);
        }
        let input = BuiltinInvocation {
            tool_id: task.request.tool_id,
            arguments: task.request.arguments,
        };
        tokio::select! {
            _=cancelled.changed()=>Err(Failure::Revoked),
            outcome=tokio::time::timeout(std::time::Duration::from_millis(valid_until.saturating_sub(crate::now())),executor.execute(input,valid_until))=>outcome.unwrap_or(Err(Failure::Expired)),
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
