// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The host's calls to Control under its workload certificate. Per invocation (platform-requirements.md §11.3,
//! §12.8): claim the permit's nonce, fetch a secret for a running invocation, and report the
//! outcome. As an enrolled device (`device`): enroll, check in, register and fetch content. The
//! certificate is either configured or the one enrollment issued, swapped in as it renews.

use crate::config::ControlConfig;
use olo_toolgate_contracts::v2::{
    AdmissionResponse, Dispatch, InvocationResultReport, McpQuarantineReport, McpRefreshRequest,
    McpScanClaims, McpScanDispatch, McpScanFailureReport, McpScanResult, PermitConsumed,
    PermitConsumption, ResultMessage, RunEventAck, RunEventBatch,
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
/// MCP proxy scans this host runs (ADR 030).
const SCANS: &str = "/api/control/v1/tool-hosts/mcp-scans";

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
    tenant: String,
    url: String,
    bootstrap: reqwest::Client,
    workload: Option<crate::workload_identity::Workload>,
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
        let mut workload = None;
        if let (Some(cert), Some(key)) = (&config.client_certificate_path, &config.client_key_path)
        {
            let pem = std::fs::read(cert).map_err(|_| "Workload public certificate unavailable")?;
            let certificate = std::str::from_utf8(&pem)
                .map_err(|_| "Workload public certificate required")?
                .to_owned();
            if olo_toolgate_tenant_identity::certificate_tenant(&certificate)
                .map_err(|_| "Workload tenant certificate required")?
                != config.tenant_id
            {
                return Err("Workload tenant identity mismatch".into());
            }
            let private =
                std::fs::read_to_string(key).map_err(|_| "Workload private key unavailable")?;
            workload = Some(crate::workload_identity::Workload::open(
                config,
                certificate,
                &private,
            )?);
            let mut pem = pem;
            pem.extend(private.as_bytes());
            identity = Some(pem);
        }
        let client = build(roots.as_ref(), identity.as_deref())?;
        let bootstrap = build(roots.as_ref(), None)?;
        Ok(Control {
            tenant: config.tenant_id.clone(),
            bootstrap,
            workload,
            url: config.url.trim_end_matches('/').to_string(),
            roots,
            client: std::sync::RwLock::new(client),
        })
    }

    pub fn validate_host(&self, host: &str) -> Result<(), String> {
        if self
            .workload
            .as_ref()
            .is_some_and(|workload| workload.host_id != host)
        {
            return Err("Workload host identity mismatch".into());
        }
        Ok(())
    }
    pub async fn qualify(&self) -> Result<(), ControlError> {
        if let Some(workload) = &self.workload {
            workload
                .qualify(self)
                .await
                .map_err(ControlError::Unavailable)?;
        }
        Ok(())
    }
    /// Fixed HTTPS discovery never presents a device or preprovisioned workload certificate.
    pub async fn discovery(&self) -> Result<Vec<u8>, ControlError> {
        olo_toolgate_tenant_identity::origin(&self.url)
            .map_err(|_| ControlError::Unavailable("HTTPS discovery origin required".into()))?;
        self.send_via(
            self.bootstrap.clone(),
            "/.well-known/olo-toolgate-client",
            None,
            &[],
            131072,
        )
        .await
    }
    /// Presents this certificate chain and key (PEM) on every later call.
    pub fn use_identity(&self, pem: &[u8]) -> Result<(), String> {
        if self.workload.is_some() {
            return Err(
                "Operator-provisioned workload identity cannot be replaced by enrollment".into(),
            );
        }
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

    /// Response frames of the attempt this host admitted, bound to its fencing token.
    pub async fn events(&self, batch: &RunEventBatch) -> Result<RunEventAck, ControlError> {
        let path = format!("/invocations/{}/events", batch.invocation_id);
        self.post(&path, batch, None).await
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

    /// MCP scans: the oldest scan Control queued for this host, with its scan permit.
    pub async fn next_scan(&self) -> Result<Option<McpScanDispatch>, ControlError> {
        let bytes = self
            .send(
                &format!("{SCANS}/next"),
                Some(b"{}".to_vec()),
                &[],
                MAX_REPLY,
            )
            .await?;
        if bytes.is_empty() {
            return Ok(None);
        }
        json(&bytes).map(Some)
    }

    /// Consumes a verified scan permit; only then may the scan's secret be redeemed.
    pub async fn consume_scan(&self, claims: &McpScanClaims) -> Result<(), ControlError> {
        let path = format!("{SCANS}/{}/consume", claims.scan_id);
        let _: serde_json::Value = self.post_json(&path, claims, &[]).await?;
        Ok(())
    }

    /// The registration's upstream credential, for the admitted holder of the scan.
    pub async fn scan_secret(
        &self,
        scan_id: &str,
        fencing_token: &str,
    ) -> Result<String, ControlError> {
        let path = format!("{SCANS}/{scan_id}/secret");
        let delivery: EnterpriseSecretDelivery = self
            .post_json(
                &path,
                &serde_json::json!({}),
                &[(FENCING_HEADER, fencing_token)],
            )
            .await?;
        Ok(delivery.value)
    }

    pub async fn scan_result(&self, result: &McpScanResult) -> Result<(), ControlError> {
        let path = format!("{SCANS}/{}/result", result.scan_id);
        let _: serde_json::Value = self.post_json(&path, result, &[]).await?;
        Ok(())
    }

    pub async fn scan_failure(&self, report: &McpScanFailureReport) -> Result<(), ControlError> {
        let path = format!("{SCANS}/{}/failure", report.scan_id);
        let _: serde_json::Value = self.post_json(&path, report, &[]).await?;
        Ok(())
    }

    /// Asks for an immediate refresh of the server of a call this host holds, which sent `list_changed`.
    pub async fn refresh(&self, request: &McpRefreshRequest) -> Result<(), ControlError> {
        let _: serde_json::Value = self
            .post_json("/api/control/v1/tool-hosts/mcp-refreshes", request, &[])
            .await?;
        Ok(())
    }

    /// A quarantine report bound to one invocation or refresh scan this host holds.
    pub async fn quarantine(&self, report: &McpQuarantineReport) -> Result<(), ControlError> {
        let _: serde_json::Value = self
            .post_json("/api/control/v1/tool-hosts/mcp-quarantines", report, &[])
            .await?;
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
        self.qualify().await?;
        self.send_via(self.client(), path, body, headers, max).await
    }
    async fn send_via(
        &self,
        client: reqwest::Client,
        path: &str,
        body: Option<Vec<u8>>,
        headers: &[(&str, &str)],
        max: usize,
    ) -> Result<Vec<u8>, ControlError> {
        let url = format!("{}{path}", self.url);
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
        request = request.header("Toolgate-Tenant-ID", &self.tenant);
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
