// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Durable identity/check-in state machine. Only this protected process owns network credentials.
use crate::{
    config::Config,
    identity::{verify_discovery, verify_identity, DeviceKey},
    storage::{ProtectedStore, ServiceLease},
    transport::ControlPort,
    Failure, Result,
};
use olo_toolgate_contracts::*;
use serde::{Deserialize, Serialize};
use std::{sync::Arc, time::Instant};

#[derive(Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Journal {
    format_version: u64,
    server_url: String,
    tenant_id: Option<String>,
    manifest: Option<ClientDiscovery>,
    challenge: Option<EndpointEnrollmentChallenge>,
    identity: Option<DeviceIdentity>,
    sequence: u64,
    last_success: Option<u64>,
    pending_report: Option<EndpointCheckIn>,
    revoked: bool,
    #[serde(default)]
    reenrollment: bool,
    #[serde(default)]
    observed_time: u64,
    /// Display name the gateway last sent; shown beside its URL on the device.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    server_name: Option<String>,
    /// The last report this gateway acknowledged said the owner had switched the device off.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    power_off_reported: bool,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RemoteJournal {
    server_url: String,
    request_id: String,
    lease_id: String,
    result: Option<RemoteToolResult>,
}

/// One instance per machine, protected by OS lease and serialized command handling.
pub struct ClientService {
    pub activity: Arc<crate::activity::Activity>,
    pub(crate) schedule_changed: Arc<tokio::sync::Notify>,
    /// Shared by every gateway connection on this device; the runtime installs the saved switch.
    pub(crate) power: Arc<crate::power::Power>,
    remote_job: Option<crate::remote::Job>,
    remote_result: Option<RemoteToolResult>,
    adoption: Option<EndpointAdoption>,
    deployment: crate::deployment::State,
    execution: Option<crate::execution::Manager>,
    store: ProtectedStore,
    _lease: ServiceLease,
    key: Arc<DeviceKey>,
    control: Arc<dyn ControlPort>,
    config: Config,
    journal: Journal,
    state: EndpointState,
    started: Instant,
    successes: u64,
    failures: u64,
    check_in_interval_ms: u64,
    observed: u64,
    boot_time: u64,
}
/// Local calls need the administrator-provisioned Gateway credential; without one the device still runs the calls
/// Control relays and authorizes (ADR 027), so signed releases work on devices with no local Gateway settings.
fn local_authorization(
    config: &crate::config::Config,
) -> Result<Arc<dyn crate::execution::AuthorizationPort>> {
    Ok(match &config.gateway {
        Some(gateway) => Arc::new(crate::tool_gateway::HttpsGateway::new(
            gateway,
            config,
            Arc::new(crate::contracts::Contracts::new()?),
        )?),
        None => Arc::new(crate::execution::RelayOnly),
    })
}

fn read_journal(config: &Config, store: &ProtectedStore) -> Result<Journal> {
    let journal: Journal = match store.read("journal.json")? {
        Some(bytes) => serde_json::from_slice(&bytes).map_err(|_| Failure::Validation)?,
        None => {
            if store.read("device-key")?.is_some() {
                return Err(Failure::Validation);
            }
            Journal {
                format_version: 2,
                server_url: crate::config::origin(&config.server_url)?,
                ..Journal::default()
            }
        }
    };
    if journal.format_version != 2 {
        return Err(Failure::Validation);
    }
    if journal.server_url != crate::config::origin(&config.server_url)? {
        return Err(Failure::TenantMismatch);
    }
    if let Some(tenant) = &journal.tenant_id {
        crate::contracts::Contracts::shared()?.encode("TenantUuid", tenant)?;
        if journal
            .manifest
            .as_ref()
            .is_none_or(|manifest| &manifest.tenant_id != tenant)
        {
            return Err(Failure::TenantMismatch);
        }
    } else if journal.manifest.is_some()
        || journal.identity.is_some()
        || journal.challenge.is_some()
    {
        return Err(Failure::Validation);
    }
    if (journal.identity.is_some() || journal.challenge.is_some())
        && store.read("device-key")?.is_none()
    {
        return Err(Failure::Validation);
    }
    if let Some(manifest) = &journal.manifest {
        if crate::config::origin(&manifest.control_url)?
            != crate::config::origin(&config.server_url)?
        {
            return Err(Failure::TenantMismatch);
        }
    }
    Ok(journal)
}
pub(crate) fn validate_saved_binding(config: &Config, store: &ProtectedStore) -> Result<()> {
    read_journal(config, store).map(|_| ())
}

impl ClientService {
    pub fn open(
        config: Config,
        store: ProtectedStore,
        connect: impl FnOnce(Arc<DeviceKey>) -> Result<Arc<dyn ControlPort>>,
    ) -> Result<Self> {
        config.validate()?;
        let lease = store.lease()?;
        let journal = read_journal(&config, &store)?;
        let observed = crate::now().max(journal.observed_time);
        // Persist the initial origin anchor under the service lease before generating a key.
        // Unsupported or foreign state above is never rewritten or adopted.
        if store.read("journal.json")?.is_none() {
            store.write(
                "journal.json",
                &serde_json::to_vec(&journal).map_err(|_| Failure::Validation)?,
            )?;
        }
        let key = Arc::new(DeviceKey::load_or_create(&store)?);
        if let (Some(identity), Some(manifest)) = (&journal.identity, &journal.manifest) {
            verify_identity(
                identity,
                manifest,
                &key,
                &format!("device-{}", &key.fingerprint()[..32]),
                observed.min(identity.expires_at_unix_ms.saturating_sub(1000)),
            )?;
        }
        if (journal.identity.is_some() || journal.challenge.is_some()) && journal.manifest.is_none()
        {
            return Err(Failure::Validation);
        }
        let control = connect(key.clone())?;
        if let Some(tenant) = &journal.tenant_id {
            control.bind_tenant(tenant)?;
        }
        let state = if journal.revoked {
            EndpointState::Revoked
        } else if journal.identity.is_some() {
            EndpointState::Offline
        } else if journal.challenge.is_some() {
            EndpointState::Pending
        } else {
            EndpointState::Unenrolled
        };
        let execution = match &config.execution {
            Some(settings) => Some(crate::execution::Manager::new(
                settings.clone(),
                local_authorization(&config)?,
            )?),
            None => None,
        };
        let deployment = if let Some(settings) = &config.deployment {
            crate::deployment::State::open(&store, settings)?
        } else {
            crate::deployment::State::default()
        };
        Ok(Self {
            activity: crate::activity::Activity::open(config.state_directory.clone())?,
            schedule_changed: Arc::new(tokio::sync::Notify::new()),
            power: crate::power::Power::volatile(),
            remote_job: None,
            remote_result: None,
            adoption: match store.read("adoption.json")? {
                Some(bytes) => {
                    Some(crate::contracts::Contracts::new()?.decode("EndpointAdoption", &bytes)?)
                }
                None => None,
            },
            deployment,
            execution,
            store,
            _lease: lease,
            key,
            control,
            config,
            journal,
            state,
            started: Instant::now(),
            successes: 0,
            failures: 0,
            check_in_interval_ms: 500,
            observed,
            boot_time: observed,
        })
    }
    /// Shares the device's saved power switch with this gateway connection.
    pub fn with_power(mut self, power: Arc<crate::power::Power>) -> Self {
        self.power = power;
        self
    }
    fn clock(&mut self) -> Result<u64> {
        let wall = crate::now();
        if wall.saturating_add(1000) < self.observed {
            return Err(Failure::Expired);
        }
        let now = wall.max(
            self.boot_time.saturating_add(
                self.started
                    .elapsed()
                    .as_millis()
                    .try_into()
                    .unwrap_or(u64::MAX),
            ),
        );
        self.observed = now;
        self.journal.observed_time = now;
        self.save()?;
        Ok(now)
    }
    fn local_catalog(&self) -> Vec<LocalToolInfo> {
        let mut tools = self
            .execution
            .as_ref()
            .map(|m| m.catalog())
            .unwrap_or_default();
        tools.retain(|tool| tool.enabled);
        tools.sort_by(|a, b| a.tool_id.cmp(&b.tool_id));
        tools.dedup_by(|a, b| a.tool_id == b.tool_id);
        tools
    }
    pub async fn tool_catalog(&self, agent: Option<&str>) -> Result<Vec<LocalToolInfo>> {
        if !self.health().ready {
            return Err(Failure::Unavailable);
        }
        let settings = self.config.gateway.as_ref().ok_or(Failure::Unavailable)?;
        let gateway = crate::tool_gateway::HttpsGateway::new(
            settings,
            &self.config,
            Arc::new(crate::contracts::Contracts::new()?),
        )?;
        let visible = gateway.catalog(agent).await?;
        let installed = self.local_catalog();
        Ok(visible
            .tools
            .into_iter()
            .filter(|t| installed.iter().any(|i| i == t))
            .collect())
    }
    pub async fn execute_tool(
        &mut self,
        invocation: LocalToolCall,
    ) -> Result<std::collections::BTreeMap<String, serde_json::Value>> {
        if self.remote_job.is_some() {
            return Err(Failure::Conflict);
        }
        let now = self.clock()?;
        if !self.health().ready {
            return Err(Failure::Unavailable);
        }
        let identity = self
            .journal
            .identity
            .as_ref()
            .ok_or(Failure::Unauthorized)?;
        if self
            .config
            .gateway
            .as_ref()
            .is_none_or(|s| s.device_id != identity.device_id)
        {
            return Err(Failure::Unauthorized);
        }
        let valid_until = identity.expires_at_unix_ms.min(
            self.journal
                .last_success
                .unwrap_or(0)
                .saturating_add(120000),
        );
        if valid_until < now.saturating_add(20000) {
            return Err(Failure::Expired);
        }
        // Local callers reach only signed managed registrations, through the same execution path as remote tasks.
        let input = LocalToolInput {
            protocol_version: 1,
            request_id: crate::identity::nonce()?,
            tool_id: invocation.tool_id,
            arguments: invocation.arguments,
        };
        Ok(self
            .execution
            .as_mut()
            .ok_or(Failure::Unsupported)?
            .invoke(input, valid_until)
            .await?
            .output)
    }
    fn save(&self) -> Result<()> {
        self.store.write(
            "journal.json",
            &serde_json::to_vec(&self.journal).map_err(|_| Failure::Validation)?,
        )
    }
    /// Runtime status is distinct from enrolled-device readiness.
    pub fn runtime_health(&self) -> Result<LocalRuntimeHealth> {
        self.execution
            .as_ref()
            .map(|m| m.health())
            .ok_or(Failure::Unsupported)
    }
    pub async fn prepare_runtimes(&mut self) -> Result<LocalRuntimeHealth> {
        if !self.power.is_on() {
            return Err(Failure::Unavailable);
        }
        if self.remote_job.is_some() {
            return Err(Failure::Conflict);
        }
        self.execution
            .as_mut()
            .ok_or(Failure::Unsupported)?
            .prepare()
            .await
    }
    pub async fn shutdown_runtimes(&mut self) -> Result<()> {
        let remote_cleanup = if let Some(mut job) = self.remote_job.take() {
            job.shutdown().await
        } else {
            Ok(())
        };
        if let Some(manager) = &self.execution {
            manager.shutdown().await?;
        }
        remote_cleanup
    }
    pub async fn invoke_runtime(&mut self, input: LocalToolInput) -> Result<LocalToolOutput> {
        if self.remote_job.is_some() {
            return Err(Failure::Conflict);
        }
        self.clock()?;
        if !self.health().ready {
            return Err(Failure::Unavailable);
        }
        let identity = self
            .journal
            .identity
            .as_ref()
            .ok_or(Failure::Unauthorized)?;
        if self
            .config
            .gateway
            .as_ref()
            .is_none_or(|s| s.device_id != identity.device_id)
        {
            return Err(Failure::Unauthorized);
        }
        let valid_until = identity.expires_at_unix_ms.min(
            self.journal
                .last_success
                .unwrap_or(0)
                .saturating_add(120000),
        );
        let valid_until = if self.config.deployment.is_some()
            && self
                .config
                .execution
                .as_ref()
                .is_none_or(|s| !s.tools.iter().any(|t| t.tool_id == input.tool_id))
        {
            valid_until.min(self.deployment.deadline()?)
        } else {
            valid_until
        };
        self.execution
            .as_mut()
            .ok_or(Failure::Unsupported)?
            .invoke(input, valid_until)
            .await
    }
    pub fn fleet_status(&self) -> FleetClientStatus {
        self.deployment.status.clone()
    }
    async fn reconcile(&mut self, identity: &DeviceIdentity) -> Result<()> {
        let Some(settings) = self.config.deployment.clone() else {
            return Ok(());
        };
        let signed = self.control.desired(identity.clone()).await?;
        let now = self.clock()?;
        settings.separate_device_ca(&identity.issuer_certificate_pem)?;
        let server = self
            .journal
            .manifest
            .as_ref()
            .ok_or(Failure::Unauthorized)?
            .server_id
            .clone();
        let changed =
            self.deployment
                .accept(&self.store, &settings, &signed, identity, &server, now)?;
        if !changed {
            return Ok(());
        }
        let base = self.config.execution.clone().ok_or(Failure::Unsupported)?;
        let authorization = local_authorization(&self.config)?;
        // Fence old assigned tools immediately, preserving only administrator-local registrations.
        if let Some(old) = self.execution.take() {
            old.shutdown().await?;
        }
        self.execution = Some(crate::execution::Manager::new(
            base.clone(),
            authorization.clone(),
        )?);
        let result: Result<crate::execution::Manager> = async {
            let (candidate, documents) = self.deployment.candidate(&settings, &base)?;
            let desired = self
                .deployment
                .desired
                .as_ref()
                .ok_or(Failure::Unavailable)?
                .clone();
            for assignment in &desired.assignments {
                if assignment.desired_presence {
                    let grant = self
                        .control
                        .artifact_grant(
                            identity.clone(),
                            FleetArtifactGrantRequest {
                                generation: desired.generation,
                                manifest_digest: assignment.release.manifest_digest.clone(),
                            },
                        )
                        .await?;
                    let (claims, _): (FleetArtifactGrantClaims, _) = settings.verify(
                        &grant,
                        "toolgate-fleet-artifact+jws",
                        "FleetArtifactGrantClaims",
                    )?;
                    let now = self.clock()?;
                    if claims.device_id != identity.device_id
                        || claims.tenant_id != identity.tenant_id
                        || claims.server_id != server
                        || claims.generation != desired.generation
                        || claims.manifest_digest != assignment.release.manifest_digest
                        || claims.size_bytes != assignment.release.size_bytes
                        || claims.issued_at_unix_ms > now
                        || claims.expires_at_unix_ms <= now
                        || claims
                            .expires_at_unix_ms
                            .saturating_sub(claims.issued_at_unix_ms)
                            > 60000
                    {
                        return Err(Failure::Unauthorized);
                    }
                    let bytes = self.control.artifact(identity.clone(), grant).await?;
                    if bytes.len() as u64 != assignment.release.size_bytes
                        || crate::digest(&bytes) != assignment.release.manifest_digest
                    {
                        return Err(Failure::Unauthorized);
                    }
                    let (_, signed_bytes): (FleetPackageDocument, _) = settings.verify(
                        &assignment.release.release,
                        "toolgate-package-release+jws",
                        "FleetPackageDocument",
                    )?;
                    if bytes != signed_bytes {
                        return Err(Failure::Unauthorized);
                    }
                }
            }
            let mut candidate = crate::execution::Manager::new(candidate, authorization)?;
            if !documents.is_empty() && !candidate.prepare().await?.ready {
                return Err(Failure::Unavailable);
            }
            for document in documents {
                for test in document.self_tests {
                    if let Err(failure) = candidate.probe(&test).await {
                        tracing::warn!(event="self_test",tool_id=%test.tool_id,result="failed",error=?failure);
                        return Err(failure);
                    }
                }
            }
            self.clock()?;
            self.deployment.activate(&self.store)?;
            Ok(candidate)
        }
        .await;
        match result {
            Ok(candidate) => {
                self.execution = Some(candidate);
                tracing::info!(
                    event = "fleet_reconcile",
                    generation = self.deployment.status.generation,
                    result = "ready"
                );
                Ok(())
            }
            Err(failure) => {
                self.deployment.failed(failure);
                tracing::warn!(event="fleet_reconcile",generation=self.deployment.status.generation,result="failed",error=?failure);
                Err(failure)
            }
        }
    }
    fn device(&self) -> String {
        format!("device-{}", &self.key.fingerprint()[..32])
    }
    /// Checked inside the service lock before any console-scoped health, enrollment or proof.
    pub fn authorize_console(
        &self,
        console_url: &str,
        expected_tenant: Option<&str>,
    ) -> Result<()> {
        crate::browser::authorize_console(&self.config, console_url)?;
        if let Some(expected) = expected_tenant {
            crate::contracts::Contracts::shared()?.encode("TenantUuid", &expected)?;
            if self
                .journal
                .manifest
                .as_ref()
                .map(|manifest| manifest.tenant_id.as_str())
                != Some(expected)
            {
                return Err(Failure::TenantMismatch);
            }
        }
        Ok(())
    }
    pub fn console_device_proof(
        &self,
        console_url: &str,
        nonce: &str,
    ) -> Result<ConsoleDeviceProof> {
        self.authorize_console(console_url, None)?;
        let origin = crate::browser::console_origin(console_url)?;
        let local = self
            .config
            .local_console_url
            .as_ref()
            .map(|url| url.trim_end_matches('/').to_owned());
        let verified =
            self.journal.manifest.as_ref().and_then(|manifest| {
                crate::browser::console_origin(&manifest.verification_uri).ok()
            });
        if local.as_ref() != Some(&origin) && verified.as_ref() != Some(&origin) {
            return Err(Failure::Unauthorized);
        }
        Ok(ConsoleDeviceProof {
            nonce: nonce.to_owned(),
            device_id: self.device(),
            key_fingerprint: self.key.fingerprint(),
            signature: self.key.console_proof(nonce)?,
        })
    }
    async fn collect_remote(&mut self, identity: &DeviceIdentity) -> Result<()> {
        if let Some(job) = &mut self.remote_job {
            if let Some(result) = job.completed().await {
                let journal = RemoteJournal {
                    server_url: self.config.server_url.clone(),
                    request_id: result.request_id.clone(),
                    lease_id: result.lease_id.clone(),
                    result: Some(result.clone()),
                };
                self.store.write(
                    "remote-journal.json",
                    &serde_json::to_vec(&journal).map_err(|_| Failure::Validation)?,
                )?;
                self.remote_job = None;
                self.remote_result = Some(result);
            }
        }
        if let Some(result) = self.remote_result.clone() {
            let response = self
                .control
                .remote_result(identity.clone(), result.clone())
                .await;
            if matches!(response, Err(Failure::Conflict | Failure::Expired)) {
                self.remote_result = None;
            }
            let response = response?;
            if response.request_id != result.request_id || response.device_id != identity.device_id
            {
                return Err(Failure::Unauthorized);
            }
            self.remote_result = None;
        }
        Ok(())
    }
    fn receive_remote(&mut self, identity: &DeviceIdentity, task: RemoteToolTask) -> Result<()> {
        let context = &task.invocation.evaluation.context;
        if context.tenant_id != identity.tenant_id
            || context.device_id != identity.device_id
            || task.request_id != context.request_id
            || task.request.tool_id != task.invocation.evaluation.tool_id
            || task.request.action != task.invocation.evaluation.action
            || task.expires_at_unix_ms <= crate::now()
            || task.expires_at_unix_ms > crate::now().saturating_add(30000)
        {
            return Err(Failure::Unauthorized);
        }
        if let Some(job) = &self.remote_job {
            if job.task != task {
                return Err(Failure::Conflict);
            }
            return Ok(());
        }
        if let Some(result) = &self.remote_result {
            if result.request_id != task.request_id || result.lease_id != task.lease_id {
                return Err(Failure::Conflict);
            }
            return Ok(());
        }
        if let Some(bytes) = self.store.read("remote-journal.json")? {
            let journal: RemoteJournal =
                serde_json::from_slice(&bytes).map_err(|_| Failure::Validation)?;
            if journal.server_url == self.config.server_url
                && journal.request_id == task.request_id
                && journal.lease_id == task.lease_id
            {
                // An interrupted effect is never replayed. Return the durable result or a bounded failure.
                self.remote_result = Some(journal.result.unwrap_or(RemoteToolResult {
                    request_id: task.request_id,
                    lease_id: task.lease_id,
                    output: None,
                    error: Some(ErrorCode::Conflict),
                }));
                return Ok(());
            }
        }
        let journal = RemoteJournal {
            server_url: self.config.server_url.clone(),
            request_id: task.request_id.clone(),
            lease_id: task.lease_id.clone(),
            result: None,
        };
        self.store.write(
            "remote-journal.json",
            &serde_json::to_vec(&journal).map_err(|_| Failure::Validation)?,
        )?;
        self.remote_job = Some(crate::remote::Job::start(
            self.config.clone(),
            self.execution.as_ref().map(|m| m.settings()),
            self.control.clone(),
            identity.clone(),
            task,
            self.activity.clone(),
        ));
        Ok(())
    }
    pub fn health(&self) -> ClientHealth {
        let now = crate::now().max(
            self.boot_time.saturating_add(
                self.started
                    .elapsed()
                    .as_millis()
                    .try_into()
                    .unwrap_or(u64::MAX),
            ),
        );
        let fresh = now >= self.observed
            && self
                .journal
                .identity
                .as_ref()
                .is_some_and(|i| i.expires_at_unix_ms > now)
            && self
                .journal
                .last_success
                .is_some_and(|t| now.saturating_sub(t) <= 120000);
        ClientHealth {
            state: if self.state == EndpointState::Active && !fresh {
                EndpointState::Offline
            } else {
                self.state.clone()
            },
            ready: self.state == EndpointState::Active && fresh && self.power.is_on(),
            uptime_seconds: self.started.elapsed().as_secs(),
            successful_check_ins: self.successes,
            failed_check_ins: self.failures,
            report_sequence: self.journal.sequence,
            last_success_unix_ms: self.journal.last_success,
        }
    }
    fn prompt(&self, challenge: &EndpointEnrollmentChallenge) -> EndpointEnrollmentPrompt {
        EndpointEnrollmentPrompt {
            enrollment_id: challenge.enrollment_id.clone(),
            user_code: challenge.user_code.clone(),
            verification_uri: challenge.verification_uri.clone(),
            expires_at_unix_ms: challenge.expires_at_unix_ms,
            poll_interval_seconds: challenge.poll_interval_seconds,
            key_fingerprint: self.key.fingerprint(),
        }
    }
    pub async fn enroll(&mut self) -> Result<EndpointEnrollmentPrompt> {
        self.enroll_inner(None).await
    }
    /// Only an explicit OS-authenticated local request may reopen a revoked device.
    pub async fn request_approval(&mut self) -> Result<EndpointEnrollmentPrompt> {
        let directive = self.control.ping(self.presence()?).await?;
        if directive.device_id != self.device()
            || !(500..=3600000).contains(&directive.next_interval_ms)
        {
            return Err(Failure::Unauthorized);
        }
        if directive.state == EndpointState::Revoked {
            self.enroll_inner(Some(directive.report_sequence)).await
        } else {
            if directive.action == EndpointConnectionAction::Enroll {
                self.follow_enrollment(&directive)?;
            }
            self.enroll().await
        }
    }
    async fn enroll_inner(
        &mut self,
        requested_sequence: Option<u64>,
    ) -> Result<EndpointEnrollmentPrompt> {
        let now = self.clock()?;
        let expired = self
            .journal
            .identity
            .as_ref()
            .is_some_and(|identity| identity.expires_at_unix_ms <= now);
        if requested_sequence.is_none()
            && (self.journal.revoked || self.journal.identity.is_some())
            && !expired
        {
            return Err(Failure::Conflict);
        }
        if let Some(challenge) = &self.journal.challenge {
            if requested_sequence.is_none() && challenge.expires_at_unix_ms > now {
                return Ok(self.prompt(challenge));
            }
        }
        let envelope = self.control.discovery().await?;
        // Signed issuance occurs after the outgoing request. Validate against receipt time.
        let now = self.clock()?;
        let pin = self
            .journal
            .manifest
            .as_ref()
            .map(|m| {
                crate::identity::certificate_der(&m.issuer_certificate_pem)
                    .map(|b| crate::digest(&b))
            })
            .transpose()?;
        let manifest = verify_discovery(
            &envelope,
            &crate::config::origin(&self.config.server_url)?,
            now,
            pin.as_deref(),
        )?;
        self.bind_discovery(&manifest)?;
        let request = self.announcement()?;
        let challenge = if requested_sequence.is_some() {
            self.control.request_approval(request).await?
        } else {
            self.control.start(request).await?
        };
        let now = self.clock()?;
        if challenge.verification_uri != manifest.verification_uri
            || challenge.expires_at_unix_ms <= now
            || challenge.expires_at_unix_ms - now > 600000
        {
            return Err(Failure::Unauthorized);
        }
        if let Some(sequence) = requested_sequence {
            self.reset_enrollment();
            self.journal.sequence = sequence;
        }
        self.journal.manifest = Some(manifest);
        self.journal.identity = None;
        self.journal.revoked = false;
        self.journal.challenge = Some(challenge.clone());
        self.state = EndpointState::Pending;
        self.save()?;
        Ok(self.prompt(&challenge))
    }
    /// Called only after cryptographic discovery verification, before requesting enrollment.
    fn bind_discovery(&mut self, manifest: &ClientDiscovery) -> Result<()> {
        crate::contracts::Contracts::shared()?.encode("TenantUuid", &manifest.tenant_id)?;
        if self
            .journal
            .tenant_id
            .as_ref()
            .is_some_and(|tenant| tenant != &manifest.tenant_id)
        {
            return Err(Failure::TenantMismatch);
        }
        self.control.bind_tenant(&manifest.tenant_id)?;
        self.journal.tenant_id = Some(manifest.tenant_id.clone());
        self.journal.manifest = Some(manifest.clone());
        self.save()
    }
    fn announcement(&self) -> Result<EndpointEnrollmentStart> {
        Ok(EndpointEnrollmentStart {
            device_id: self.device(),
            client_version: env!("CARGO_PKG_VERSION").into(),
            platform: crate::platform::current(),
            csr_pem: self.key.csr(&self.device())?,
            capabilities: vec!["endpoint.identity.v1".into(), "endpoint.check-in.v1".into()],
        })
    }
    fn presence(&self) -> Result<EndpointPresencePing> {
        Ok(EndpointPresencePing {
            announcement: self.announcement()?,
            utc_offset: crate::platform::utc_offset(),
        })
    }
    fn reset_enrollment(&mut self) {
        self.journal.identity = None;
        self.journal.challenge = None;
        self.journal.pending_report = None;
        self.journal.last_success = None;
        self.journal.revoked = false;
        self.journal.reenrollment = true;
        self.remote_job = None;
        self.remote_result = None;
        self.state = EndpointState::Unenrolled;
    }
    fn follow_enrollment(&mut self, directive: &EndpointConnectionDirective) -> Result<()> {
        if self.journal.identity.is_some()
            || self.journal.revoked
            || (directive.state == EndpointState::Unenrolled && self.journal.challenge.is_some())
        {
            self.reset_enrollment();
        }
        // A report may have been accepted just before deletion but its ack lost.
        self.journal.sequence = directive.report_sequence;
        self.journal.reenrollment = true;
        self.save()
    }
    pub async fn tick(&mut self) -> Result<()> {
        let result = self.tick_inner().await;
        if let Err(failure) = result {
            if let Some(job) = &self.remote_job {
                job.cancel();
            }
            self.failures = self.failures.saturating_add(1);
            if failure == Failure::EnrollmentRequired {
                self.reset_enrollment();
                self.save()?;
            } else if failure == Failure::Revoked {
                self.state = EndpointState::Revoked;
                self.journal.revoked = true;
                self.save()?;
            } else if self.journal.identity.is_some() && !self.journal.revoked {
                self.state = EndpointState::Offline;
            }
            tracing::warn!(event="client_check_in",result="rejected",error=?failure);
        }
        result
    }
    async fn tick_inner(&mut self) -> Result<()> {
        let now = self.clock()?;
        // Presence continues in every local state. Only Control decides the next action.
        let directive = self.control.ping(self.presence()?).await?;
        if directive.device_id != self.device()
            || !(500..=3600000).contains(&directive.next_interval_ms)
        {
            return Err(Failure::Unauthorized);
        }
        self.check_in_interval_ms = directive.next_interval_ms;
        match directive.action {
            EndpointConnectionAction::Wait => {
                if let Some(job) = &self.remote_job {
                    job.cancel();
                }
                self.journal.revoked = directive.state == EndpointState::Revoked;
                self.state = directive.state;
                self.failures = 0;
                self.save()?;
                return Ok(());
            }
            EndpointConnectionAction::Enroll => {
                self.follow_enrollment(&directive)?;
            }
            EndpointConnectionAction::CheckIn => {
                self.journal.revoked = false;
                if self.journal.identity.is_none() && self.journal.challenge.is_none() {
                    self.enroll().await?;
                }
            }
        }
        if self.journal.reenrollment
            && self.journal.identity.is_none()
            && self.journal.challenge.is_none()
        {
            self.enroll().await?;
        }
        if self
            .journal
            .identity
            .as_ref()
            .is_some_and(|identity| identity.expires_at_unix_ms <= now)
        {
            // Recover via a signed CSR for the same registered key. The server must still
            // approve this device and owner; a disabled or deapproved identity cannot recover.
            self.enroll().await?;
        }
        if self.journal.identity.is_none() {
            let Some(challenge) = &self.journal.challenge else {
                return Ok(());
            };
            if challenge.expires_at_unix_ms <= now {
                self.journal.challenge = None;
                self.state = EndpointState::Unenrolled;
                self.save()?;
                return Err(Failure::Expired);
            }
            let result = self
                .control
                .poll(EndpointEnrollmentPoll {
                    enrollment_id: challenge.enrollment_id.clone(),
                    device_code: challenge.device_code.clone(),
                })
                .await?;
            self.remember_server_name(result.server_name.as_deref());
            match result.state {
                EnrollmentState::Consumed => {
                    let identity = result.identity.ok_or(Failure::Unauthorized)?;
                    let manifest = self
                        .journal
                        .manifest
                        .as_ref()
                        .ok_or(Failure::Unauthorized)?;
                    verify_identity(&identity, manifest, &self.key, &self.device(), now)?;
                    self.journal.identity = Some(identity);
                    self.journal.challenge = None;
                    self.journal.reenrollment = false;
                    self.save()?;
                }
                EnrollmentState::Pending => return Ok(()),
                EnrollmentState::Denied | EnrollmentState::Expired => {
                    self.journal.challenge = None;
                    self.state = EndpointState::Unenrolled;
                    self.save()?;
                    return Err(Failure::Unauthorized);
                }
                _ => return Err(Failure::Unauthorized),
            }
        }
        let identity = self.journal.identity.clone().ok_or(Failure::Unauthorized)?;
        if identity.expires_at_unix_ms <= now {
            return Err(Failure::Expired);
        }
        // A power switch is reported at once instead of waiting for the next interval.
        if self.journal.pending_report.is_none()
            && self.power_reported()
            && self
                .journal
                .last_success
                .is_some_and(|last| now.saturating_sub(last) < self.check_in_interval_ms)
        {
            return Ok(());
        }
        if self.journal.pending_report.is_none() {
            if let Err(failure) = self.collect_remote(&identity).await {
                tracing::warn!(event="remote_result",error=?failure);
                if failure == Failure::Revoked {
                    return Err(failure);
                }
            }
            if serde_json::to_vec(&self.local_catalog())
                .map_err(|_| Failure::Validation)?
                .len()
                > 49152
            {
                return Err(Failure::Validation);
            }
            let request = EndpointCheckIn {
                sequence: self
                    .journal
                    .sequence
                    .checked_add(1)
                    .ok_or(Failure::Conflict)?,
                report: ClientReport {
                    device_id: self.device(),
                    client_version: env!("CARGO_PKG_VERSION").into(),
                    applied_revision: self.deployment.status.generation,
                    packages: self.deployment.status.packages.clone(),
                    // Omitted while on, so gateways without the power switch accept the report.
                    power: (!self.power.is_on()).then_some(DevicePower::Off),
                },
                adoption_digest: self
                    .adoption
                    .as_ref()
                    .filter(|p| {
                        p.device_id == identity.device_id
                            && self
                                .journal
                                .manifest
                                .as_ref()
                                .is_some_and(|m| m.server_id == p.server_id)
                    })
                    .map(|p| p.digest.clone()),
                local_tools: Some(self.local_catalog()),
            };
            self.journal.pending_report = Some(request);
            self.save()?;
        }
        let request = self
            .journal
            .pending_report
            .clone()
            .ok_or(Failure::Conflict)?;
        let ack = self.control.check_in(identity, request.clone()).await?;
        if ack.device_id != self.device()
            || ack.sequence != request.sequence
            || ack.server_time_unix_ms.abs_diff(now) > 300000
        {
            return Err(Failure::Unauthorized);
        }
        let interval_ms = ack.next_interval_ms;
        if !(500..=3600000).contains(&interval_ms) {
            return Err(Failure::Validation);
        }
        self.check_in_interval_ms = interval_ms;
        self.remember_server_name(ack.server_name.as_deref());
        if let Some(identity) = ack.identity {
            verify_identity(
                &identity,
                self.journal
                    .manifest
                    .as_ref()
                    .ok_or(Failure::Unauthorized)?,
                &self.key,
                &self.device(),
                now,
            )?;
            self.journal.identity = Some(identity);
        }
        if let Some(configuration) = ack.adoption {
            let identity = self
                .journal
                .identity
                .as_ref()
                .ok_or(Failure::Unauthorized)?;
            let server = &self
                .journal
                .manifest
                .as_ref()
                .ok_or(Failure::Unauthorized)?
                .server_id;
            if configuration.device_id != identity.device_id
                || &configuration.server_id != server
                || self.adoption.as_ref().is_some_and(|old| {
                    old.server_id == configuration.server_id
                        && (old.revision > configuration.revision
                            || old.authorization_epoch > configuration.authorization_epoch)
                })
            {
                return Err(Failure::Unauthorized);
            }
            let bytes =
                crate::contracts::Contracts::new()?.encode("EndpointAdoption", &configuration)?;
            self.store.write("adoption.json", &bytes)?;
            if self
                .adoption
                .as_ref()
                .is_some_and(|old| old.authorization_epoch != configuration.authorization_epoch)
            {
                if let Some(job) = &self.remote_job {
                    job.cancel();
                }
            }
            self.adoption = Some(configuration);
        }
        self.journal.sequence = request.sequence;
        self.journal.pending_report = None;
        self.journal.last_success = Some(now);
        self.journal.power_off_reported = request.report.power == Some(DevicePower::Off);
        self.save()?;
        self.successes = self.successes.saturating_add(1);
        self.state = EndpointState::Active;
        tracing::info!(
            event = "client_check_in",
            result = "accepted",
            sequence = self.journal.sequence
        );
        // Switched off: no task, package or builder work is taken from this or any later reply.
        if !self.power.is_on() {
            return Ok(());
        }
        // Check authority first on every cycle, including while a leased job is running.
        let identity = self.journal.identity.clone().ok_or(Failure::Unauthorized)?;
        if let Some(task) = ack.task {
            self.receive_remote(&identity, task)?;
        }
        if let Err(failure) = self.reconcile(&identity).await {
            if self
                .deployment
                .desired
                .as_ref()
                .is_some_and(|d| d.expires_at_unix_ms <= crate::now())
            {
                self.deployment.failed(Failure::Expired);
            }
            if failure == Failure::Revoked {
                return Err(failure);
            }
        }
        Ok(())
    }
    /// True when this gateway's last acknowledged report matches the owner's switch.
    fn power_reported(&self) -> bool {
        self.journal.power_off_reported != self.power.is_on()
    }
    /// The owner switched the device off: stop local work, then send this gateway one final
    /// report. A report already pending is delivered first, so at most two check-ins are sent.
    pub async fn power_off(&mut self) -> Result<()> {
        let stopped = self.shutdown_runtimes().await;
        if self.journal.identity.is_some() && !self.journal.revoked {
            for _ in 0..2 {
                if self.power.is_on() || self.power_reported() {
                    break;
                }
                self.tick().await?;
            }
        }
        stopped
    }
    /// Keeps the gateway's latest display name; the next journal save persists it.
    fn remember_server_name(&mut self, name: Option<&str>) {
        if let Some(name) = name.filter(|name| valid_server_name(name)) {
            self.journal.server_name = Some(name.to_owned());
        }
    }
    pub fn server_name(&self) -> Option<String> {
        self.journal.server_name.clone()
    }
    pub fn server_url(&self) -> &str {
        &self.config.server_url
    }
    pub(crate) fn is_revoked(&self) -> bool {
        self.journal.revoked
    }
    pub fn next_delay_millis(&self) -> u64 {
        if self.journal.revoked {
            return crate::platform::next_local_hour_millis(
                crate::now(),
                crate::platform::utc_offset_minutes().unwrap_or(0),
            );
        }
        if self.journal.challenge.is_some() {
            return 5000;
        }
        if self.state == EndpointState::Active || self.failures == 0 {
            return self.check_in_interval_ms;
        }
        (5_u64.saturating_mul(1_u64 << self.failures.min(6))).min(300) * 1000
    }
}

/// Mirrors the GatewayName contract so a name never carries control characters into the tray.
pub fn valid_server_name(name: &str) -> bool {
    let bytes = name.as_bytes();
    (1..=64).contains(&bytes.len())
        && bytes[0].is_ascii_alphanumeric()
        && bytes
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || b" ._()-".contains(b))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transport::Call;
    use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

    struct Gateway {
        pings: AtomicU64,
        ping_unavailable: AtomicBool,
        directive: std::sync::Mutex<Option<EndpointConnectionDirective>>,
        polls: AtomicU64,
        desired: AtomicU64,
        revoked: AtomicBool,
        deleted: AtomicBool,
        blocked: AtomicBool,
        tenant_mismatch: AtomicBool,
        authorizations: AtomicU64,
        deadline: AtomicU64,
        interval_ms: AtomicU64,
        consumption: std::sync::Mutex<Option<EnterpriseInvocation>>,
        reports: std::sync::Mutex<Vec<Option<DevicePower>>>,
    }
    impl ControlPort for Gateway {
        fn ping(&self, request: EndpointPresencePing) -> Call<'_, EndpointConnectionDirective> {
            Box::pin(async move {
                self.pings.fetch_add(1, Ordering::SeqCst);
                if self.ping_unavailable.load(Ordering::SeqCst) {
                    return Err(Failure::Unavailable);
                }
                Ok(self
                    .directive
                    .lock()
                    .unwrap()
                    .clone()
                    .unwrap_or(EndpointConnectionDirective {
                        device_id: request.announcement.device_id,
                        state: EndpointState::Active,
                        action: EndpointConnectionAction::CheckIn,
                        report_sequence: 0,
                        next_interval_ms: 500,
                    }))
            })
        }
        fn consume_effect(
            &self,
            identity: DeviceIdentity,
            request: EnterprisePermitConsumption,
        ) -> Call<'_, EnterpriseInvocation> {
            Box::pin(async move {
                if self.revoked.load(Ordering::SeqCst) {
                    return Err(Failure::Revoked);
                }
                let mut value = self
                    .consumption
                    .lock()
                    .unwrap()
                    .take()
                    .ok_or(Failure::Unauthorized)?;
                assert_eq!(value.id, request.invocation_id);
                assert_eq!(value.evaluation.context.device_id, identity.device_id);
                assert_eq!(value.evaluation.arguments_digest, request.arguments_digest);
                assert_eq!(value.evaluation.resources, request.resources);
                assert_eq!(value.evaluation.tool_digest, request.tool_digest);
                assert_eq!(value.evaluation.package_digest, request.package_digest);
                value.state = EnterpriseInvocationState::Executing;
                value.revision += 1;
                Ok(value)
            })
        }
        fn discovery(&self) -> Call<'_, SignedClientDiscovery> {
            Box::pin(async { Err(Failure::Unsupported) })
        }
        fn start(&self, _: EndpointEnrollmentStart) -> Call<'_, EndpointEnrollmentChallenge> {
            Box::pin(async { Err(Failure::Unsupported) })
        }
        fn poll(&self, _: EndpointEnrollmentPoll) -> Call<'_, EndpointEnrollmentResult> {
            Box::pin(async { Err(Failure::Unsupported) })
        }
        fn desired(&self, _: DeviceIdentity) -> Call<'_, FleetSignedDocument> {
            Box::pin(async {
                self.desired.fetch_add(1, Ordering::SeqCst);
                Err(Failure::Unsupported)
            })
        }
        fn check_in(
            &self,
            _: DeviceIdentity,
            report: EndpointCheckIn,
        ) -> Call<'_, EndpointCheckInAck> {
            Box::pin(async move {
                self.polls.fetch_add(1, Ordering::SeqCst);
                self.reports
                    .lock()
                    .unwrap()
                    .push(report.report.power.clone());
                if self.deleted.load(Ordering::SeqCst) {
                    return Err(Failure::EnrollmentRequired);
                }
                if self.revoked.load(Ordering::SeqCst) {
                    return Err(Failure::Revoked);
                }
                if self.blocked.load(Ordering::SeqCst) {
                    return Err(Failure::Unavailable);
                }
                if self.tenant_mismatch.load(Ordering::SeqCst) {
                    return Err(Failure::TenantMismatch);
                }
                Ok(EndpointCheckInAck {
                    device_id: report.report.device_id,
                    sequence: report.sequence,
                    server_time_unix_ms: crate::now(),
                    next_interval_ms: self.interval_ms.load(Ordering::SeqCst),
                    identity: None,
                    adoption: None,
                    task: None,
                    server_name: None,
                })
            })
        }
        fn remote_authorize(
            &self,
            _: DeviceIdentity,
            request: RemoteToolAuthorization,
        ) -> Call<'_, RemoteToolAuthorizationAck> {
            Box::pin(async move {
                self.authorizations.fetch_add(1, Ordering::SeqCst);
                assert!(["hotfolder.write_text", "hotfolder.read_text"]
                    .contains(&request.request.tool_id.as_str()));
                assert_eq!(
                    request.request.arguments.get("path"),
                    Some(&serde_json::json!("test.txt"))
                );
                if self.revoked.load(Ordering::SeqCst) {
                    return Err(Failure::Revoked);
                }
                Ok(RemoteToolAuthorizationAck {
                    expires_at_unix_ms: self.deadline.load(Ordering::SeqCst),
                })
            })
        }
    }

    fn test_service() -> (ClientService, Arc<Gateway>, std::path::PathBuf) {
        #[cfg(windows)]
        let base = std::path::PathBuf::from(std::env::var_os("ProgramData").unwrap());
        #[cfg(target_os = "macos")]
        let base = std::path::PathBuf::from("/private/tmp");
        #[cfg(all(unix, not(target_os = "macos")))]
        let base = std::env::temp_dir();
        let directory = base.join(format!(
            "toolgate-poll-{}",
            crate::identity::nonce().unwrap()
        ));
        let store = ProtectedStore::open(directory.clone()).unwrap();
        let config = Config {
            deployment: None,
            execution: None,
            gateway: None,
            server_url: "https://control.example.test".into(),
            state_directory: directory.clone(),
            ipc_endpoint: {
                #[cfg(unix)]
                {
                    directory.join("client.sock").to_string_lossy().into_owned()
                }
                #[cfg(windows)]
                {
                    r"\\.\pipe\olo-toolgate-client".into()
                }
            },
            authorized_peers: vec![{
                #[cfg(unix)]
                {
                    unsafe { libc::geteuid() }.to_string()
                }
                #[cfg(windows)]
                {
                    crate::platform::windows::current_sid().unwrap()
                }
            }],
            ca_certificate_path: None,
            request_timeout_seconds: 5,
            local_console_url: None,
        };
        let gateway = Arc::new(Gateway {
            pings: AtomicU64::new(0),
            ping_unavailable: AtomicBool::new(false),
            directive: std::sync::Mutex::new(None),
            polls: AtomicU64::new(0),
            desired: AtomicU64::new(0),
            revoked: AtomicBool::new(false),
            deleted: AtomicBool::new(false),
            blocked: AtomicBool::new(false),
            tenant_mismatch: AtomicBool::new(false),
            authorizations: AtomicU64::new(0),
            deadline: AtomicU64::new(crate::now() + 29000),
            interval_ms: AtomicU64::new(500),
            consumption: std::sync::Mutex::new(None),
            reports: std::sync::Mutex::new(vec![]),
        });
        let service = ClientService::open(config, store, |_| Ok(gateway.clone())).unwrap();
        (service, gateway, directory)
    }
    #[test]
    fn console_proof_requires_the_current_console_origin_and_does_not_enroll() {
        let (mut service, gateway, directory) = test_service();
        let nonce = "a".repeat(64);
        assert!(service
            .console_device_proof("https://untrusted.example/console/", &nonce)
            .is_err());
        service.config.local_console_url = Some("http://127.0.0.1:18103".into());
        let proof = service
            .console_device_proof("http://127.0.0.1:18103/console/#enroll", &nonce)
            .unwrap();
        assert_eq!(proof.nonce, nonce);
        assert_eq!(proof.device_id, service.device());
        assert!(service
            .console_device_proof("http://127.0.0.1:18104/console/", &nonce)
            .is_err());
        assert!(service
            .console_device_proof("http://localhost:18103/console/", &nonce)
            .is_err());
        assert_eq!(gateway.polls.load(Ordering::SeqCst), 0);
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[test]
    fn console_scope_requires_the_configured_origin_and_verified_uuid_without_changing_binding() {
        let (mut service, gateway, directory) = test_service();
        let allowed = format!("{}/console/", service.config.server_url);
        assert_eq!(service.authorize_console(&allowed, None), Ok(()));
        assert_eq!(
            service.authorize_console("https://foreign.example/console/", None),
            Err(Failure::TenantMismatch)
        );
        let tenant = "10000000-0000-4000-8000-000000000001";
        assert_eq!(
            service.authorize_console(&allowed, Some(tenant)),
            Err(Failure::TenantMismatch)
        );
        let mut manifest: ClientDiscovery =
            serde_json::from_value(fixtures()["ClientDiscovery"].clone()).unwrap();
        manifest.tenant_id = tenant.into();
        service.journal.manifest = Some(manifest);
        assert_eq!(service.authorize_console(&allowed, Some(tenant)), Ok(()));
        assert_eq!(
            service.authorize_console(&allowed, Some("10000000-0000-4000-8000-000000000002")),
            Err(Failure::TenantMismatch)
        );
        assert_eq!(service.journal.manifest.as_ref().unwrap().tenant_id, tenant);
        assert_eq!(gateway.polls.load(Ordering::SeqCst), 0);
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[test]
    fn verified_discovery_cannot_replace_owner_after_reset_or_restart() {
        let (mut service, gateway, directory) = test_service();
        let mut manifest: ClientDiscovery =
            serde_json::from_value(fixtures()["ClientDiscovery"].clone()).unwrap();
        manifest.tenant_id = "10000000-0000-4000-8000-000000000001".into();
        manifest.control_url = service.config.server_url.clone();
        service.bind_discovery(&manifest).unwrap();
        service.reset_enrollment();
        service.save().unwrap();
        let config = service.config.clone();
        let before = service.store.read("journal.json").unwrap().unwrap();
        let key = service.key.fingerprint();
        manifest.tenant_id = "10000000-0000-4000-8000-000000000002".into();
        assert_eq!(
            service.bind_discovery(&manifest),
            Err(Failure::TenantMismatch)
        );
        assert_eq!(service.store.read("journal.json").unwrap().unwrap(), before);
        drop(service);
        let store = ProtectedStore::open(directory.clone()).unwrap();
        let mut restarted = ClientService::open(config, store, |_| Ok(gateway.clone())).unwrap();
        assert_eq!(restarted.key.fingerprint(), key);
        assert_eq!(
            restarted.bind_discovery(&manifest),
            Err(Failure::TenantMismatch)
        );
        assert_eq!(
            restarted.store.read("journal.json").unwrap().unwrap(),
            before
        );
        assert_eq!(gateway.polls.load(Ordering::SeqCst), 0);
        drop(restarted);
        std::fs::remove_dir_all(directory).unwrap();
    }
    fn fixtures() -> serde_json::Value {
        serde_json::from_str(include_str!(
            "../../../tests/fixtures/contracts/v1/valid.json"
        ))
        .unwrap()
    }
    fn remote_task(identity: &DeviceIdentity, deadline: u64) -> RemoteToolTask {
        let mut task: RemoteToolTask =
            serde_json::from_value(fixtures()["RemoteToolTask"].clone()).unwrap();
        task.invocation.evaluation.context.tenant_id = identity.tenant_id.clone();
        task.invocation.evaluation.context.user_id = Some(identity.user_id.clone());
        task.invocation.evaluation.context.device_id = identity.device_id.clone();
        task.invocation.evaluation.context.request_id = task.request_id.clone();
        task.invocation.evaluation.tool_id = task.request.tool_id.clone();
        task.invocation.evaluation.action = task.request.action.clone();
        task.expires_at_unix_ms = deadline;
        task
    }
    #[tokio::test]
    async fn interrupted_effect_is_not_replayed_and_completed_result_is_recovered() {
        let (mut service, _, directory) = test_service();
        let identity: DeviceIdentity =
            serde_json::from_value(fixtures()["DeviceIdentity"].clone()).unwrap();
        let task = remote_task(&identity, crate::now() + 29000);
        let mut journal = RemoteJournal {
            server_url: service.config.server_url.clone(),
            request_id: task.request_id.clone(),
            lease_id: task.lease_id.clone(),
            result: None,
        };
        service
            .store
            .write(
                "remote-journal.json",
                &serde_json::to_vec(&journal).unwrap(),
            )
            .unwrap();
        service.receive_remote(&identity, task.clone()).unwrap();
        assert!(service.remote_job.is_none());
        assert_eq!(
            service.remote_result.as_ref().unwrap().error,
            Some(ErrorCode::Conflict)
        );
        let result = RemoteToolResult {
            request_id: task.request_id.clone(),
            lease_id: task.lease_id.clone(),
            output: Some(std::collections::BTreeMap::new()),
            error: None,
        };
        journal.result = Some(result.clone());
        service
            .store
            .write(
                "remote-journal.json",
                &serde_json::to_vec(&journal).unwrap(),
            )
            .unwrap();
        service.remote_result = None;
        service.receive_remote(&identity, task).unwrap();
        assert!(service.remote_job.is_none());
        assert_eq!(service.remote_result, Some(result));
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn temporary_disable_keeps_identity_and_resumes_pending_report() {
        let (mut service, gateway, directory) = test_service();
        let mut identity: DeviceIdentity =
            serde_json::from_value(fixtures()["DeviceIdentity"].clone()).unwrap();
        identity.expires_at_unix_ms = crate::now() + 60000;
        service.journal.identity = Some(identity);
        gateway.blocked.store(true, Ordering::SeqCst);
        assert_eq!(service.tick().await, Err(Failure::Unavailable));
        assert!(!service.journal.revoked);
        assert!(service.journal.identity.is_some());
        assert_eq!(service.journal.pending_report.as_ref().unwrap().sequence, 1);
        gateway.blocked.store(false, Ordering::SeqCst);
        service.tick().await.unwrap();
        assert_eq!(service.journal.sequence, 1);
        assert!(service.journal.pending_report.is_none());
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn tenant_mismatch_keeps_the_bound_identity_and_report_for_the_correct_gateway() {
        let (mut service, gateway, directory) = test_service();
        let mut identity: DeviceIdentity =
            serde_json::from_value(fixtures()["DeviceIdentity"].clone()).unwrap();
        identity.expires_at_unix_ms = crate::now() + 60000;
        let tenant = identity.tenant_id.clone();
        let device = identity.device_id.clone();
        let fingerprint = service.key.fingerprint();
        service.journal.identity = Some(identity);
        gateway.tenant_mismatch.store(true, Ordering::SeqCst);
        assert_eq!(service.tick().await, Err(Failure::TenantMismatch));
        assert_eq!(service.health().state, EndpointState::Offline);
        assert!(!service.journal.revoked);
        assert!(!service.journal.reenrollment);
        assert_eq!(service.journal.identity.as_ref().unwrap().tenant_id, tenant);
        assert_eq!(service.journal.identity.as_ref().unwrap().device_id, device);
        assert_eq!(service.key.fingerprint(), fingerprint);
        assert_eq!(service.journal.pending_report.as_ref().unwrap().sequence, 1);
        let persisted: Journal =
            serde_json::from_slice(&service.store.read("journal.json").unwrap().unwrap()).unwrap();
        assert!(!persisted.revoked);
        assert_eq!(persisted.identity.as_ref().unwrap().tenant_id, tenant);
        gateway.tenant_mismatch.store(false, Ordering::SeqCst);
        service.tick().await.unwrap();
        assert_eq!(service.journal.sequence, 1);
        assert!(service.journal.pending_report.is_none());
        assert_eq!(service.health().state, EndpointState::Active);
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn power_off_reports_once_and_power_on_resumes_without_reenrollment() {
        let (mut service, gateway, directory) = test_service();
        let mut identity: DeviceIdentity =
            serde_json::from_value(fixtures()["DeviceIdentity"].clone()).unwrap();
        identity.expires_at_unix_ms = crate::now() + 60000;
        service.journal.identity = Some(identity);
        service.tick().await.unwrap();
        assert!(service.health().ready);
        let power = crate::power::Power::open(directory.clone()).unwrap();
        let mut service = service.with_power(power.clone());
        power.set(false).unwrap();
        assert!(!service.health().ready);
        // The final report goes out at once, inside the check-in interval, and only once.
        service.power_off().await.unwrap();
        service.power_off().await.unwrap();
        assert_eq!(
            *gateway.reports.lock().unwrap(),
            [None, Some(DevicePower::Off)]
        );
        assert!(service.journal.power_off_reported);
        assert_eq!(
            service.prepare_runtimes().await.err(),
            Some(Failure::Unavailable)
        );
        // Switched on: the next cycle reports at once, with the same enrollment.
        power.set(true).unwrap();
        service.tick().await.unwrap();
        service.tick().await.unwrap();
        assert_eq!(
            *gateway.reports.lock().unwrap(),
            [None, Some(DevicePower::Off), None]
        );
        assert!(!service.journal.power_off_reported);
        assert!(service.health().ready);
        assert_eq!(service.journal.sequence, 3);
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn only_the_local_switch_changes_power_and_off_refuses_gateway_work() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let (service, _, directory) = test_service();
        let power = crate::power::Power::open(directory.clone()).unwrap();
        let activity = service.activity.clone();
        let service = Arc::new(tokio::sync::Mutex::new(service.with_power(power.clone())));
        let contracts = crate::contracts::Contracts::new().unwrap();
        let call = |operation: ClientIpcOperation, power: Option<Arc<crate::power::Power>>| {
            let (service, contracts, activity) = (service.clone(), &contracts, activity.clone());
            async move {
                let request = ClientIpcRequest {
                    protocol_version: 1,
                    request_id: crate::identity::nonce().unwrap(),
                    operation,
                };
                let bytes = contracts.encode("ClientIpcRequest", &request).unwrap();
                let (mut stream, mut peer) = tokio::io::duplex(16384);
                peer.write_u32(bytes.len() as u32).await.unwrap();
                peer.write_all(&bytes).await.unwrap();
                crate::ipc::connection_with_gateways(
                    &mut stream,
                    "authorized",
                    &["authorized".into()],
                    &service,
                    None,
                    power.as_deref(),
                    contracts,
                    &activity,
                )
                .await
                .unwrap();
                let mut response = vec![0; peer.read_u32().await.unwrap() as usize];
                peer.read_exact(&mut response).await.unwrap();
                contracts
                    .decode::<ClientIpcResponse>("ClientIpcResponse", &response)
                    .unwrap()
            }
        };
        // Without the device switch, as for an unauthenticated test listener, nothing changes.
        let response = call(ClientIpcOperation::PowerOff, None).await;
        assert_eq!(response.error, Some(ErrorCode::Unsupported));
        assert!(power.is_on());
        let response = call(ClientIpcOperation::PowerOff, Some(power.clone())).await;
        assert_eq!(response.power, Some(DevicePower::Off));
        assert!(!crate::power::Power::open(directory.clone())
            .unwrap()
            .is_on());
        let response = call(ClientIpcOperation::CheckIn, Some(power.clone())).await;
        assert_eq!(response.error, Some(ErrorCode::DependencyUnavailable));
        let response = call(ClientIpcOperation::Health, Some(power.clone())).await;
        assert_eq!(response.power, Some(DevicePower::Off));
        assert!(!response.health.unwrap().ready);
        let response = call(ClientIpcOperation::PowerOn, Some(power.clone())).await;
        assert_eq!(response.power, Some(DevicePower::On));
        assert!(power.is_on());
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn pings_continue_while_waiting_and_revoked_and_follow_server_reenrollment() {
        let (mut service, gateway, directory) = test_service();
        let mut identity: DeviceIdentity =
            serde_json::from_value(fixtures()["DeviceIdentity"].clone()).unwrap();
        identity.expires_at_unix_ms = crate::now() + 60000;
        service.journal.identity = Some(identity);
        service.journal.sequence = 4;
        for state in [EndpointState::Offline, EndpointState::Revoked] {
            *gateway.directive.lock().unwrap() = Some(EndpointConnectionDirective {
                device_id: service.device(),
                state: state.clone(),
                action: EndpointConnectionAction::Wait,
                report_sequence: 5,
                next_interval_ms: 5000,
            });
            service.tick().await.unwrap();
            service.tick().await.unwrap();
            assert!(!service.health().ready);
            assert_eq!(service.state, state);
            assert!(service.journal.identity.is_some());
            if state == EndpointState::Revoked {
                assert!((1..=3600000).contains(&service.next_delay_millis()));
            } else {
                assert_eq!(service.next_delay_millis(), 5000);
            }
        }
        assert_eq!(gateway.pings.load(Ordering::SeqCst), 4);
        assert_eq!(gateway.polls.load(Ordering::SeqCst), 0);
        *gateway.directive.lock().unwrap() = Some(EndpointConnectionDirective {
            device_id: service.device(),
            state: EndpointState::Unenrolled,
            action: EndpointConnectionAction::Enroll,
            report_sequence: 5,
            next_interval_ms: 5000,
        });
        // Discovery is unavailable in this mock: the retry state must still be durable.
        assert_eq!(service.tick().await, Err(Failure::Unsupported));
        assert_eq!(service.journal.sequence, 5);
        assert!(!service.journal.revoked);
        assert!(service.journal.identity.is_none());
        assert!(service.journal.reenrollment);
        let saved: Journal =
            serde_json::from_slice(&service.store.read("journal.json").unwrap().unwrap()).unwrap();
        assert!(saved.reenrollment);
        assert_eq!(saved.sequence, 5);
        assert_eq!(service.tick().await, Err(Failure::Unsupported));
        assert_eq!(gateway.pings.load(Ordering::SeqCst), 6);
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn revoked_ping_failure_keeps_revoked_health_and_hourly_schedule() {
        let (mut service, gateway, directory) = test_service();
        service.journal.identity =
            Some(serde_json::from_value(fixtures()["DeviceIdentity"].clone()).unwrap());
        service.journal.revoked = true;
        service.state = EndpointState::Revoked;
        gateway.ping_unavailable.store(true, Ordering::SeqCst);
        assert_eq!(service.tick().await, Err(Failure::Unavailable));
        assert_eq!(service.health().state, EndpointState::Revoked);
        assert!(service.journal.revoked);
        assert!((1..=3600000).contains(&service.next_delay_millis()));
        assert_eq!(gateway.pings.load(Ordering::SeqCst), 1);
        assert_eq!(gateway.polls.load(Ordering::SeqCst), 0);
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn manual_check_pings_once_and_only_follows_server_changes() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let (mut service, gateway, directory) = test_service();
        service.journal.identity =
            Some(serde_json::from_value(fixtures()["DeviceIdentity"].clone()).unwrap());
        service.journal.revoked = true;
        service.state = EndpointState::Revoked;
        let fingerprint = service.key.fingerprint();
        let device_id = service.device();
        let activity = service.activity.clone();
        let schedule_changed = service.schedule_changed.clone();
        let service = Arc::new(tokio::sync::Mutex::new(service));
        let contracts = crate::contracts::Contracts::new().unwrap();
        for (index, state, action) in [
            (1, EndpointState::Revoked, EndpointConnectionAction::Wait),
            (
                2,
                EndpointState::Unenrolled,
                EndpointConnectionAction::Enroll,
            ),
        ] {
            *gateway.directive.lock().unwrap() = Some(EndpointConnectionDirective {
                device_id: device_id.clone(),
                state,
                action,
                report_sequence: 7,
                next_interval_ms: 5000,
            });
            let request = ClientIpcRequest {
                protocol_version: 1,
                request_id: format!("manual-check-{index}"),
                operation: ClientIpcOperation::CheckIn,
            };
            let bytes = contracts.encode("ClientIpcRequest", &request).unwrap();
            let (mut stream, mut peer) = tokio::io::duplex(16384);
            peer.write_u32(bytes.len() as u32).await.unwrap();
            peer.write_all(&bytes).await.unwrap();
            crate::ipc::connection(
                &mut stream,
                "authorized",
                &["authorized".into()],
                &service,
                &contracts,
                &activity,
            )
            .await
            .unwrap();
            let mut response = vec![0; peer.read_u32().await.unwrap() as usize];
            peer.read_exact(&mut response).await.unwrap();
            let response: ClientIpcResponse =
                contracts.decode("ClientIpcResponse", &response).unwrap();
            assert_eq!(gateway.pings.load(Ordering::SeqCst), index);
            assert_eq!(gateway.polls.load(Ordering::SeqCst), 0);
            tokio::time::timeout(
                std::time::Duration::from_millis(100),
                schedule_changed.notified(),
            )
            .await
            .unwrap();
            let state = service.lock().await;
            assert_eq!(state.key.fingerprint(), fingerprint);
            assert!(!state.health().ready);
            if index == 1 {
                assert!(response.error.is_none());
                assert!(state.journal.revoked);
                assert!(state.journal.identity.is_some());
            } else {
                // Mock discovery is unavailable, but the changed server state is applied durably.
                assert_eq!(response.error, Some(ErrorCode::Unsupported));
                assert!(!state.journal.revoked);
                assert!(state.journal.identity.is_none());
                assert!(state.journal.reenrollment);
                assert_eq!(state.journal.sequence, 7);
            }
        }
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn deletion_between_ping_and_check_in_recovers_the_servers_report_sequence() {
        let (mut service, gateway, directory) = test_service();
        let mut identity: DeviceIdentity =
            serde_json::from_value(fixtures()["DeviceIdentity"].clone()).unwrap();
        identity.expires_at_unix_ms = crate::now() + 60000;
        service.journal.identity = Some(identity);
        service.journal.sequence = 4;
        gateway.deleted.store(true, Ordering::SeqCst);
        assert_eq!(service.tick().await, Err(Failure::EnrollmentRequired));
        assert!(service.journal.identity.is_none());
        assert!(!service.journal.revoked);
        *gateway.directive.lock().unwrap() = Some(EndpointConnectionDirective {
            device_id: service.device(),
            state: EndpointState::Unenrolled,
            action: EndpointConnectionAction::Enroll,
            report_sequence: 5,
            next_interval_ms: 5000,
        });
        assert_eq!(service.tick().await, Err(Failure::Unsupported));
        assert_eq!(service.journal.sequence, 5);
        assert!(service.journal.pending_report.is_none());
        assert_eq!(gateway.pings.load(Ordering::SeqCst), 2);
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn explicit_revoked_request_keeps_identity_blocked_when_discovery_is_unavailable() {
        let (mut service, gateway, directory) = test_service();
        service.journal.revoked = true;
        service.state = EndpointState::Revoked;
        let fingerprint = service.key.fingerprint();
        *gateway.directive.lock().unwrap() = Some(EndpointConnectionDirective {
            device_id: service.device(),
            state: EndpointState::Revoked,
            action: EndpointConnectionAction::Wait,
            report_sequence: 7,
            next_interval_ms: 3600000,
        });
        assert_eq!(service.request_approval().await, Err(Failure::Unsupported));
        assert_eq!(service.key.fingerprint(), fingerprint);
        assert!(service.journal.revoked);
        assert!(!service.health().ready);
        assert_eq!(gateway.pings.load(Ordering::SeqCst), 1);
        assert_eq!(gateway.polls.load(Ordering::SeqCst), 0);
        assert!((1..=3600000).contains(&service.next_delay_millis()));
        *gateway.directive.lock().unwrap() = Some(EndpointConnectionDirective {
            device_id: service.device(),
            state: EndpointState::Unenrolled,
            action: EndpointConnectionAction::Enroll,
            report_sequence: 7,
            next_interval_ms: 5000,
        });
        // An admin may already have reopened the server while this client was sleeping.
        assert_eq!(service.request_approval().await, Err(Failure::Unsupported));
        assert!(!service.journal.revoked);
        assert!(service.journal.reenrollment);
        assert_eq!(service.journal.sequence, 7);
        assert_eq!(service.key.fingerprint(), fingerprint);
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn expired_pending_request_keeps_pinging_and_retries_enrollment() {
        let (mut service, gateway, directory) = test_service();
        service.journal.challenge = Some(EndpointEnrollmentChallenge {
            enrollment_id: "e".repeat(32),
            device_code: "d".repeat(64),
            user_code: "A".repeat(16),
            verification_uri: "https://control.example.test/console/#enroll".into(),
            expires_at_unix_ms: crate::now() - 1000,
            poll_interval_seconds: 5,
        });
        *gateway.directive.lock().unwrap() = Some(EndpointConnectionDirective {
            device_id: service.device(),
            state: EndpointState::Pending,
            action: EndpointConnectionAction::Enroll,
            report_sequence: 0,
            next_interval_ms: 5000,
        });
        assert_eq!(service.tick().await, Err(Failure::Expired));
        assert!(service.journal.challenge.is_none());
        assert!(service.journal.reenrollment);
        assert_eq!(service.tick().await, Err(Failure::Unsupported));
        assert_eq!(gateway.pings.load(Ordering::SeqCst), 2);
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn expired_certificate_attempts_recovery_without_revoking_or_sending_check_in() {
        let (mut service, gateway, directory) = test_service();
        let mut identity: DeviceIdentity =
            serde_json::from_value(fixtures()["DeviceIdentity"].clone()).unwrap();
        identity.expires_at_unix_ms = crate::now() - 1000;
        service.journal.identity = Some(identity);
        assert_eq!(service.tick().await, Err(Failure::Unsupported));
        assert_eq!(gateway.polls.load(Ordering::SeqCst), 0);
        assert!(!service.journal.revoked);
        assert!(service.journal.identity.is_some());
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn the_interval_follows_control_and_invalid_values_are_rejected() {
        let (mut service, gateway, directory) = test_service();
        let mut identity: DeviceIdentity =
            serde_json::from_value(fixtures()["DeviceIdentity"].clone()).unwrap();
        identity.expires_at_unix_ms = crate::now() + 60000;
        service.journal.identity = Some(identity);
        service.tick().await.unwrap();
        assert_eq!(service.next_delay_millis(), 500);
        service.journal.last_success = Some(crate::now());
        service.tick().await.unwrap();
        assert_eq!(gateway.polls.load(Ordering::SeqCst), 1);
        gateway.interval_ms.store(2000, Ordering::SeqCst);
        service.journal.last_success = Some(crate::now() - 2000);
        service.tick().await.unwrap();
        assert_eq!(service.next_delay_millis(), 2000);
        service.journal.last_success = Some(crate::now());
        service.tick().await.unwrap();
        assert_eq!(gateway.polls.load(Ordering::SeqCst), 2);
        gateway.interval_ms.store(499, Ordering::SeqCst);
        service.journal.last_success = Some(crate::now() - 2000);
        assert_eq!(service.tick().await, Err(Failure::Validation));
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[tokio::test]
    async fn polls_continue_during_a_job_and_revocation_cancels_it() {
        let (mut service, gateway, directory) = test_service();
        let fixtures: serde_json::Value = serde_json::from_str(include_str!(
            "../../../tests/fixtures/contracts/v1/valid.json"
        ))
        .unwrap();
        let mut identity: DeviceIdentity =
            serde_json::from_value(fixtures["DeviceIdentity"].clone()).unwrap();
        identity.expires_at_unix_ms = crate::now() + 60000;
        service.journal.identity = Some(identity);
        service.config.deployment = Some(crate::deployment::Settings {
            organization_keys: vec![],
            release_keys: vec![],
        });
        service.remote_job = Some(crate::remote::Job::waiting(
            serde_json::from_value(fixtures["RemoteToolTask"].clone()).unwrap(),
        ));
        tokio::time::timeout(std::time::Duration::from_secs(1), service.tick())
            .await
            .unwrap()
            .unwrap();
        assert!(service.health().ready);
        assert_eq!(service.next_delay_millis(), 500);
        tokio::time::sleep(std::time::Duration::from_millis(550)).await;
        tokio::time::timeout(std::time::Duration::from_secs(1), service.tick())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(gateway.polls.load(Ordering::SeqCst), 2);
        assert_eq!(gateway.desired.load(Ordering::SeqCst), 2);
        assert_eq!(service.health().successful_check_ins, 2);
        gateway.revoked.store(true, Ordering::SeqCst);
        tokio::time::sleep(std::time::Duration::from_millis(550)).await;
        assert_eq!(service.tick().await, Err(Failure::Revoked));
        assert_eq!(service.health().state, EndpointState::Revoked);
        let mut job = service.remote_job.take().unwrap();
        let result = tokio::time::timeout(std::time::Duration::from_secs(1), async {
            loop {
                if let Some(result) = job.completed().await {
                    break result;
                }
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert!(result.output.is_none());
        assert_eq!(result.error, Some(ErrorCode::Forbidden));
        drop(service);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
