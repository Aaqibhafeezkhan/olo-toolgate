// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Public metadata comes only from the fixed tenant Control origin, without service credentials.
use olo_toolgate_contracts::v2::TenantDiscovery;
use olo_toolgate_tenant_identity::{origin, Trust};
use serde::{Deserialize, Serialize};
use std::{path::Path, sync::Mutex, time::Duration};

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Settings {
    pub control_origin: String,
    pub gateway_origin: String,
}
impl Settings {
    pub fn validate(&self) -> Result<(), &'static str> {
        if origin(&self.control_origin).map_err(|_| "HTTPS discovery Control origin required")?
            != self.control_origin
            || origin(&self.gateway_origin).map_err(|_| "HTTPS public Gateway origin required")?
                != self.gateway_origin
        {
            return Err("canonical discovery origins required");
        }
        Ok(())
    }
}
pub struct PublicDiscovery {
    settings: Settings,
    tenant: String,
    client: reqwest::Client,
    trust: Mutex<Option<Trust>>,
}
pub enum Refused {
    Unavailable,
    TenantUnavailable,
}
pub struct Document {
    pub bytes: Vec<u8>,
    pub manifest: TenantDiscovery,
}
impl PublicDiscovery {
    pub fn new(
        settings: Settings,
        tenant: String,
        ca: Option<&Path>,
    ) -> Result<Self, &'static str> {
        settings.validate()?;
        let _ = rustls::crypto::ring::default_provider().install_default();
        let mut client = reqwest::Client::builder()
            .https_only(true)
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .tls_sslkeylogfile(false)
            .connect_timeout(Duration::from_secs(2))
            .timeout(Duration::from_secs(3));
        if let Some(path) = ca {
            let bytes = crate::config::read_bytes(path)?;
            let roots = reqwest::Certificate::from_pem_bundle(&bytes)
                .map_err(|_| "discovery TLS roots required")?;
            if roots.is_empty() {
                return Err("discovery TLS roots required");
            }
            client = client.tls_certs_only(roots);
        }
        Ok(Self {
            settings,
            tenant,
            client: client
                .build()
                .map_err(|_| "discovery transport unavailable")?,
            trust: Mutex::new(None),
        })
    }
    pub async fn fetch(&self) -> Result<Document, Refused> {
        let mut response = self
            .client
            .get(format!(
                "{}/.well-known/olo-toolgate-client",
                self.settings.control_origin
            ))
            .header("Toolgate-Tenant-ID", &self.tenant)
            .send()
            .await
            .map_err(|_| Refused::Unavailable)?;
        if response.status() == reqwest::StatusCode::NOT_FOUND {
            return Err(Refused::TenantUnavailable);
        }
        if !response.status().is_success()
            || response
                .content_length()
                .is_some_and(|length| length > 131072)
        {
            return Err(Refused::Unavailable);
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| Refused::Unavailable)? {
            if bytes.len() + chunk.len() > 131072 {
                return Err(Refused::Unavailable);
            }
            bytes.extend_from_slice(&chunk);
        }
        // No response is served from a cache: suspended/unknown tenants keep the same public 404.
        // The native client persists its own rollback floor across Gateway restarts.
        let mut pin = self.trust.lock().map_err(|_| Refused::Unavailable)?;
        let verified = olo_toolgate_tenant_identity::verify_https_refresh(
            &bytes,
            &self.settings.control_origin,
            &self.tenant,
            pin.as_ref(),
            crate::unix_ms().ok_or(Refused::Unavailable)?,
        )
        .map_err(|_| Refused::Unavailable)?;
        if verified.manifest.control_url != self.settings.control_origin
            || verified.manifest.gateway_url != self.settings.gateway_origin
        {
            return Err(Refused::Unavailable);
        }
        *pin = Some(verified.trust);
        Ok(Document {
            bytes,
            manifest: verified.manifest,
        })
    }
}
