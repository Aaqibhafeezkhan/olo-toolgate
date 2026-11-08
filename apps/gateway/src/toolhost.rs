// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Dispatch to Tool Hosts (platform-requirements.md §11.3). Control places every call on a device; when that
//! device is a Tool Host named here, the Gateway gets a permit for it from Control and posts a
//! `Dispatch`. The host answers NDJSON: one `AdmissionResponse` line, then after `ADMITTED` one
//! `ResultMessage` line. The permit, not this Gateway, is what lets the host run the tool.
pub use crate::toolhost_tls::Config as ToolHostTlsConfig;
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
    #[serde(default)]
    pub development_loopback_http: bool,
    #[serde(default)]
    pub tls: Option<ToolHostTlsConfig>,
}

impl ToolHostRoute {
    pub fn validate(&self) -> Result<(), &'static str> {
        let url = reqwest::Url::parse(&self.url).map_err(|_| "invalid Tool Host URL")?;
        // Loopback until the host's mTLS listener exists (apps/tool-host/README.md).
        let loopback = matches!(url.host_str(), Some("127.0.0.1" | "[::1]" | "localhost"));
        if self.host_id.is_empty()
            || self.host_id.len() > 128
            || !((url.scheme() == "https" && self.tls.is_some() && !self.development_loopback_http)
                || (url.scheme() == "http"
                    && self.tls.is_none()
                    && loopback
                    && self.development_loopback_http))
            || url.path() != "/"
            || url.query().is_some()
            || !url.username().is_empty()
            || url.password().is_some()
            || url.fragment().is_some()
        {
            return Err("Tool Host routes require dedicated HTTPS mTLS or explicit development loopback HTTP");
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
    routes: BTreeMap<String, (String, Option<reqwest::Client>)>,
    contracts: Option<std::sync::Arc<Contracts>>,
    delivered: std::sync::Mutex<BTreeMap<String, String>>,
}

impl ToolHosts {
    pub fn new(routes: &[ToolHostRoute], tenant: &str) -> Self {
        let _ = rustls::crypto::ring::default_provider().install_default();
        Self {
            routes: routes
                .iter()
                .map(|route| {
                    let client = route.validate().ok().and_then(|()| {
                        if let Some(tls) = &route.tls {
                            crate::toolhost_tls::client(tls, tenant).ok()
                        } else {
                            reqwest::Client::builder()
                                .no_proxy()
                                .redirect(reqwest::redirect::Policy::none())
                                .retry(reqwest::retry::never())
                                .connect_timeout(Duration::from_secs(2))
                                .build()
                                .ok()
                        }
                    });
                    (
                        route.host_id.clone(),
                        (route.url.trim_end_matches('/').to_owned(), client),
                    )
                })
                .collect(),
            contracts: Contracts::new().ok().map(std::sync::Arc::new),
            delivered: std::sync::Mutex::new(BTreeMap::new()),
        }
    }

    /// One bounded independent delivery per dedicated mTLS route. Loopback
    /// development dispatch is deliberately not a signed-state relay tier.
    pub async fn push_revocations(&self, bytes: &[u8], epoch: u64) -> bool {
        let Some(contracts) = &self.contracts else {
            return false;
        };
        let fingerprint = format!("{epoch}:{}", crate::digest(bytes));
        let mut delivered = true;
        let mut deliveries = tokio::task::JoinSet::new();
        for (host, (url, client)) in &self.routes {
            if self
                .delivered
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .get(host)
                == Some(&fingerprint)
            {
                continue;
            }
            if !url.starts_with("https://") {
                continue;
            }
            let Some(client) = client.as_ref() else {
                delivered = false;
                continue;
            };
            let url = format!("{url}/v1/kills");
            let client = client.clone();
            let body = bytes.to_vec();
            let contracts = contracts.clone();
            let host = host.clone();
            deliveries.spawn(async move {
                let delivery = async {
                    let mut response = client
                        .post(url)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .send()
                        .await
                        .ok()?;
                    if response.status() != 200
                        || response.content_length().is_some_and(|n| n > 131072)
                    {
                        return None;
                    }
                    let mut bytes = Vec::new();
                    while let Some(chunk) = response.chunk().await.ok()? {
                        if bytes.len() + chunk.len() > 131072 {
                            return None;
                        }
                        bytes.extend_from_slice(&chunk);
                    }
                    let value = strict_json(&bytes).ok()?;
                    if !contracts.valid("KillAcknowledgement", &value) {
                        return None;
                    }
                    let ack: olo_toolgate_contracts::v2::KillAcknowledgement =
                        serde_json::from_value(value).ok()?;
                    (ack.authorization_epoch >= epoch
                        && ack.delivery_path == olo_toolgate_contracts::v2::KillDeliveryPath::Push)
                        .then_some(())
                };
                let accepted = tokio::time::timeout(Duration::from_secs(2), delivery)
                    .await
                    .is_ok_and(|result| result.is_some());
                (host, accepted)
            });
        }
        while let Some(result) = deliveries.join_next().await {
            match result {
                Ok((host, true)) => {
                    self.delivered
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .insert(host, fingerprint.clone());
                }
                _ => delivered = false,
            }
        }
        delivered
    }

    /// The dispatch origin of the Tool Host with this device id, when there is one.
    pub fn url(&self, device: &str) -> Option<&str> {
        self.routes.get(device).map(|(url, _)| url.as_str())
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
        // Identity is bound to this configured origin. A caller cannot send it to an arbitrary URL.
        let client = self
            .routes
            .values()
            .find(|(origin, _)| origin == url)
            .and_then(|(_, client)| client.as_ref());
        let (Some(client), Some(contracts)) = (client, &self.contracts) else {
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
