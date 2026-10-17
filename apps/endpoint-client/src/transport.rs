// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Fixed-path bounded HTTPS transport; identity/private material never enters error strings.
use crate::{config::Config, contracts::Contracts, identity::DeviceKey, Failure, Result};
use olo_toolgate_contracts::*;
use std::{future::Future, pin::Pin, sync::Arc, time::Duration};
pub type Call<'a, T> = Pin<Box<dyn Future<Output = Result<T>> + Send + 'a>>;
pub trait ControlPort: Send + Sync {
    fn ping(&self, request: EndpointPresencePing) -> Call<'_, EndpointConnectionDirective>;
    fn effect_outcome(
        &self,
        _identity: DeviceIdentity,
        _id: String,
    ) -> Call<'_, EnterpriseInvocation> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    fn deliver_secret(
        &self,
        _identity: DeviceIdentity,
        _request: EnterpriseSecretDeliveryRequest,
    ) -> Call<'_, EnterpriseSecretDelivery> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    fn consume_effect(
        &self,
        _identity: DeviceIdentity,
        _request: EnterprisePermitConsumption,
    ) -> Call<'_, EnterpriseInvocation> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    fn report_effect(
        &self,
        _identity: DeviceIdentity,
        _request: EnterpriseEffectReport,
    ) -> Call<'_, EnterpriseInvocation> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    fn remote_authorize(
        &self,
        _identity: DeviceIdentity,
        _request: RemoteToolAuthorization,
    ) -> Call<'_, RemoteToolAuthorizationAck> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    /// Run-response frames of an attempt this device admitted, bound to its fencing token.
    fn run_events(
        &self,
        _identity: DeviceIdentity,
        _batch: olo_toolgate_contracts::v2::RunEventBatch,
    ) -> Call<'_, olo_toolgate_contracts::v2::RunEventAck> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    fn remote_result(
        &self,
        _identity: DeviceIdentity,
        _result: RemoteToolResult,
    ) -> Call<'_, RemoteToolRecord> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    fn desired(&self, _identity: DeviceIdentity) -> Call<'_, FleetSignedDocument> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    fn artifact_grant(
        &self,
        _identity: DeviceIdentity,
        _request: FleetArtifactGrantRequest,
    ) -> Call<'_, FleetSignedDocument> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    fn artifact(
        &self,
        _identity: DeviceIdentity,
        _grant: FleetSignedDocument,
    ) -> Call<'_, Vec<u8>> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    fn discovery(&self) -> Call<'_, SignedClientDiscovery>;
    fn start(&self, request: EndpointEnrollmentStart) -> Call<'_, EndpointEnrollmentChallenge>;
    fn request_approval(
        &self,
        _request: EndpointEnrollmentStart,
    ) -> Call<'_, EndpointEnrollmentChallenge> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    fn poll(&self, request: EndpointEnrollmentPoll) -> Call<'_, EndpointEnrollmentResult>;
    fn check_in(
        &self,
        identity: DeviceIdentity,
        request: EndpointCheckIn,
    ) -> Call<'_, EndpointCheckInAck>;
}
pub struct HttpsControl {
    origin: String,
    client: reqwest::Client,
    config: Config,
    key: Arc<DeviceKey>,
    contracts: Arc<Contracts>,
    packets: crate::diagnostics::PacketLog,
    socket: crate::socket::Channel,
}
impl HttpsControl {
    pub fn new(config: Config, key: Arc<DeviceKey>, contracts: Arc<Contracts>) -> Result<Self> {
        config.validate()?;
        let client = Self::builder(&config)?
            .build()
            .map_err(|_| Failure::Unavailable)?;
        Ok(Self {
            socket: crate::socket::Channel::default(),
            packets: crate::diagnostics::PacketLog::open(config.state_directory.clone())?,
            origin: crate::config::origin(&config.server_url)?,
            client,
            config,
            key,
            contracts,
        })
    }
    fn builder(config: &Config) -> Result<reqwest::ClientBuilder> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let mut builder = reqwest::Client::builder()
            .https_only(true)
            .http1_only()
            .redirect(reqwest::redirect::Policy::none())
            .no_proxy()
            .tls_sslkeylogfile(false)
            .referer(false)
            .retry(reqwest::retry::never())
            .timeout(Duration::from_secs(config.request_timeout_seconds))
            .connect_timeout(Duration::from_secs(5))
            .pool_max_idle_per_host(1)
            .user_agent(concat!("olo-toolgate-client/", env!("CARGO_PKG_VERSION")));
        if let Some(path) = &config.ca_certificate_path {
            let bytes = crate::storage::read_owned(path, 16384, false)?;
            let root = reqwest::Certificate::from_pem(&bytes).map_err(|_| Failure::Validation)?;
            builder = builder.tls_certs_only([root]);
        }
        Ok(builder)
    }
    async fn request<T: serde::de::DeserializeOwned>(
        &self,
        client: &reqwest::Client,
        path: &str,
        body: Option<Vec<u8>>,
        model: &str,
    ) -> Result<T> {
        let url = format!("{}{path}", self.origin);
        let correlation = crate::identity::nonce()?;
        let message = body
            .as_ref()
            .and_then(|bytes| serde_json::from_slice(bytes).ok())
            .map(|value| crate::diagnostics::summary(&value))
            .unwrap_or_else(|| serde_json::json!({}));
        self.packets.record(
            "SEND",
            path,
            &correlation,
            (None, None),
            body.as_ref().map_or(0, Vec::len),
            message,
        );
        let mut request = if body.is_some() {
            client.post(url)
        } else {
            client.get(url)
        };
        request = request
            .header("Accept", "application/json")
            .header("X-Request-ID", &correlation);
        if path == "/api/control/v1/endpoint/check-in" {
            if let Some(name) = crate::platform::system_name() {
                request = request.header("X-ToolGate-System-Name", name);
            }
        }
        let mut response = if let Some(body) = body {
            request
                .header("Content-Type", "application/json")
                .body(body)
        } else {
            request
        }
        .send()
        .await
        .map_err(|_| {
            self.packets.record(
                "RECEIVE",
                path,
                &correlation,
                (None, None),
                0,
                serde_json::json!({"code":"DEPENDENCY_UNAVAILABLE"}),
            );
            Failure::Unavailable
        })?;
        let status = response.status().as_u16();
        let peer_id = response
            .headers()
            .get("x-request-id")
            .and_then(|v| v.to_str().ok())
            .filter(|v| v.len() <= 64 && v.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-'))
            .map(str::to_owned);
        if status != 200 {
            self.packets.record(
                "RECEIVE",
                path,
                &correlation,
                (Some(status), peer_id.as_deref()),
                0,
                serde_json::json!({"code":"HTTP_REJECTED"}),
            );
        }
        match response.status().as_u16() {
            200 => {}
            401 | 403 => return Err(Failure::Revoked),
            409 => return Err(Failure::Conflict),
            410 => return Err(Failure::EnrollmentRequired),
            _ => return Err(Failure::Unavailable),
        }
        if response.content_length().is_some_and(|n| n > 131072)
            || response
                .headers()
                .get("content-type")
                .and_then(|h| h.to_str().ok())
                .is_none_or(|v| v.split(';').next() != Some("application/json"))
        {
            return Err(Failure::Validation);
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| Failure::Unavailable)? {
            if bytes.len() + chunk.len() > 131072 {
                return Err(Failure::Validation);
            }
            bytes.extend_from_slice(&chunk);
        }
        let decoded = self.contracts.decode(model, &bytes);
        let message = if decoded.is_ok() {
            serde_json::from_slice(&bytes)
                .map(|value| crate::diagnostics::summary(&value))
                .unwrap_or_default()
        } else {
            serde_json::json!({"code":"VALIDATION"})
        };
        self.packets.record(
            "RECEIVE",
            path,
            &correlation,
            (Some(status), peer_id.as_deref()),
            bytes.len(),
            message,
        );
        decoded
    }
}
impl ControlPort for HttpsControl {
    fn request_approval(
        &self,
        request: EndpointEnrollmentStart,
    ) -> Call<'_, EndpointEnrollmentChallenge> {
        Box::pin(async move {
            self.request(
                &self.client,
                "/api/control/v1/endpoint/enrollments/request-approval",
                Some(self.contracts.encode("EndpointEnrollmentStart", &request)?),
                "EndpointEnrollmentChallenge",
            )
            .await
        })
    }
    fn ping(&self, request: EndpointPresencePing) -> Call<'_, EndpointConnectionDirective> {
        Box::pin(async move {
            self.request(
                &self.client,
                "/api/control/v1/endpoint/ping",
                Some(self.contracts.encode("EndpointPresencePing", &request)?),
                "EndpointConnectionDirective",
            )
            .await
        })
    }
    fn effect_outcome(
        &self,
        identity: DeviceIdentity,
        id: String,
    ) -> Call<'_, EnterpriseInvocation> {
        Box::pin(async move {
            let client = Self::builder(&self.config)?
                .identity(self.key.tls_identity(&identity)?)
                .build()
                .map_err(|_| Failure::Unavailable)?;
            self.request(
                &client,
                &format!("/api/control/v1/access/invocations/{id}/device-outcome"),
                None,
                "EnterpriseInvocation",
            )
            .await
        })
    }
    fn deliver_secret(
        &self,
        identity: DeviceIdentity,
        request: EnterpriseSecretDeliveryRequest,
    ) -> Call<'_, EnterpriseSecretDelivery> {
        Box::pin(async move {
            let client = Self::builder(&self.config)?
                .identity(self.key.tls_identity(&identity)?)
                .build()
                .map_err(|_| Failure::Unavailable)?;
            self.request(
                &client,
                "/api/control/v1/access/secrets/deliver",
                Some(
                    self.contracts
                        .encode("EnterpriseSecretDeliveryRequest", &request)?,
                ),
                "EnterpriseSecretDelivery",
            )
            .await
        })
    }
    fn consume_effect(
        &self,
        identity: DeviceIdentity,
        request: EnterprisePermitConsumption,
    ) -> Call<'_, EnterpriseInvocation> {
        Box::pin(async move {
            let client = Self::builder(&self.config)?
                .identity(self.key.tls_identity(&identity)?)
                .build()
                .map_err(|_| Failure::Unavailable)?;
            self.request(
                &client,
                "/api/control/v1/access/permits/consume",
                Some(
                    self.contracts
                        .encode("EnterprisePermitConsumption", &request)?,
                ),
                "EnterpriseInvocation",
            )
            .await
        })
    }
    fn run_events(
        &self,
        identity: DeviceIdentity,
        batch: olo_toolgate_contracts::v2::RunEventBatch,
    ) -> Call<'_, olo_toolgate_contracts::v2::RunEventAck> {
        Box::pin(async move {
            let client = Self::builder(&self.config)?
                .identity(self.key.tls_identity(&identity)?)
                .build()
                .map_err(|_| Failure::Unavailable)?;
            let path = format!(
                "/api/control/v1/access/invocations/{}/events",
                batch.invocation_id
            );
            self.request(
                &client,
                &path,
                Some(self.contracts.encode("RunEventBatch", &batch)?),
                "RunEventAck",
            )
            .await
        })
    }
    fn report_effect(
        &self,
        identity: DeviceIdentity,
        request: EnterpriseEffectReport,
    ) -> Call<'_, EnterpriseInvocation> {
        Box::pin(async move {
            let client = Self::builder(&self.config)?
                .identity(self.key.tls_identity(&identity)?)
                .build()
                .map_err(|_| Failure::Unavailable)?;
            self.request(
                &client,
                "/api/control/v1/access/effects/report",
                Some(self.contracts.encode("EnterpriseEffectReport", &request)?),
                "EnterpriseInvocation",
            )
            .await
        })
    }
    fn remote_authorize(
        &self,
        identity: DeviceIdentity,
        request: RemoteToolAuthorization,
    ) -> Call<'_, RemoteToolAuthorizationAck> {
        Box::pin(async move {
            let client = Self::builder(&self.config)?
                .identity(self.key.tls_identity(&identity)?)
                .build()
                .map_err(|_| Failure::Unavailable)?;
            self.socket
                .ensure(
                    &client,
                    &self.origin,
                    identity.expires_at_unix_ms,
                    Some((request.request_id.clone(), crate::now() + 30000)),
                    &self.packets,
                )
                .await;
            let body = self.contracts.encode("RemoteToolAuthorization", &request)?;
            let response: RemoteToolAuthorizationAck = if let Some(result) = self
                .socket
                .call(
                    ClientSocketOperation::Authorize,
                    Some(&body),
                    "RemoteToolAuthorizationAck",
                    &self.contracts,
                    &self.packets,
                )
                .await
            {
                result?
            } else {
                self.request(
                    &client,
                    "/api/control/v1/mcp/authorize",
                    Some(self.contracts.encode("RemoteToolAuthorization", &request)?),
                    "RemoteToolAuthorizationAck",
                )
                .await?
            };
            self.socket
                .ensure(
                    &client,
                    &self.origin,
                    identity.expires_at_unix_ms,
                    Some((request.request_id.clone(), response.expires_at_unix_ms)),
                    &self.packets,
                )
                .await;
            Ok(response)
        })
    }
    fn remote_result(
        &self,
        identity: DeviceIdentity,
        result: RemoteToolResult,
    ) -> Call<'_, RemoteToolRecord> {
        Box::pin(async move {
            let client = Self::builder(&self.config)?
                .identity(self.key.tls_identity(&identity)?)
                .build()
                .map_err(|_| Failure::Unavailable)?;
            let body = self.contracts.encode("RemoteToolResult", &result)?;
            let response = if let Some(response) = self
                .socket
                .call(
                    ClientSocketOperation::Result,
                    Some(&body),
                    "RemoteToolRecord",
                    &self.contracts,
                    &self.packets,
                )
                .await
            {
                response
            } else {
                self.request(
                    &client,
                    "/api/control/v1/mcp/results",
                    Some(self.contracts.encode("RemoteToolResult", &result)?),
                    "RemoteToolRecord",
                )
                .await
            };
            if response.is_ok() {
                self.socket.finished(&result.request_id).await;
            }
            response
        })
    }
    fn desired(&self, identity: DeviceIdentity) -> Call<'_, FleetSignedDocument> {
        Box::pin(async move {
            let client = Self::builder(&self.config)?
                .identity(self.key.tls_identity(&identity)?)
                .build()
                .map_err(|_| Failure::Unavailable)?;
            self.request(
                &client,
                "/api/control/v1/fleet/desired",
                None,
                "FleetSignedDocument",
            )
            .await
        })
    }
    fn artifact_grant(
        &self,
        identity: DeviceIdentity,
        request: FleetArtifactGrantRequest,
    ) -> Call<'_, FleetSignedDocument> {
        Box::pin(async move {
            let client = Self::builder(&self.config)?
                .identity(self.key.tls_identity(&identity)?)
                .build()
                .map_err(|_| Failure::Unavailable)?;
            self.request(
                &client,
                "/api/control/v1/fleet/artifact-grants",
                Some(
                    self.contracts
                        .encode("FleetArtifactGrantRequest", &request)?,
                ),
                "FleetSignedDocument",
            )
            .await
        })
    }
    fn artifact(&self, identity: DeviceIdentity, grant: FleetSignedDocument) -> Call<'_, Vec<u8>> {
        Box::pin(async move {
            let client = Self::builder(&self.config)?
                .identity(self.key.tls_identity(&identity)?)
                .build()
                .map_err(|_| Failure::Unavailable)?;
            let mut response = client
                .post(format!(
                    "{}/api/control/v1/fleet/artifacts/download",
                    self.origin
                ))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Request-ID", crate::identity::nonce()?)
                .body(self.contracts.encode("FleetSignedDocument", &grant)?)
                .send()
                .await
                .map_err(|_| Failure::Unavailable)?;
            match response.status().as_u16() {
                200 => {}
                401 | 403 => return Err(Failure::Revoked),
                409 => return Err(Failure::Conflict),
                _ => return Err(Failure::Unavailable),
            }
            if response.content_length().is_some_and(|n| n > 32768)
                || response
                    .headers()
                    .get("content-type")
                    .and_then(|h| h.to_str().ok())
                    .is_none_or(|v| v.split(';').next() != Some("application/json"))
            {
                return Err(Failure::Validation);
            }
            let mut bytes = Vec::new();
            while let Some(chunk) = response.chunk().await.map_err(|_| Failure::Unavailable)? {
                if bytes.len() + chunk.len() > 32768 {
                    return Err(Failure::Validation);
                }
                bytes.extend_from_slice(&chunk);
            }
            Ok(bytes)
        })
    }
    fn discovery(&self) -> Call<'_, SignedClientDiscovery> {
        Box::pin(self.request(
            &self.client,
            "/.well-known/olo-toolgate-client",
            None,
            "SignedClientDiscovery",
        ))
    }
    fn start(&self, request: EndpointEnrollmentStart) -> Call<'_, EndpointEnrollmentChallenge> {
        Box::pin(async move {
            self.request(
                &self.client,
                "/api/control/v1/endpoint/enrollments",
                Some(self.contracts.encode("EndpointEnrollmentStart", &request)?),
                "EndpointEnrollmentChallenge",
            )
            .await
        })
    }
    fn poll(&self, request: EndpointEnrollmentPoll) -> Call<'_, EndpointEnrollmentResult> {
        Box::pin(async move {
            self.request(
                &self.client,
                "/api/control/v1/endpoint/enrollments/poll",
                Some(self.contracts.encode("EndpointEnrollmentPoll", &request)?),
                "EndpointEnrollmentResult",
            )
            .await
        })
    }
    fn check_in(
        &self,
        identity: DeviceIdentity,
        request: EndpointCheckIn,
    ) -> Call<'_, EndpointCheckInAck> {
        Box::pin(async move {
            let client = Self::builder(&self.config)?
                .identity(self.key.tls_identity(&identity)?)
                .build()
                .map_err(|_| Failure::Unavailable)?;
            self.socket
                .ensure(
                    &client,
                    &self.origin,
                    identity.expires_at_unix_ms,
                    None,
                    &self.packets,
                )
                .await;
            let body = self.contracts.encode("EndpointCheckIn", &request)?;
            let response: EndpointCheckInAck = if let Some(response) = self
                .socket
                .call(
                    ClientSocketOperation::CheckIn,
                    Some(&body),
                    "EndpointCheckInAck",
                    &self.contracts,
                    &self.packets,
                )
                .await
            {
                response?
            } else {
                self.request(
                    &client,
                    "/api/control/v1/endpoint/check-in",
                    Some(self.contracts.encode("EndpointCheckIn", &request)?),
                    "EndpointCheckInAck",
                )
                .await?
            };
            if let Some(task) = &response.task {
                self.socket
                    .ensure(
                        &client,
                        &self.origin,
                        identity.expires_at_unix_ms,
                        Some((task.request_id.clone(), task.expires_at_unix_ms)),
                        &self.packets,
                    )
                    .await;
            }
            Ok(response)
        })
    }
}
