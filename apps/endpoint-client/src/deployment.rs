// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Independent release/deployment trust and durable generation fencing. No package grants execution.
use crate::{contracts::Contracts, storage::ProtectedStore, Failure, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use olo_toolgate_contracts::*;
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Settings {
    pub release_keys: Vec<FleetTrustKey>,
    pub organization_keys: Vec<FleetTrustKey>,
}
impl Settings {
    /// Device enrollment trust cannot become release or organization deployment trust.
    pub fn separate_device_ca(&self, pem: &str) -> Result<()> {
        use x509_parser::prelude::FromDer;
        let der = crate::identity::certificate_der(pem)?;
        let (_, ca) = x509_parser::certificate::X509Certificate::from_der(&der)
            .map_err(|_| Failure::Validation)?;
        let public = ca.public_key().parsed().map_err(|_| Failure::Validation)?;
        let x509_parser::public_key::PublicKey::RSA(key) = public else {
            return Err(Failure::Validation);
        };
        let modulus = key.modulus.strip_prefix(&[0]).unwrap_or(key.modulus);
        for entry in self.release_keys.iter().chain(&self.organization_keys) {
            if decode(&entry.n)? == modulus {
                return Err(Failure::Unauthorized);
            }
        }
        Ok(())
    }
    pub fn validate(&self) -> Result<()> {
        let contracts = Contracts::new()?;
        let mut moduli = BTreeSet::new();
        for keys in [&self.release_keys, &self.organization_keys] {
            if keys.is_empty() || keys.len() > 4 {
                return Err(Failure::Validation);
            }
            let mut ids = BTreeSet::new();
            for key in keys {
                contracts.encode("FleetTrustKey", key)?;
                let n = decode(&key.n)?;
                if ![256, 384, 512].contains(&n.len())
                    || n[0] & 0x80 == 0
                    || key.e != "AQAB"
                    || !ids.insert(&key.kid)
                    || !moduli.insert(key.n.clone())
                {
                    return Err(Failure::Validation);
                }
            }
        }
        Ok(())
    }
    pub fn verify<T: serde::de::DeserializeOwned>(
        &self,
        envelope: &FleetSignedDocument,
        typ: &str,
        model: &str,
    ) -> Result<(T, Vec<u8>)> {
        let contracts = Contracts::new()?;
        contracts.encode("FleetSignedDocument", envelope)?;
        let parts: Vec<_> = envelope.jws.split('.').collect();
        if parts.len() != 3 || parts[0].len() > 1024 || parts[1].len() > 87384 {
            return Err(Failure::Validation);
        }
        let header: FleetSignatureHeader =
            contracts.decode("FleetSignatureHeader", &decode(parts[0])?)?;
        if header.typ != typ {
            return Err(Failure::Unauthorized);
        }
        let keys = match typ {
            "toolgate-package-release+jws" => &self.release_keys,
            "toolgate-fleet-desired+jws" | "toolgate-fleet-artifact+jws" => &self.organization_keys,
            _ => return Err(Failure::Unauthorized),
        };
        let key = keys
            .iter()
            .find(|k| k.kid == header.kid)
            .ok_or(Failure::Unauthorized)?;
        let n = decode(&key.n)?;
        let e = decode(&key.e)?;
        let signature = decode(parts[2])?;
        let public = ring::signature::RsaPublicKeyComponents { n: &n, e: &e };
        public
            .verify(
                &ring::signature::RSA_PKCS1_2048_8192_SHA256,
                format!("{}.{}", parts[0], parts[1]).as_bytes(),
                &signature,
            )
            .map_err(|_| Failure::Unauthorized)?;
        let bytes = decode(parts[1])?;
        Ok((contracts.decode(model, &bytes)?, bytes))
    }
    pub fn release(&self, release: &FleetPackageRelease) -> Result<FleetPackageDocument> {
        let (document, bytes): (FleetPackageDocument, _) = self.verify(
            &release.release,
            "toolgate-package-release+jws",
            "FleetPackageDocument",
        )?;
        if bytes.len() > 32768
            || bytes.len() as u64 != release.size_bytes
            || crate::digest(&bytes) != release.manifest_digest
            || document.package_id != release.package_id
            || document.version != release.version
        {
            return Err(Failure::Unauthorized);
        }
        let runtimes: BTreeSet<_> = document.runtimes.iter().map(|r| &r.id).collect();
        let tools: BTreeSet<_> = document.tools.iter().map(|t| &t.tool_id).collect();
        let tests: BTreeSet<_> = document.self_tests.iter().map(|t| &t.tool_id).collect();
        if runtimes.len() != document.runtimes.len()
            || tools.len() != document.tools.len()
            || tests.len() != document.self_tests.len()
            || tools != tests
            || document
                .tools
                .iter()
                .any(|t| !runtimes.contains(&t.runtime_id))
        {
            return Err(Failure::Validation);
        }
        Ok(document)
    }
}
fn decode(value: &str) -> Result<Vec<u8>> {
    let bytes = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| Failure::Validation)?;
    if URL_SAFE_NO_PAD.encode(&bytes) != value {
        return Err(Failure::Validation);
    }
    Ok(bytes)
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Activation {
    desired: FleetSignedDocument,
    status: FleetClientStatus,
}

pub struct State {
    signed: Option<FleetSignedDocument>,
    pub desired: Option<FleetDesiredDocument>,
    pub status: FleetClientStatus,
    pub activated: bool,
}
impl Default for State {
    fn default() -> Self {
        Self {
            signed: None,
            desired: None,
            status: FleetClientStatus {
                ready: false,
                generation: 0,
                packages: vec![],
                error: None,
            },
            activated: false,
        }
    }
}
impl State {
    /// Signed intent is persisted before staging. Restart re-verifies it but cannot infer runtime health.
    pub fn open(store: &ProtectedStore, settings: &Settings) -> Result<Self> {
        settings.validate()?;
        let mut state = Self::default();
        if let Some(bytes) = store.read("fleet-intent.json")? {
            let signed: FleetSignedDocument =
                Contracts::new()?.decode("FleetSignedDocument", &bytes)?;
            let (desired, _): (FleetDesiredDocument, _) = settings.verify(
                &signed,
                "toolgate-fleet-desired+jws",
                "FleetDesiredDocument",
            )?;
            state.status.generation = desired.generation;
            state.desired = Some(desired);
        }
        if let Some(bytes) = store.read("fleet-active.json")? {
            let saved: Activation =
                serde_json::from_slice(&bytes).map_err(|_| Failure::Validation)?;
            let (desired, _): (FleetDesiredDocument, _) = settings.verify(
                &saved.desired,
                "toolgate-fleet-desired+jws",
                "FleetDesiredDocument",
            )?;
            if saved.status.generation != desired.generation
                || state
                    .desired
                    .as_ref()
                    .is_none_or(|intent| desired.generation > intent.generation)
            {
                return Err(Failure::Validation);
            }
            for assignment in desired.assignments {
                settings.release(&assignment.release)?;
            }
        }
        Ok(state)
    }
    pub fn accept(
        &mut self,
        store: &ProtectedStore,
        settings: &Settings,
        signed: &FleetSignedDocument,
        identity: &DeviceIdentity,
        server: &str,
        now: u64,
    ) -> Result<bool> {
        let (desired, _): (FleetDesiredDocument, _) =
            settings.verify(signed, "toolgate-fleet-desired+jws", "FleetDesiredDocument")?;
        if desired.device_id != identity.device_id
            || desired.tenant_id != identity.tenant_id
            || desired.server_id != server
            || desired.issued_at_unix_ms > now
            || desired.expires_at_unix_ms <= now
            || desired
                .expires_at_unix_ms
                .saturating_sub(desired.issued_at_unix_ms)
                > 300000
        {
            return Err(Failure::Unauthorized);
        }
        if let Some(before) = &self.desired {
            if desired.generation < before.generation
                || desired.generation == before.generation
                    && desired.assignments != before.assignments
            {
                return Err(Failure::Conflict);
            }
        }
        let mut packages = BTreeSet::new();
        for assignment in &desired.assignments {
            if !packages.insert(&assignment.release.package_id) {
                return Err(Failure::Validation);
            }
            settings.release(&assignment.release)?;
        }
        let changed = self
            .desired
            .as_ref()
            .is_none_or(|old| old.generation != desired.generation)
            || !self.activated;
        // Learning a new assignment revokes old package execution even if durable intent cannot be written.
        if changed {
            self.activated = false;
            self.status.ready = false;
        }
        store.write(
            "fleet-intent.json",
            &Contracts::new()?.encode("FleetSignedDocument", signed)?,
        )?;
        if changed {
            self.activated = false;
            self.status = FleetClientStatus {
                ready: false,
                generation: desired.generation,
                packages: desired
                    .assignments
                    .iter()
                    .map(|a| ReportedPackage {
                        package_id: a.release.package_id.clone(),
                        version: a.release.version.clone(),
                        state: PackageState::Staging,
                    })
                    .collect(),
                error: None,
            };
        }
        self.signed = Some(signed.clone());
        self.desired = Some(desired);
        Ok(changed)
    }
    pub fn candidate(
        &self,
        settings: &Settings,
        base: &crate::execution::Settings,
    ) -> Result<(crate::execution::Settings, Vec<FleetPackageDocument>)> {
        let desired = self.desired.as_ref().ok_or(Failure::Unavailable)?;
        let mut candidate = base.clone();
        let mut documents = vec![];
        for assignment in &desired.assignments {
            if assignment.desired_presence {
                let document = settings.release(&assignment.release)?;
                let platform = crate::platform::current();
                let arch = match std::env::consts::ARCH {
                    "x86_64" => FleetArchitecture::X8664,
                    "aarch64" => FleetArchitecture::Aarch64,
                    _ => return Err(Failure::Unsupported),
                };
                let minimum = semver::Version::parse(&document.minimum_client_version)
                    .map_err(|_| Failure::Validation)?;
                let version = semver::Version::parse(env!("CARGO_PKG_VERSION"))
                    .map_err(|_| Failure::Validation)?;
                if !document.platforms.contains(&platform)
                    || !document.architectures.contains(&arch)
                    || version < minimum
                {
                    return Err(Failure::Unsupported);
                }
                if document
                    .runtimes
                    .iter()
                    .any(|r| matches!(r.kind, LocalRuntimeKind::Batch | LocalRuntimeKind::Wasm))
                {
                    return Err(Failure::Unsupported);
                }
                candidate.runtimes.extend(document.runtimes.clone());
                candidate.tools.extend(document.tools.clone());
                documents.push(document);
            }
        }
        candidate.validate()?;
        Ok((candidate, documents))
    }
    pub fn failed(&mut self, failure: Failure) {
        self.activated = false;
        self.status.ready = false;
        self.status.error = Some(match failure {
            Failure::Unsupported => ErrorCode::Unsupported,
            Failure::Unauthorized | Failure::Revoked => ErrorCode::Forbidden,
            Failure::Expired => ErrorCode::Timeout,
            _ => ErrorCode::DependencyUnavailable,
        });
        for pkg in &mut self.status.packages {
            pkg.state = PackageState::Failed;
        }
    }
    pub fn activate(&mut self, store: &ProtectedStore) -> Result<()> {
        let desired = self.desired.as_ref().ok_or(Failure::Unavailable)?;
        if desired.expires_at_unix_ms <= crate::now() {
            return Err(Failure::Expired);
        }
        let mut status = self.status.clone();
        status.ready = true;
        status.error = None;
        for (pkg, assignment) in status.packages.iter_mut().zip(&desired.assignments) {
            pkg.state = if assignment.desired_presence {
                PackageState::Ready
            } else {
                PackageState::Absent
            };
        }
        // Atomic replace is the activation commit. Intent remains available after an interrupted update.
        store.write(
            "fleet-active.json",
            &serde_json::to_vec(&Activation {
                desired: self.signed.clone().ok_or(Failure::Unavailable)?,
                status: status.clone(),
            })
            .map_err(|_| Failure::Validation)?,
        )?;
        self.status = status;
        self.activated = true;
        Ok(())
    }
    pub fn deadline(&self) -> Result<u64> {
        if !self.activated {
            return Err(Failure::Unavailable);
        }
        self.desired
            .as_ref()
            .map(|d| d.expires_at_unix_ms)
            .filter(|t| *t > crate::now())
            .ok_or(Failure::Expired)
    }
}
