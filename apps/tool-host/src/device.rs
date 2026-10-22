// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The host as an enrolled device of Control (README.md, "Enrollment"). It enrolls like any client
//! device, with its own key and a CSR an administrator approves, so it joins Control's device
//! groups and placement sends it calls the same way. Once enrolled it registers as a Tool Host,
//! fetches its assignments, puts each deployment's package, service profile and globals into the
//! store by digest, and checks in reporting the tools whose content it holds. Control renews the
//! certificate on check-in; an expired one is recovered by enrolling the same key again.

use crate::config::Config;
use crate::control::{Control, ControlError};
use crate::{digest, now_ms, store};
use olo_toolgate_contracts::v2::{
    PackageDeployment, ProtocolRange, RuntimeTier, ToolHostAssignments, ToolHostRegistration,
};
use olo_toolgate_contracts::{
    ClientPlatform, ClientReport, DeviceIdentity, EndpointCheckIn, EndpointCheckInAck,
    EndpointConnectionDirective, EndpointEnrollmentChallenge, EndpointEnrollmentPoll,
    EndpointEnrollmentResult, EndpointEnrollmentStart, EndpointPresencePing, EnrollmentState,
    LocalToolInfo,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

const ENDPOINT: &str = "/api/control/v1/endpoint";
const TOOL_HOSTS: &str = "/api/control/v1/tool-hosts";
const VERSION: &str = env!("CARGO_PKG_VERSION");
/// Control refuses a package over this size on upload.
const MAX_PACKAGE_BYTES: usize = 64 * 1024 * 1024;
/// Waits after a failure, and before the first poll of a new enrollment.
const RETRY_MS: u64 = 5_000;

/// What survives a restart: the certificate Control issued, an enrollment still waiting for
/// approval, and the last check-in sequence Control acknowledged.
#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Journal {
    format_version: u64,
    tenant_id: String,
    server_url: String,
    manifest: Option<olo_toolgate_contracts::v2::TenantDiscovery>,
    discovery_trust: Option<olo_toolgate_tenant_identity::Trust>,
    identity: Option<DeviceIdentity>,
    challenge: Option<EndpointEnrollmentChallenge>,
    sequence: u64,
}

pub struct Device {
    tenant: String,
    host_id: String,
    pool_id: String,
    store_dir: PathBuf,
    dir: PathBuf,
    key: rcgen::KeyPair,
    control: Arc<Control>,
    journal: Journal,
    /// The certificate `control` presents now.
    presented: Option<String>,
    registered: bool,
}

impl Device {
    /// Opens the identity directory, creating the device key on first start.
    pub fn open(config: &Config, control: Arc<Control>) -> Result<Device, String> {
        config.validate()?;
        let enrollment = config
            .enrollment
            .as_ref()
            .ok_or("the configuration has no enrollment")?;
        let dir = enrollment.identity_dir.clone();
        std::fs::create_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        let journal_path = dir.join("device.json");
        let (journal, fresh): (Journal, bool) = match std::fs::read(&journal_path) {
            Ok(bytes) => (
                serde_json::from_slice(&bytes)
                    .map_err(|_| "unsupported Tool Host identity state")?,
                false,
            ),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => (
                Journal {
                    format_version: 3,
                    tenant_id: config.control.tenant_id.clone(),
                    server_url: olo_toolgate_tenant_identity::origin(&config.control.url)
                        .map_err(|_| "HTTPS discovery origin required")?,
                    ..Journal::default()
                },
                true,
            ),
            Err(_) => return Err("Tool Host identity state unavailable".into()),
        };
        if journal.format_version != 3
            || journal.tenant_id != config.control.tenant_id
            || journal.server_url
                != olo_toolgate_tenant_identity::origin(&config.control.url)
                    .map_err(|_| "HTTPS discovery origin required")?
            || journal.manifest.is_some() != journal.discovery_trust.is_some()
            || journal.manifest.as_ref().is_some_and(|manifest| {
                manifest.tenant_id != journal.tenant_id
                    || manifest.control_url != journal.server_url
            })
            || journal.discovery_trust.as_ref().is_some_and(|trust| {
                trust.tenant_id != journal.tenant_id
                    || journal.manifest.as_ref().is_none_or(|manifest| {
                        trust.version != manifest.key_set_version
                            || trust.issued_at != manifest.issued_at_unix_ms
                    })
            })
            || journal.identity.as_ref().is_some_and(|identity| {
                identity.tenant_id != journal.tenant_id || identity.device_id != config.host_id
            })
        {
            return Err("Tool Host tenant identity mismatch".into());
        }
        let key_path = dir.join("device-key.pem");
        if fresh && key_path.exists() {
            return Err("unsupported Tool Host identity state".into());
        }
        if !key_path.exists() && (journal.identity.is_some() || journal.challenge.is_some()) {
            return Err("Tool Host device key is missing".into());
        }
        if fresh {
            write_atomic(
                &journal_path,
                &serde_json::to_vec(&journal)
                    .map_err(|_| "Tool Host identity serialization failed")?,
            )?;
        }
        let key = match std::fs::read_to_string(&key_path) {
            Ok(pem) => rcgen::KeyPair::from_pem(&pem)
                .map_err(|e| format!("{}: {e}", key_path.display()))?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                let key = rcgen::KeyPair::generate().map_err(|e| format!("device key: {e}"))?;
                write_private(&key_path, key.serialize_pem().as_bytes())?;
                key
            }
            Err(e) => return Err(format!("{}: {e}", key_path.display())),
        };
        let device = Device {
            tenant: config.control.tenant_id.clone(),
            host_id: config.host_id.clone(),
            pool_id: enrollment.pool_id.clone(),
            store_dir: config.store_dir.clone(),
            dir,
            key,
            control,
            journal,
            presented: None,
            registered: false,
        };
        device.save()?;
        Ok(device)
    }

    /// Enrolls, then keeps the store and Control's view of this host current, until the process stops.
    pub async fn run(mut self) {
        loop {
            let wait = match self.step().await {
                Ok(wait) => wait,
                Err(e) => {
                    tracing::warn!(error = %e, "tool host sync failed");
                    RETRY_MS
                }
            };
            tokio::time::sleep(Duration::from_millis(wait.clamp(500, 3_600_000))).await;
        }
    }

    /// One round; answers how long to wait before the next.
    pub async fn step(&mut self) -> Result<u64, String> {
        if self
            .journal
            .manifest
            .as_ref()
            .is_none_or(|manifest| manifest.expires_at_unix_ms <= now_ms().saturating_add(60000))
        {
            self.refresh_discovery().await?;
        }
        if self
            .journal
            .identity
            .as_ref()
            .is_some_and(|identity| identity.expires_at_unix_ms <= now_ms())
        {
            self.journal.identity = None;
            self.save()?;
        }
        let Some(identity) = self.journal.identity.clone() else {
            return self.enroll().await;
        };
        self.accept(identity.clone())?;
        self.present(&identity)?;
        match self.sync().await {
            Err(Failure::Control(ControlError::Refused(410))) => {
                // The device was deleted in Control: start over with a fresh approval.
                self.journal.identity = None;
                self.journal.challenge = None;
                self.journal.sequence = 0;
                self.registered = false;
                self.save()?;
                Err("Control no longer knows this device; enrolling again".into())
            }
            Err(Failure::Control(ControlError::Refused(409))) => {
                self.resync().await?;
                Ok(RETRY_MS)
            }
            Err(e) => Err(e.to_string()),
            Ok(wait) => Ok(wait),
        }
    }

    async fn sync(&mut self) -> Result<u64, Failure> {
        if !self.registered {
            let registration = ToolHostRegistration {
                host_id: self.host_id.clone(),
                pool_id: self.pool_id.clone(),
                version: VERSION.into(),
                protocol: ProtocolRange { min: 2, max: 2 },
                launcher_version: VERSION.into(),
                tiers: vec![RuntimeTier::HostNative],
            };
            let _: serde_json::Value = self
                .control
                .post_json(&format!("{TOOL_HOSTS}/registrations"), &registration, &[])
                .await?;
            self.registered = true;
            tracing::info!(host_id = %self.host_id, "tool host registered");
        }
        let assignments: ToolHostAssignments = self
            .control
            .get_json(&format!("{TOOL_HOSTS}/assignments"))
            .await?;
        if assignments.host_id != self.host_id {
            return Err(Failure::Local("assignments are for another host".into()));
        }
        let mut held = BTreeSet::new();
        for deployment in &assignments.deployments {
            match self.fetch(deployment).await {
                Ok(()) => {
                    held.insert(deployment.package_digest.clone());
                }
                Err(e) => tracing::warn!(
                    package = %deployment.package_id,
                    error = %e,
                    "deployment content not fetched"
                ),
            }
        }
        // Report only tools this host can run now, so placement never picks it for one it cannot.
        let tools = assignments
            .tools
            .into_iter()
            .filter(|tool| held.contains(&tool.package_digest))
            .filter_map(|tool| {
                Some(LocalToolInfo {
                    package_digest: store::hex(&tool.package_digest)?.to_string(),
                    tool_id: tool.tool_id,
                    action: tool.action,
                    description: tool.description,
                    enabled: tool.enabled,
                    input_schema: tool.input_schema,
                    tool_digest: tool.tool_digest,
                })
            })
            .collect();
        self.check_in(tools).await
    }

    async fn check_in(&mut self, tools: Vec<LocalToolInfo>) -> Result<u64, Failure> {
        let request = EndpointCheckIn {
            sequence: self.journal.sequence + 1,
            report: ClientReport {
                device_id: self.host_id.clone(),
                client_version: VERSION.into(),
                applied_revision: 0,
                packages: vec![],
                power: None,
            },
            adoption_digest: None,
            local_tools: Some(tools),
        };
        let ack: EndpointCheckInAck = self
            .control
            .post_json(&format!("{ENDPOINT}/check-in"), &request, &[])
            .await?;
        if ack.device_id != self.host_id || ack.sequence != request.sequence {
            return Err(Failure::Local(
                "Control acknowledged another check-in".into(),
            ));
        }
        self.journal.sequence = ack.sequence;
        if let Some(identity) = ack.identity {
            self.refresh_for_identity(&identity)
                .await
                .map_err(Failure::Local)?;
            self.accept(identity).map_err(Failure::Local)?;
        }
        self.save().map_err(Failure::Local)?;
        Ok(ack.next_interval_ms)
    }

    /// Puts a deployment's package, service profile and globals into the store, each checked
    /// against its digest; what is already there is kept.
    async fn fetch(&self, deployment: &PackageDeployment) -> Result<(), Failure> {
        let digest = &deployment.package_digest;
        let hex = store::hex(digest).ok_or_else(|| Failure::Local("bad package digest".into()))?;
        let path = self.store_dir.join("packages").join(format!("{hex}.tgpkg"));
        if !path.is_file() {
            let data = self
                .control
                .get_bytes(
                    &format!("{TOOL_HOSTS}/packages/{digest}"),
                    MAX_PACKAGE_BYTES,
                )
                .await?;
            let report = toolgate_cli::package::validate(&data)
                .map_err(|e| Failure::Local(format!("the package is {e}")))?;
            if &report.package_digest != digest {
                return Err(Failure::Local(
                    "the package does not match its digest".into(),
                ));
            }
            write_atomic(&path, &data).map_err(Failure::Local)?;
        }
        for (kind, digest) in [
            ("profiles", &deployment.service_profile_digest),
            ("globals", &deployment.globals_digest),
        ] {
            let hex =
                store::hex(digest).ok_or_else(|| Failure::Local("bad document digest".into()))?;
            let path = self.store_dir.join(kind).join(format!("{hex}.json"));
            if path.is_file() {
                continue;
            }
            let value: serde_json::Value = self
                .control
                .get_json(&format!("{TOOL_HOSTS}/documents/{digest}"))
                .await?;
            if &digest::canonical(&value).map_err(Failure::Local)? != digest {
                return Err(Failure::Local(format!(
                    "a {kind} document does not match its digest"
                )));
            }
            let bytes = serde_json::to_vec(&value).map_err(|e| Failure::Local(e.to_string()))?;
            write_atomic(&path, &bytes).map_err(Failure::Local)?;
        }
        Ok(())
    }

    /// Starts an enrollment, or polls the one waiting for an administrator.
    async fn enroll(&mut self) -> Result<u64, String> {
        let now = now_ms();
        let challenge = match &self.journal.challenge {
            Some(challenge) if challenge.expires_at_unix_ms > now => challenge.clone(),
            _ => {
                let challenge: EndpointEnrollmentChallenge = self
                    .control
                    .post_json(
                        &format!("{ENDPOINT}/enrollments"),
                        &self.announcement()?,
                        &[],
                    )
                    .await
                    .map_err(|e| format!("enrollment refused: {e}"))?;
                tracing::info!(
                    host_id = %self.host_id,
                    user_code = %challenge.user_code,
                    "tool host waiting for enrollment approval"
                );
                self.journal.challenge = Some(challenge.clone());
                self.save()?;
                challenge
            }
        };
        let poll = EndpointEnrollmentPoll {
            enrollment_id: challenge.enrollment_id.clone(),
            device_code: challenge.device_code.clone(),
        };
        let result: EndpointEnrollmentResult = self
            .control
            .post_json(&format!("{ENDPOINT}/enrollments/poll"), &poll, &[])
            .await
            .map_err(|e| format!("enrollment poll refused: {e}"))?;
        match result.state {
            EnrollmentState::Consumed => {
                let identity = result.identity.ok_or("Control issued no certificate")?;
                self.journal.challenge = None;
                self.refresh_for_identity(&identity).await?;
                self.accept(identity)?;
                self.save()?;
                tracing::info!(host_id = %self.host_id, "tool host enrolled");
                Ok(0)
            }
            EnrollmentState::Denied | EnrollmentState::Expired => {
                self.journal.challenge = None;
                self.save()?;
                Err("the enrollment was denied or expired; starting another".into())
            }
            _ => Ok(challenge.poll_interval_seconds.saturating_mul(1000)),
        }
    }

    /// Learns the check-in sequence Control last accepted, after a check-in it refused as out of order.
    async fn resync(&mut self) -> Result<(), String> {
        let ping = EndpointPresencePing {
            announcement: self.announcement()?,
            utc_offset: "+00:00".into(),
        };
        let directive: EndpointConnectionDirective = self
            .control
            .post_json(&format!("{ENDPOINT}/ping"), &ping, &[])
            .await
            .map_err(|e| format!("presence refused: {e}"))?;
        if directive.device_id != self.host_id {
            return Err("Control answered for another device".into());
        }
        self.journal.sequence = directive.report_sequence;
        self.save()
    }

    fn announcement(&self) -> Result<EndpointEnrollmentStart, String> {
        let mut params =
            rcgen::CertificateParams::new(Vec::<String>::new()).map_err(|e| format!("CSR: {e}"))?;
        let mut name = rcgen::DistinguishedName::new();
        name.push(rcgen::DnType::CommonName, self.host_id.as_str());
        params.distinguished_name = name;
        let csr = params
            .serialize_request(&self.key)
            .and_then(|request| request.pem())
            .map_err(|e| format!("CSR: {e}"))?;
        Ok(EndpointEnrollmentStart {
            device_id: self.host_id.clone(),
            client_version: VERSION.into(),
            platform: platform(),
            csr_pem: csr,
            capabilities: vec!["endpoint.identity.v1".into(), "endpoint.check-in.v1".into()],
        })
    }

    async fn refresh_discovery(&mut self) -> Result<(), String> {
        let bytes = self
            .control
            .discovery()
            .await
            .map_err(|_| "Tenant discovery unavailable")?;
        let verified = olo_toolgate_tenant_identity::verify_https_refresh(
            &bytes,
            &self.journal.server_url,
            &self.tenant,
            self.journal.discovery_trust.as_ref(),
            now_ms(),
        )
        .map_err(|_| "Tenant discovery verification failed")?;
        if verified.manifest.control_url != self.journal.server_url {
            return Err("Tenant Control origin mismatch".into());
        }
        self.journal.manifest = Some(verified.manifest);
        self.journal.discovery_trust = Some(verified.trust);
        self.save()
    }
    async fn refresh_for_identity(&mut self, identity: &DeviceIdentity) -> Result<(), String> {
        if self.journal.manifest.as_ref().is_none_or(|manifest| {
            !manifest
                .device_authorities
                .iter()
                .any(|authority| authority.certificate_pem == identity.issuer_certificate_pem)
        }) {
            self.refresh_discovery().await?;
        }
        Ok(())
    }
    /// Verify tenant CA, certificate identity, private-key possession and client-only privileges.
    fn accept(&mut self, identity: DeviceIdentity) -> Result<(), String> {
        use rcgen::PublicKeyData;
        let manifest = self
            .journal
            .manifest
            .as_ref()
            .ok_or("Verified tenant discovery required")?;
        let fingerprint = olo_toolgate_tenant_identity::digest(&self.key.subject_public_key_info());
        olo_toolgate_tenant_identity::verify_tenant_identity(
            &identity,
            manifest,
            &fingerprint,
            &self.host_id,
            now_ms(),
        )
        .map_err(|_| "Issued tenant certificate rejected")?;
        self.journal.identity = Some(identity);
        Ok(())
    }

    fn present(&mut self, identity: &DeviceIdentity) -> Result<(), String> {
        if self.presented.as_deref() == Some(identity.certificate_pem.as_str()) {
            return Ok(());
        }
        let pem = format!("{}{}", identity.certificate_pem, self.key.serialize_pem());
        self.control.use_identity(pem.as_bytes())?;
        self.presented = Some(identity.certificate_pem.clone());
        Ok(())
    }

    fn save(&self) -> Result<(), String> {
        let bytes = serde_json::to_vec(&self.journal).map_err(|e| e.to_string())?;
        write_atomic(&self.dir.join("device.json"), &bytes)
    }
}

#[derive(Debug)]
enum Failure {
    Control(ControlError),
    Local(String),
}

impl From<ControlError> for Failure {
    fn from(e: ControlError) -> Failure {
        Failure::Control(e)
    }
}

impl std::fmt::Display for Failure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Failure::Control(e) => write!(f, "{e}"),
            Failure::Local(e) => f.write_str(e),
        }
    }
}

fn platform() -> ClientPlatform {
    if cfg!(windows) {
        ClientPlatform::Windows
    } else if cfg!(target_os = "macos") {
        ClientPlatform::Macos
    } else {
        ClientPlatform::Linux
    }
}

/// Writes under a temporary name and renames, so a reader never sees half a file.
fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let parent = path.parent().ok_or("a path without a directory")?;
    std::fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    let staging = parent.join(format!(".{}", crate::random_hex(8)));
    std::fs::write(&staging, bytes)
        .and_then(|()| std::fs::rename(&staging, path))
        .map_err(|e| {
            let _ = std::fs::remove_file(&staging);
            format!("{}: {e}", path.display())
        })
}

fn write_private(path: &Path, bytes: &[u8]) -> Result<(), String> {
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    options
        .open(path)
        .and_then(|mut file| file.write_all(bytes))
        .map_err(|e| format!("{}: {e}", path.display()))
}
