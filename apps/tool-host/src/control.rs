// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The host's calls to Control under its workload certificate. Per invocation (plan.md §11.3,
//! §12.8): claim the permit's nonce, fetch a secret for a running invocation, and report the
//! outcome. As an enrolled device (`device`): enroll, check in, register and fetch content. The
//! certificate is either configured or the one enrollment issued, swapped in as it renews.

use crate::config::ControlConfig;
use olo_toolgate_contracts::v2::{
    AdmissionResponse, Dispatch, InvocationResultReport, PermitConsumed, PermitConsumption,
    ResultMessage,
};
use olo_toolgate_contracts::{EnterpriseSecretDelivery, EnterpriseSecretDeliveryRequest};
use std::time::Duration;

const BASE: &str = "/api/control/v1/access";
/// Carries the fencing token on secret delivery, whose v1 body has no field for it.
pub const FENCING_HEADER: &str = "Toolgate-Fencing-Token";
const MAX_REPLY: usize = 1024 * 1024;
/// Pull mode, under the host's device certificate.
const DISPATCHES: &str = "/api/control/v1/tool-hosts/dispatches";
/// A relayed dispatch carries the call's arguments, at most the host's input ceiling.
const MAX_DISPATCH: usize = 2 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ControlError {
    /// Control answered and said no.
    Refused(u16),
    /// Control could not be reached or answered something unusable.
    Unavailable(String),
}

impl std::fmt::Display for ControlError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ControlError::Refused(status) => write!(f, "Control refused with {status}"),
            ControlError::Unavailable(why) => f.write_str(why),
        }
    }
}

pub struct Control {
    url: String,
    roots: Option<Vec<reqwest::Certificate>>,
    client: std::sync::RwLock<reqwest::Client>,
}

impl Control {
    pub fn new(config: &ControlConfig) -> Result<Control, String> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let roots = match &config.ca_certificate_path {
            Some(ca) => {
                let pem = std::fs::read(ca).map_err(|e| format!("{}: {e}", ca.display()))?;
                Some(
                    reqwest::Certificate::from_pem_bundle(&pem)
                        .map_err(|e| format!("{}: {e}", ca.display()))?,
                )
            }
            None => None,
        };
        let mut identity = None;
        if let (Some(cert), Some(key)) = (&config.client_certificate_path, &config.client_key_path)
        {
            let mut pem = std::fs::read(cert).map_err(|e| format!("{}: {e}", cert.display()))?;
            pem.extend(std::fs::read(key).map_err(|e| format!("{}: {e}", key.display()))?);
            identity = Some(pem);
        }
        let client = build(roots.as_ref(), identity.as_deref())?;
        Ok(Control {
            url: config.url.trim_end_matches('/').to_string(),
            roots,
            client: std::sync::RwLock::new(client),
        })
    }

    /// Presents this certificate chain and key (PEM) on every later call.
    pub fn use_identity(&self, pem: &[u8]) -> Result<(), String> {
        let client = build(self.roots.as_ref(), Some(pem))?;
        *self.client.write().unwrap_or_else(|e| e.into_inner()) = client;
        Ok(())
    }

    fn client(&self) -> reqwest::Client {
        self.client
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    /// Moves the invocation to EXECUTING by consuming the permit's nonce; the reply's fencing
    /// token is presented on every later call for this invocation.
    pub async fn consume(
        &self,
        consumption: &PermitConsumption,
    ) -> Result<PermitConsumed, ControlError> {
        self.post("/permits/consume", consumption, None).await
    }

    pub async fn secret(
        &self,
        invocation_id: &str,
        fencing_token: &str,
        name: &str,
    ) -> Result<String, ControlError> {
        let request = EnterpriseSecretDeliveryRequest {
            invocation_id: invocation_id.into(),
            name: name.into(),
        };
        let delivery: EnterpriseSecretDelivery = self
            .post("/secrets/deliver", &request, Some(fencing_token))
            .await?;
        Ok(delivery.value)
    }

    pub async fn report(&self, report: &InvocationResultReport) -> Result<(), ControlError> {
        let _: serde_json::Value = self
            .post("/effects/report", report, Some(&report.fencing_token))
            .await?;
        Ok(())
    }

    /// Pull mode: the oldest call Control holds for this host, which the host now owns.
    pub async fn next_dispatch(&self) -> Result<Option<Dispatch>, ControlError> {
        let bytes = self
            .send(
                &format!("{DISPATCHES}/next"),
                Some(b"{}".to_vec()),
                &[],
                MAX_DISPATCH,
            )
            .await?;
        if bytes.is_empty() {
            return Ok(None);
        }
        json(&bytes).map(Some)
    }

    /// Pull mode: the admission for a call taken from Control.
    pub async fn admission(&self, admission: &AdmissionResponse) -> Result<(), ControlError> {
        let path = format!("{DISPATCHES}/{}/admission", admission.invocation_id);
        let _: serde_json::Value = self.post_json(&path, admission, &[]).await?;
        Ok(())
    }

    /// Pull mode: the result of a call taken from Control.
    pub async fn relayed_result(&self, result: &ResultMessage) -> Result<(), ControlError> {
        let path = format!("{DISPATCHES}/{}/result", result.invocation_id);
        let _: serde_json::Value = self.post_json(&path, result, &[]).await?;
        Ok(())
    }

    async fn post<B: serde::Serialize, R: serde::de::DeserializeOwned>(
        &self,
        path: &str,
        body: &B,
        fencing_token: Option<&str>,
    ) -> Result<R, ControlError> {
        let headers: Vec<(&str, &str)> = fencing_token
            .map(|token| vec![(FENCING_HEADER, token)])
            .unwrap_or_default();
        self.post_json(&format!("{BASE}{path}"), body, &headers)
            .await
    }

    /// POSTs JSON to a Control path and reads a JSON reply; an empty reply reads as `{}`.
    pub async fn post_json<B: serde::Serialize, R: serde::de::DeserializeOwned>(
        &self,
        path: &str,
        body: &B,
        headers: &[(&str, &str)],
    ) -> Result<R, ControlError> {
        let body =
            serde_json::to_vec(body).map_err(|e| ControlError::Unavailable(e.to_string()))?;
        json(&self.send(path, Some(body), headers, MAX_REPLY).await?)
    }

    pub async fn get_json<R: serde::de::DeserializeOwned>(
        &self,
        path: &str,
    ) -> Result<R, ControlError> {
        json(&self.send(path, None, &[], MAX_REPLY).await?)
    }

    /// The exact bytes Control answers, at most `max`.
    pub async fn get_bytes(&self, path: &str, max: usize) -> Result<Vec<u8>, ControlError> {
        self.send(path, None, &[], max).await
    }

    async fn send(
        &self,
        path: &str,
        body: Option<Vec<u8>>,
        headers: &[(&str, &str)],
        max: usize,
    ) -> Result<Vec<u8>, ControlError> {
        let url = format!("{}{path}", self.url);
        let client = self.client();
        let mut request = match body {
            Some(body) => client
                .post(url)
                .header(reqwest::header::CONTENT_TYPE, "application/json")
                .body(body),
            None => client.get(url),
        };
        for (name, value) in headers {
            request = request.header(*name, *value);
        }
        let mut response = request
            .send()
            .await
            .map_err(|e| ControlError::Unavailable(e.without_url().to_string()))?;
        let status = response.status();
        if status.is_client_error() {
            return Err(ControlError::Refused(status.as_u16()));
        }
        if !status.is_success() {
            return Err(ControlError::Unavailable(format!(
                "Control answered {status}"
            )));
        }
        let too_large = || ControlError::Unavailable("Control's reply is too large".into());
        if response.content_length().is_some_and(|n| n > max as u64) {
            return Err(too_large());
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|e| ControlError::Unavailable(e.without_url().to_string()))?
        {
            if bytes.len() + chunk.len() > max {
                return Err(too_large());
            }
            bytes.extend_from_slice(&chunk);
        }
        Ok(bytes)
    }
}

fn json<R: serde::de::DeserializeOwned>(bytes: &[u8]) -> Result<R, ControlError> {
    let bytes = if bytes.is_empty() { &b"{}"[..] } else { bytes };
    serde_json::from_slice(bytes).map_err(|e| {
        ControlError::Unavailable(format!("Control's reply is not the expected JSON: {e}"))
    })
}

fn build(
    roots: Option<&Vec<reqwest::Certificate>>,
    identity: Option<&[u8]>,
) -> Result<reqwest::Client, String> {
    let mut builder = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .tls_sslkeylogfile(false)
        .timeout(Duration::from_secs(10))
        .connect_timeout(Duration::from_secs(3));
    if let Some(roots) = roots {
        builder = builder.tls_certs_only(roots.iter().cloned());
    }
    if let Some(pem) = identity {
        builder = builder.identity(
            reqwest::Identity::from_pem(pem)
                .map_err(|e| format!("control client identity: {e}"))?,
        );
    }
    builder.build().map_err(|e| format!("control client: {e}"))
}
