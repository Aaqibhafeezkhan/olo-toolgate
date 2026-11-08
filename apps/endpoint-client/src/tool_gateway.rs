// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Administrator-configured HTTPS Gateway. ASK grants are consumed online before execution.
use crate::{contracts::Contracts, execution::AuthorizationPort, transport::Call, Failure, Result};
use olo_toolgate_contracts::*;
use std::{path::Path, sync::Arc, time::Duration};
pub fn secret(path: &Path) -> Result<String> {
    let bytes = crate::storage::read_owned(path, 4096, true)?;
    let value = String::from_utf8(bytes).map_err(|_| Failure::Validation)?;
    let value = value.trim();
    if value.is_empty() || value.len() > 2048 || !value.bytes().all(|b| b.is_ascii_graphic()) {
        return Err(Failure::Validation);
    }
    Ok(value.to_owned())
}
pub fn client(ca: Option<&Path>) -> Result<reqwest::Client> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let mut builder = reqwest::Client::builder()
        .https_only(true)
        .redirect(reqwest::redirect::Policy::none())
        .no_proxy()
        .tls_sslkeylogfile(false)
        .referer(false)
        .retry(reqwest::retry::never())
        .timeout(Duration::from_secs(5))
        .connect_timeout(Duration::from_secs(3))
        .pool_max_idle_per_host(1);
    if let Some(path) = ca {
        builder = builder.tls_certs_only([reqwest::Certificate::from_pem(
            &crate::storage::read_owned(path, 16384, false)?,
        )
        .map_err(|_| Failure::Validation)?]);
    }
    builder.build().map_err(|_| Failure::Unavailable)
}
pub async fn body(mut response: reqwest::Response) -> Result<Vec<u8>> {
    if response.status() != 200 {
        return Err(if matches!(response.status().as_u16(), 401 | 403) {
            Failure::Unauthorized
        } else {
            Failure::Unavailable
        });
    }
    if response.content_length().is_some_and(|n| n > 131072)
        || response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .is_none_or(|v| v.split(';').next() != Some("application/json"))
    {
        return Err(Failure::Validation);
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| Failure::Unavailable)? {
        if bytes.len() + chunk.len() > 131072 {
            return Err(Failure::Validation);
        }
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}
/// The Gateway this device authorizes local and managed tool calls with.
#[derive(Clone, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Settings {
    pub gateway_url: String,
    pub gateway_token_path: std::path::PathBuf,
    pub gateway_ca_path: Option<std::path::PathBuf>,
    pub device_id: String,
}
impl Settings {
    pub fn validate(&self) -> Result<()> {
        crate::config::origin(&self.gateway_url)?;
        if !self.gateway_token_path.is_absolute()
            || self.device_id.is_empty()
            || self.device_id.len() > 128
            || self
                .gateway_ca_path
                .as_ref()
                .is_some_and(|p| !p.is_absolute())
        {
            return Err(Failure::Validation);
        }
        Ok(())
    }
}
pub struct HttpsGateway {
    client: reqwest::Client,
    origin: String,
    token: String,
    contracts: Arc<Contracts>,
    config: crate::config::Config,
    settings: Settings,
    executing: tokio::sync::Mutex<Option<EnterpriseInvocation>>,
    authority: std::sync::Mutex<Option<olo_toolgate_contracts::v2::NativePermitConsumed>>,
    kill_execution: std::sync::Mutex<Option<olo_toolgate_tenant_identity::revocation::Execution>>,
    revocations: Arc<crate::revocation::Shared>,
}
impl HttpsGateway {
    pub async fn catalog(&self, agent: Option<&str>) -> Result<LocalToolCatalog> {
        self.device()?;
        let query = agent
            .map(|id| serde_json::json!({"agentId":id}))
            .unwrap_or_else(|| serde_json::json!({}));
        let response = self
            .client
            .post(format!("{}/access/catalog", self.origin))
            .bearer_auth(&self.token)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .body(serde_json::to_vec(&query).map_err(|_| Failure::Validation)?)
            .send()
            .await
            .map_err(|_| Failure::Unavailable)?;
        self.contracts
            .decode("LocalToolCatalog", &body(response).await?)
    }
    pub(crate) fn new(
        settings: &Settings,
        config: &crate::config::Config,
        contracts: Arc<Contracts>,
        revocations: Arc<crate::revocation::Shared>,
    ) -> Result<Self> {
        settings.validate()?;
        Ok(Self {
            client: client(settings.gateway_ca_path.as_deref())?,
            origin: crate::config::origin(&settings.gateway_url)?,
            token: secret(&settings.gateway_token_path)?,
            contracts,
            config: config.clone(),
            settings: settings.clone(),
            executing: tokio::sync::Mutex::new(None),
            authority: std::sync::Mutex::new(None),
            kill_execution: std::sync::Mutex::new(None),
            revocations,
        })
    }
    fn device(
        &self,
    ) -> Result<(
        DeviceIdentity,
        crate::transport::HttpsControl,
        crate::storage::ProtectedStore,
    )> {
        let store = crate::storage::ProtectedStore::open(self.config.state_directory.clone())?;
        crate::service::validate_saved_binding(&self.config, &store)?;
        if store.read("device-key")?.is_none() {
            return Err(Failure::Unauthorized);
        }
        let key = Arc::new(crate::identity::DeviceKey::load_or_create(&store)?);
        let bytes = store.read("journal.json")?.ok_or(Failure::Unauthorized)?;
        let journal: serde_json::Value =
            serde_json::from_slice(&bytes).map_err(|_| Failure::Validation)?;
        if journal["revoked"] != false {
            return Err(Failure::Unauthorized);
        }
        let identity: DeviceIdentity = serde_json::from_value(journal["identity"].clone())
            .map_err(|_| Failure::Unauthorized)?;
        let manifest: olo_toolgate_contracts::v2::TenantDiscovery =
            serde_json::from_value(journal["manifest"].clone())
                .map_err(|_| Failure::Unauthorized)?;
        if crate::config::origin(&manifest.control_url)?
            != crate::config::origin(&self.config.server_url)?
            || identity.device_id != self.settings.device_id
        {
            return Err(Failure::Unauthorized);
        }
        crate::identity::verify_tenant_identity(
            &identity,
            &manifest,
            &key,
            &identity.device_id,
            crate::now(),
        )?;
        let control =
            crate::transport::HttpsControl::new(self.config.clone(), key, self.contracts.clone())?;
        crate::transport::ControlPort::bind_tenant(&control, &identity.tenant_id)?;
        Ok((identity, control, store))
    }
    /// The signed managed registration for this request; nothing else is executable on a device.
    fn installed(&self, request: &AuthorizationRequest) -> Result<LocalToolInfo> {
        let execution = self
            .config
            .execution
            .as_ref()
            .ok_or(Failure::Unauthorized)?;
        let tool = execution
            .tools
            .iter()
            .find(|t| t.tool_id == request.tool_id && t.action == request.action)
            .ok_or(Failure::Unauthorized)?;
        let runtime = execution
            .runtimes
            .iter()
            .find(|r| r.id == tool.runtime_id)
            .ok_or(Failure::Validation)?;
        crate::authorization_profile::managed_info(tool, runtime)
    }
}
impl AuthorizationPort for HttpsGateway {
    fn revoked(&self) -> Call<'_, ()> {
        Box::pin(async {
            let accepted = self
                .authority
                .lock()
                .map_err(|_| Failure::Unavailable)?
                .clone()
                .ok_or(Failure::Unauthorized)?;
            let (identity, control, _) = self.device()?;
            let execution = self
                .kill_execution
                .lock()
                .map_err(|_| Failure::Unavailable)?
                .clone()
                .ok_or(Failure::Unauthorized)?;
            tokio::select! {
                _=crate::lease::supervise(&control,identity,accepted)=>{},
                _=self.revocations.terminated(&execution)=>{},
            }
            Err(Failure::Revoked)
        })
    }
    fn authorize(&self, request: AuthorizationRequest) -> Call<'_, ()> {
        Box::pin(async move { self.authorize_bound(request).await.map(|_| ()) })
    }
    fn authorize_bound(&self, request: AuthorizationRequest) -> Call<'_, u64> {
        Box::pin(async move {
            use crate::transport::ControlPort;
            let mut executing = self.executing.lock().await;
            if executing.is_some() {
                return Err(Failure::Conflict);
            }
            let (identity, control, store) = self.device()?;
            if let Some(bytes) = store.read("effect-journal.json")? {
                let previous: EnterpriseInvocation =
                    self.contracts.decode("EnterpriseInvocation", &bytes)?;
                if matches!(
                    previous.state,
                    EnterpriseInvocationState::Reserved
                        | EnterpriseInvocationState::Executing
                        | EnterpriseInvocationState::OutcomeUnknown
                ) {
                    let recovered = control
                        .effect_outcome(identity.clone(), previous.id.clone())
                        .await?;
                    if !recoverable_outcome(&previous, &recovered) {
                        return Err(Failure::Conflict);
                    }
                    store.write(
                        "effect-journal.json",
                        &self.contracts.encode("EnterpriseInvocation", &recovered)?,
                    )?;
                }
            }
            let installed = self.installed(&request)?;
            let correlation = crate::identity::nonce()?;
            let response = self
                .client
                .post(format!("{}/access/invocations", self.origin))
                .bearer_auth(&self.token)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", &correlation)
                .body(self.contracts.encode("AuthorizationRequest", &request)?)
                .send()
                .await
                .map_err(|_| Failure::Unavailable)?;
            let outcome: EnterpriseAuthorizationOutcome = self
                .contracts
                .decode("EnterpriseAuthorizationOutcome", &body(response).await?)?;
            let reservation = outcome.reservation.ok_or(Failure::Unauthorized)?;
            let i = reservation.invocation;
            let e = &i.evaluation;
            if i != outcome.invocation
                || i.state != EnterpriseInvocationState::Reserved
                || e.context.device_id != identity.device_id
                || e.context.tenant_id != identity.tenant_id
                || e.tool_id != request.tool_id
                || e.action != request.action
                || e.arguments_digest
                    != crate::digest(
                        &serde_json::to_vec(&request.arguments).map_err(|_| Failure::Validation)?,
                    )
                || e.tool_digest != installed.tool_digest
                || e.package_digest != installed.package_digest
            {
                return Err(Failure::Unauthorized);
            }
            // Persist BEFORE consuming. An ambiguous transport failure cannot cause an effect retry.
            store.write(
                "effect-journal.json",
                &self.contracts.encode("EnterpriseInvocation", &i)?,
            )?;
            let execution =
                self.revocations
                    .register(olo_toolgate_tenant_identity::revocation::Subject {
                        package_id: None,
                        package_digest: Some(e.package_digest.clone()),
                        publisher: None,
                        tool_id: Some(e.tool_id.clone()),
                        pool_id: None,
                        image_digest: None,
                    })?;
            let accepted = control
                .consume_effect(
                    identity,
                    EnterprisePermitConsumption {
                        invocation_id: i.id.clone(),
                        permit: reservation.permit,
                        arguments_digest: e.arguments_digest.clone(),
                        resources: e.resources.clone(),
                        tool_digest: e.tool_digest.clone(),
                        package_digest: e.package_digest.clone(),
                    },
                )
                .await?;
            crate::execution::accepted_native(&accepted, &i)?;
            execution
                .accept(
                    accepted.authorization_epoch,
                    crate::revocation::subject(&accepted.kill_scope),
                )
                .map_err(|_| Failure::Unauthorized)?;
            *self
                .kill_execution
                .lock()
                .map_err(|_| Failure::Unavailable)? = Some(execution);
            *self.authority.lock().map_err(|_| Failure::Unavailable)? = Some(accepted.clone());
            let next = accepted.invocation;
            store.write(
                "effect-journal.json",
                &self.contracts.encode("EnterpriseInvocation", &next)?,
            )?;
            let until = next.expires_at_unix_ms;
            *executing = Some(next);
            Ok(until)
        })
    }
    fn complete(
        &self,
        request: AuthorizationRequest,
        output: std::collections::BTreeMap<String, serde_json::Value>,
    ) -> Call<'_, ()> {
        Box::pin(async move {
            use crate::transport::ControlPort;
            let mut executing = self.executing.lock().await;
            let i = executing.as_ref().ok_or(Failure::Unauthorized)?;
            if i.evaluation.arguments_digest
                != crate::digest(
                    &serde_json::to_vec(&request.arguments).map_err(|_| Failure::Validation)?,
                )
                || i.evaluation.tool_id != request.tool_id
                || i.evaluation.action != request.action
            {
                return Err(Failure::Unauthorized);
            }
            let (identity, control, store) = self.device()?;
            let next = control
                .report_effect(
                    identity,
                    EnterpriseEffectReport {
                        invocation_id: i.id.clone(),
                        expected_revision: i.revision,
                        state: EnterpriseInvocationState::Succeeded,
                        completed_resources: i.evaluation.resources.clone(),
                        result_digest: Some(crate::digest(
                            &serde_json::to_vec(&output).map_err(|_| Failure::Validation)?,
                        )),
                    },
                )
                .await?;
            if next.id != i.id || next.state != EnterpriseInvocationState::Succeeded {
                return Err(Failure::Unauthorized);
            }
            store.write(
                "effect-journal.json",
                &self.contracts.encode("EnterpriseInvocation", &next)?,
            )?;
            *executing = None;
            *self.authority.lock().map_err(|_| Failure::Unavailable)? = None;
            Ok(())
        })
    }
}

fn recoverable_outcome(previous: &EnterpriseInvocation, current: &EnterpriseInvocation) -> bool {
    current.id == previous.id
        && current.evaluation == previous.evaluation
        && current.request_digest == previous.request_digest
        && current.authorization_epoch == previous.authorization_epoch
        && current.revision >= previous.revision
        && matches!(
            current.state,
            EnterpriseInvocationState::Succeeded
                | EnterpriseInvocationState::Failed
                | EnterpriseInvocationState::Partial
                | EnterpriseInvocationState::Expired
                | EnterpriseInvocationState::Cancelled
        )
}
