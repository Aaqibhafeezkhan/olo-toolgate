// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Optional bounded Control relay. No agent bearer credential crosses this service boundary.
use crate::validation::{strict_json, Contracts};
use olo_toolgate_contracts::v2::{
    PermitReservationRequest, RequestContext, SignedInvocationPermit,
};
use olo_toolgate_contracts::*;
use serde::{Deserialize, Serialize};
use std::{
    future::Future,
    path::PathBuf,
    pin::Pin,
    sync::atomic::{AtomicU64, Ordering},
    time::Duration,
};
pub type PortFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;
use tokio::io::AsyncReadExt;

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RelayConfig {
    pub url: String,
    pub token_path: PathBuf,
    pub development_loopback_http: bool,
}
impl RelayConfig {
    pub fn validate(&self) -> Result<(), &'static str> {
        let url = reqwest::Url::parse(&self.url).map_err(|_| "invalid local MCP origin")?;
        let loopback = matches!(url.host_str(), Some("127.0.0.1" | "[::1]"));
        let unsafe_host = url.host_str().is_none_or(|host| {
            host == "metadata.google.internal"
                || host
                    .trim_matches(['[', ']'])
                    .parse::<std::net::IpAddr>()
                    .is_ok_and(|ip| match ip {
                        std::net::IpAddr::V4(ip) => {
                            ip.is_link_local()
                                || ip.is_multicast()
                                || ip.is_unspecified()
                                || ip.is_broadcast()
                        }
                        std::net::IpAddr::V6(ip) => {
                            ip.is_unicast_link_local() || ip.is_multicast() || ip.is_unspecified()
                        }
                    })
        });
        if !(url.scheme() == "https"
            || url.scheme() == "http" && loopback && self.development_loopback_http)
            || unsafe_host
            || self.url.len() > 512
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || url.path() != "/"
            || !self.token_path.is_absolute()
        {
            return Err("invalid local MCP origin or service credential");
        }
        Ok(())
    }
}
pub trait RelayPort: Send + Sync {
    fn invoke<'a>(
        &'a self,
        request: &'a EnterpriseInvocationRequest,
    ) -> PortFuture<'a, Result<EnterpriseInvocation, ErrorCode>>;
    fn reserve<'a>(
        &'a self,
        request: &'a EnterpriseReservationRequest,
    ) -> PortFuture<'a, Result<EnterpriseReservation, ErrorCode>>;
    /// One permit for a call Control placed on a Tool Host. Conflict means it was placed
    /// elsewhere, and the call goes through the relay.
    fn permit<'a>(
        &'a self,
        _request: &'a PermitReservationRequest,
    ) -> PortFuture<'a, Result<SignedInvocationPermit, ErrorCode>> {
        Box::pin(async { Err(ErrorCode::Conflict) })
    }
    fn ready(&self, now: u64) -> bool;
    fn health(&self) -> PortFuture<'_, Result<(), ErrorCode>>;
    fn catalog<'a>(
        &'a self,
        context: &'a RequestContext,
    ) -> PortFuture<'a, Result<LocalToolCatalog, ErrorCode>>;
    fn submit<'a>(
        &'a self,
        request: &'a RemoteToolSubmission,
    ) -> PortFuture<'a, Result<RemoteToolResponse, ErrorCode>>;
    fn response<'a>(
        &'a self,
        context: &'a RequestContext,
    ) -> PortFuture<'a, Result<RemoteToolResponse, ErrorCode>>;
}
pub struct HttpRelay {
    config: RelayConfig,
    client: reqwest::Client,
    contracts: Contracts,
    last_success: AtomicU64,
}
impl HttpRelay {
    pub fn new(config: RelayConfig) -> Result<Self, &'static str> {
        config.validate()?;
        let _ = rustls::crypto::ring::default_provider().install_default();
        let client = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .tls_sslkeylogfile(false)
            .timeout(Duration::from_secs(3))
            .connect_timeout(Duration::from_secs(2))
            .pool_max_idle_per_host(1)
            .build()
            .map_err(|_| "local MCP transport unavailable")?;
        Ok(Self {
            config,
            client,
            contracts: Contracts::new()?,
            last_success: AtomicU64::new(0),
        })
    }
    async fn token(&self) -> Result<String, ErrorCode> {
        let file = tokio::fs::File::open(&self.config.token_path)
            .await
            .map_err(|_| ErrorCode::DependencyUnavailable)?;
        let mut bytes = vec![];
        file.take(16385)
            .read_to_end(&mut bytes)
            .await
            .map_err(|_| ErrorCode::DependencyUnavailable)?;
        let token = std::str::from_utf8(&bytes)
            .map_err(|_| ErrorCode::DependencyUnavailable)?
            .trim();
        if token.is_empty()
            || token.len() > 16384
            || !token
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
        {
            return Err(ErrorCode::DependencyUnavailable);
        }
        Ok(token.to_owned())
    }
    async fn post<T: Serialize, R: serde::de::DeserializeOwned>(
        &self,
        path: &str,
        request: &T,
        model: &str,
    ) -> Result<R, ErrorCode> {
        let token = self.token().await?;
        let correlation = crate::digest(
            format!(
                "{}-{:?}",
                crate::unix_ms().unwrap_or(0),
                std::time::Instant::now()
            )
            .as_bytes(),
        );
        let correlation = &correlation[..32];
        let body = serde_json::to_vec(request).map_err(|_| ErrorCode::Validation)?;
        crate::diagnostics::packet(
            "SEND",
            path,
            correlation,
            None,
            crate::diagnostics::summary(
                &serde_json::from_slice(&body).map_err(|_| ErrorCode::Validation)?,
            ),
        );
        let mut response = self
            .client
            .post(format!("{}{}", self.config.url.trim_end_matches('/'), path))
            .bearer_auth(token)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("X-Request-ID", correlation)
            .body(body)
            .send()
            .await
            .map_err(|_| ErrorCode::DependencyUnavailable)?;
        if response.status() != 200 {
            crate::diagnostics::packet(
                "RECEIVE",
                path,
                correlation,
                Some(response.status().as_u16()),
                serde_json::json!({"code":"HTTP_REJECTED"}),
            );
            return Err(match response.status().as_u16() {
                401 | 403 => ErrorCode::Forbidden,
                409 => ErrorCode::Conflict,
                _ => ErrorCode::DependencyUnavailable,
            });
        }
        if response.content_length().is_some_and(|n| n > 131072)
            || response
                .headers()
                .get("content-type")
                .and_then(|v| v.to_str().ok())
                .is_none_or(|v| v.split(';').next() != Some("application/json"))
        {
            return Err(ErrorCode::Validation);
        }
        let mut bytes = vec![];
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|_| ErrorCode::DependencyUnavailable)?
        {
            if bytes.len() + chunk.len() > 131072 {
                return Err(ErrorCode::Validation);
            }
            bytes.extend_from_slice(&chunk);
        }
        let value = strict_json(&bytes).map_err(|_| ErrorCode::Validation)?;
        if !self.contracts.valid(model, &value) {
            return Err(ErrorCode::Validation);
        }
        crate::diagnostics::packet(
            "RECEIVE",
            path,
            correlation,
            Some(200),
            crate::diagnostics::summary(&value),
        );
        let decoded = serde_json::from_value(value).map_err(|_| ErrorCode::Validation)?;
        self.last_success.store(
            crate::unix_ms().ok_or(ErrorCode::DependencyUnavailable)?,
            Ordering::Release,
        );
        Ok(decoded)
    }
}
impl RelayPort for HttpRelay {
    fn invoke<'a>(
        &'a self,
        request: &'a EnterpriseInvocationRequest,
    ) -> PortFuture<'a, Result<EnterpriseInvocation, ErrorCode>> {
        Box::pin(self.post(
            "/api/control/v1/access/invocations",
            request,
            "EnterpriseInvocation",
        ))
    }
    fn reserve<'a>(
        &'a self,
        request: &'a EnterpriseReservationRequest,
    ) -> PortFuture<'a, Result<EnterpriseReservation, ErrorCode>> {
        Box::pin(self.post(
            "/api/control/v1/access/invocations/reserve",
            request,
            "EnterpriseReservation",
        ))
    }
    fn permit<'a>(
        &'a self,
        request: &'a PermitReservationRequest,
    ) -> PortFuture<'a, Result<SignedInvocationPermit, ErrorCode>> {
        Box::pin(self.post(
            "/api/control/v1/access/invocations/reserve",
            request,
            "SignedInvocationPermit",
        ))
    }
    fn ready(&self, now: u64) -> bool {
        let last = self.last_success.load(Ordering::Acquire);
        last > 0 && now >= last && now - last < 5000
    }
    fn health(&self) -> PortFuture<'_, Result<(), ErrorCode>> {
        Box::pin(async move {
            let response = self
                .client
                .get(format!(
                    "{}/api/control/v1/access/health",
                    self.config.url.trim_end_matches('/')
                ))
                .bearer_auth(self.token().await?)
                .send()
                .await
                .map_err(|_| ErrorCode::DependencyUnavailable)?;
            if !response.status().is_success() {
                return Err(ErrorCode::DependencyUnavailable);
            }
            self.last_success.store(
                crate::unix_ms().ok_or(ErrorCode::DependencyUnavailable)?,
                Ordering::Release,
            );
            Ok(())
        })
    }
    fn catalog<'a>(
        &'a self,
        context: &'a RequestContext,
    ) -> PortFuture<'a, Result<LocalToolCatalog, ErrorCode>> {
        Box::pin(self.post("/api/control/v1/mcp/catalog", context, "LocalToolCatalog"))
    }
    fn submit<'a>(
        &'a self,
        request: &'a RemoteToolSubmission,
    ) -> PortFuture<'a, Result<RemoteToolResponse, ErrorCode>> {
        Box::pin(self.post(
            "/api/control/v1/mcp/requests",
            request,
            "RemoteToolResponse",
        ))
    }
    fn response<'a>(
        &'a self,
        context: &'a RequestContext,
    ) -> PortFuture<'a, Result<RemoteToolResponse, ErrorCode>> {
        Box::pin(self.post(
            "/api/control/v1/mcp/responses",
            context,
            "RemoteToolResponse",
        ))
    }
}
