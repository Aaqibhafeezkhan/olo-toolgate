// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The host's three calls to Control (plan.md §11.3, §12.8), under its workload certificate:
//! claim the permit's nonce, fetch a secret for a running invocation, and report the outcome.

use crate::config::ControlConfig;
use olo_toolgate_contracts::v2::{InvocationResultReport, PermitConsumed, PermitConsumption};
use olo_toolgate_contracts::{EnterpriseSecretDelivery, EnterpriseSecretDeliveryRequest};
use std::time::Duration;

const BASE: &str = "/api/control/v1/access";
/// Carries the fencing token on secret delivery, whose v1 body has no field for it.
pub const FENCING_HEADER: &str = "Toolgate-Fencing-Token";
const MAX_REPLY: usize = 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ControlError {
    /// Control answered and said no.
    Refused(u16),
    /// Control could not be reached or answered something unusable.
    Unavailable(String),
}

pub struct Control {
    url: String,
    client: reqwest::Client,
}

impl Control {
    pub fn new(config: &ControlConfig) -> Result<Control, String> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let mut builder = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .tls_sslkeylogfile(false)
            .timeout(Duration::from_secs(10))
            .connect_timeout(Duration::from_secs(3));
        if let Some(ca) = &config.ca_certificate_path {
            let pem = std::fs::read(ca).map_err(|e| format!("{}: {e}", ca.display()))?;
            let certs = reqwest::Certificate::from_pem_bundle(&pem)
                .map_err(|e| format!("{}: {e}", ca.display()))?;
            builder = builder.tls_certs_only(certs);
        }
        if let (Some(cert), Some(key)) = (&config.client_certificate_path, &config.client_key_path)
        {
            let mut pem = std::fs::read(cert).map_err(|e| format!("{}: {e}", cert.display()))?;
            pem.extend(std::fs::read(key).map_err(|e| format!("{}: {e}", key.display()))?);
            builder = builder.identity(
                reqwest::Identity::from_pem(&pem)
                    .map_err(|e| format!("control client identity: {e}"))?,
            );
        }
        Ok(Control {
            url: config.url.trim_end_matches('/').to_string(),
            client: builder
                .build()
                .map_err(|e| format!("control client: {e}"))?,
        })
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

    async fn post<B: serde::Serialize, R: serde::de::DeserializeOwned>(
        &self,
        path: &str,
        body: &B,
        fencing_token: Option<&str>,
    ) -> Result<R, ControlError> {
        let body =
            serde_json::to_vec(body).map_err(|e| ControlError::Unavailable(e.to_string()))?;
        let mut request = self
            .client
            .post(format!("{}{BASE}{path}", self.url))
            .header(reqwest::header::CONTENT_TYPE, "application/json")
            .body(body);
        if let Some(token) = fencing_token {
            request = request.header(FENCING_HEADER, token);
        }
        let response = request
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
        let bytes = response
            .bytes()
            .await
            .map_err(|e| ControlError::Unavailable(e.without_url().to_string()))?;
        if bytes.len() > MAX_REPLY {
            return Err(ControlError::Unavailable(
                "Control's reply is too large".into(),
            ));
        }
        let bytes = if bytes.is_empty() {
            &b"{}"[..]
        } else {
            &bytes[..]
        };
        serde_json::from_slice(bytes).map_err(|e| {
            ControlError::Unavailable(format!("Control's reply is not the expected JSON: {e}"))
        })
    }
}
