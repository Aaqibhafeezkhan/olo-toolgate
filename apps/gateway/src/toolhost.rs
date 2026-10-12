// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Dispatch to Tool Hosts (plan.md §11.3). Control places every call on a device; when that
//! device is a Tool Host named here, the Gateway gets a permit for it from Control and posts a
//! `Dispatch`. The host answers NDJSON: one `AdmissionResponse` line, then after `ADMITTED` one
//! `ResultMessage` line. The permit, not this Gateway, is what lets the host run the tool.
use crate::validation::{strict_json, Contracts};
use olo_toolgate_contracts::v2::{
    AdmissionKind, AdmissionResponse, Dispatch, RefusalReason, ResultMessage,
};
use olo_toolgate_contracts::ErrorCode;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::time::Duration;

/// The NDJSON reply carries one result frame; the host caps tool output at 4 MiB.
const MAX_REPLY: usize = 8 * 1024 * 1024;

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolHostRoute {
    /// The device id the Tool Host enrolled with; Control places calls on it by this id.
    pub host_id: String,
    /// The host's dispatch origin, such as `http://127.0.0.1:8095`.
    pub url: String,
}

impl ToolHostRoute {
    pub fn validate(&self) -> Result<(), &'static str> {
        let url = reqwest::Url::parse(&self.url).map_err(|_| "invalid Tool Host URL")?;
        // Loopback until the host's mTLS listener exists (apps/tool-host/README.md).
        let loopback = matches!(url.host_str(), Some("127.0.0.1" | "[::1]" | "localhost"));
        if self.host_id.is_empty()
            || self.host_id.len() > 128
            || url.scheme() != "http"
            || !loopback
            || url.path() != "/"
            || url.query().is_some()
            || !url.username().is_empty()
        {
            return Err("Tool Host routes need an id and a loopback http origin");
        }
        Ok(())
    }
}

pub enum Dispatched {
    /// The host refused before running anything.
    Refused(RefusalReason),
    /// The host ran the tool, or tried to.
    Finished(Box<ResultMessage>),
}

pub struct ToolHosts {
    routes: BTreeMap<String, String>,
    client: Option<reqwest::Client>,
    contracts: Option<Contracts>,
}

impl ToolHosts {
    pub fn new(routes: &[ToolHostRoute]) -> Self {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let client = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(2))
            .build()
            .ok();
        Self {
            routes: routes
                .iter()
                .map(|r| (r.host_id.clone(), r.url.trim_end_matches('/').to_string()))
                .collect(),
            client,
            contracts: Contracts::new().ok(),
        }
    }

    /// The dispatch origin of the Tool Host with this device id, when there is one.
    pub fn url(&self, device: &str) -> Option<&str> {
        self.routes.get(device).map(String::as_str)
    }

    /// Posts the dispatch for `invocation_id` and reads the host's two lines, giving up at
    /// `deadline` (Unix ms).
    pub async fn dispatch(
        &self,
        url: &str,
        dispatch: &Dispatch,
        invocation_id: &str,
        deadline: u64,
    ) -> Result<Dispatched, ErrorCode> {
        let (Some(client), Some(contracts)) = (&self.client, &self.contracts) else {
            return Err(ErrorCode::DependencyUnavailable);
        };
        let remaining = deadline.saturating_sub(crate::unix_ms().unwrap_or(u64::MAX));
        if remaining == 0 {
            return Err(ErrorCode::Timeout);
        }
        let body = serde_json::to_vec(dispatch).map_err(|_| ErrorCode::Validation)?;
        let mut response = client
            .post(format!("{url}/v1/dispatch"))
            .timeout(Duration::from_millis(remaining))
            .header("Content-Type", "application/json")
            .body(body)
            .send()
            .await
            .map_err(|e| {
                if e.is_timeout() {
                    ErrorCode::Timeout
                } else {
                    ErrorCode::DependencyUnavailable
                }
            })?;
        if response.status() != 200 {
            return Err(ErrorCode::DependencyUnavailable);
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|e| {
            if e.is_timeout() {
                ErrorCode::Timeout
            } else {
                ErrorCode::DependencyUnavailable
            }
        })? {
            if bytes.len() + chunk.len() > MAX_REPLY {
                return Err(ErrorCode::Validation);
            }
            bytes.extend_from_slice(&chunk);
        }
        let mut lines = bytes.split(|b| *b == b'\n').filter(|line| !line.is_empty());
        let admission: AdmissionResponse = line(contracts, lines.next(), "AdmissionResponse")?;
        if admission.invocation_id != invocation_id {
            return Err(ErrorCode::Validation);
        }
        if admission.kind == AdmissionKind::Refused {
            return Ok(Dispatched::Refused(
                admission.reason.ok_or(ErrorCode::Validation)?,
            ));
        }
        let result: ResultMessage = line(contracts, lines.next(), "ResultMessage")?;
        if result.invocation_id != admission.invocation_id || lines.next().is_some() {
            return Err(ErrorCode::Validation);
        }
        Ok(Dispatched::Finished(Box::new(result)))
    }
}

fn line<T: serde::de::DeserializeOwned>(
    contracts: &Contracts,
    line: Option<&[u8]>,
    model: &str,
) -> Result<T, ErrorCode> {
    let value =
        strict_json(line.ok_or(ErrorCode::Validation)?).map_err(|_| ErrorCode::Validation)?;
    if !contracts.valid(model, &value) {
        return Err(ErrorCode::Validation);
    }
    serde_json::from_value(value).map_err(|_| ErrorCode::Validation)
}
