// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Optional bounded Control relay. No agent bearer credential crosses this service boundary.
use crate::validation::{strict_json, Contracts};
use olo_toolgate_contracts::v2::{
    Dispatch, PermitReservationRequest, RequestContext, SignedInvocationPermit,
    ToolHostDispatchStatus,
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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ca_certificate_path: Option<PathBuf>,
    pub tenant_id: String,
    pub url: String,
    pub token_path: PathBuf,
    pub development_loopback_http: bool,
}
impl RelayConfig {
    pub fn validate(&self) -> Result<(), &'static str> {
        if !crate::tenancy::canonical_uuid(&self.tenant_id) {
            return Err("canonical Control tenant UUID required");
        }
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
    fn cancel<'a>(
        &'a self,
        _context: &'a RequestContext,
    ) -> PortFuture<'a, Result<CancellationAccepted, ErrorCode>> {
        Box::pin(async { Err(ErrorCode::DependencyUnavailable) })
    }
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
    /// Hands Control a permitted dispatch for a Tool Host that fetches its calls (pull mode).
    fn relay_dispatch<'a>(
        &'a self,
        _dispatch: &'a Dispatch,
    ) -> PortFuture<'a, Result<ToolHostDispatchStatus, ErrorCode>> {
        Box::pin(async { Err(ErrorCode::DependencyUnavailable) })
    }
    /// Where a dispatch handed to Control stands.
    fn dispatch_status<'a>(
        &'a self,
        _invocation_id: &'a str,
    ) -> PortFuture<'a, Result<ToolHostDispatchStatus, ErrorCode>> {
        Box::pin(async { Err(ErrorCode::DependencyUnavailable) })
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
        let mut client = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .tls_sslkeylogfile(false)
            .timeout(Duration::from_secs(3))
            .connect_timeout(Duration::from_secs(2))
            .pool_max_idle_per_host(1);
        if let Some(path) = &config.ca_certificate_path {
            let bytes = crate::config::read_bytes(path)?;
            let roots = reqwest::Certificate::from_pem_bundle(&bytes)
                .map_err(|_| "Control TLS roots required")?;
            if roots.is_empty() {
                return Err("Control TLS roots required");
            }
            client = client.tls_certs_only(roots);
        }
        let client = client
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
        self.exchange(path, Some(request), model, 131072).await
    }
    /// A relayed dispatch's status carries the tool's result, up to the 4 MiB output ceiling.
    const MAX_DISPATCH_STATUS: usize = 8 * 1024 * 1024;
    /// POSTs `request`, or GETs when there is none, and reads a `model` reply of at most `max` bytes.
    async fn exchange<T: Serialize, R: serde::de::DeserializeOwned>(
        &self,
        path: &str,
        request: Option<&T>,
        model: &str,
        max: usize,
    ) -> Result<R, ErrorCode> {
        if let Some(request) = request {
            let value = serde_json::to_value(request).map_err(|_| ErrorCode::Validation)?;
            if value.get("context").is_some_and(|context| {
                context.get("tenantId").and_then(serde_json::Value::as_str)
                    != Some(self.config.tenant_id.as_str())
            }) {
                return Err(ErrorCode::TenantMismatch);
            }
        }
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
        let url = format!("{}{}", self.config.url.trim_end_matches('/'), path);
        let builder = match request {
            Some(request) => {
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
                self.client
                    .post(url)
                    .header("Content-Type", "application/json")
                    .body(body)
            }
            None => self.client.get(url),
        };
        let mut response = builder
            .bearer_auth(token)
            .header("Toolgate-Tenant-ID", &self.config.tenant_id)
            .header("Accept", "application/json")
            .header("X-Request-ID", correlation)
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
                404 => ErrorCode::NotFound,
                409 => ErrorCode::Conflict,
                _ => ErrorCode::DependencyUnavailable,
            });
        }
        if response.content_length().is_some_and(|n| n > max as u64)
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
            if bytes.len() + chunk.len() > max {
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
    fn cancel<'a>(
        &'a self,
        context: &'a RequestContext,
    ) -> PortFuture<'a, Result<CancellationAccepted, ErrorCode>> {
        Box::pin(self.post(
            "/api/control/v1/access/invocations/cancel",
            context,
            "CancellationAccepted",
        ))
    }
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
    fn relay_dispatch<'a>(
        &'a self,
        dispatch: &'a Dispatch,
    ) -> PortFuture<'a, Result<ToolHostDispatchStatus, ErrorCode>> {
        Box::pin(self.exchange(
            "/api/control/v1/access/dispatches",
            Some(dispatch),
            "ToolHostDispatchStatus",
            Self::MAX_DISPATCH_STATUS,
        ))
    }
    fn dispatch_status<'a>(
        &'a self,
        invocation_id: &'a str,
    ) -> PortFuture<'a, Result<ToolHostDispatchStatus, ErrorCode>> {
        Box::pin(async move {
            // Control's invocation ids are path-safe; anything else is not one of them.
            if !invocation_id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
            {
                return Err(ErrorCode::Validation);
            }
            self.exchange::<(), _>(
                &format!("/api/control/v1/access/dispatches/{invocation_id}"),
                None,
                "ToolHostDispatchStatus",
                Self::MAX_DISPATCH_STATUS,
            )
            .await
        })
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
                .header("Toolgate-Tenant-ID", &self.config.tenant_id)
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
