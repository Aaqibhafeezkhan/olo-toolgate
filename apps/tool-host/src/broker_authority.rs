// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Supervisor-only authority for one broker. No arbitrary Control URL or operation selector.
use crate::control::{Control, ControlError};
use futures_util::future::BoxFuture;
use olo_toolgate_contracts::v2::{
    HttpAuthDeclaration, RouteDeclaration, SecretDeclaration, ServiceDestination,
};
use olo_toolgate_contracts::{
    ServicePermit, ServicePermitDispatch, ServicePermitDispatchAllowed, ServicePermitIssue,
};
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

pub(crate) enum Transport {
    Direct(Box<crate::request_binding::PreparedRequest>),
    Buffered(Box<crate::broker_process::BufferedRequest>),
}
pub(crate) trait BrokerControl: Send + Sync {
    fn bind_effect_admission(
        &self,
        _proof: olo_toolgate_contracts::v2::SignedInvocationPermit,
        _arguments: std::collections::BTreeMap<String, serde_json::Value>,
    ) -> Result<(), &'static str> {
        Err("effect admission cannot be replaced")
    }
    fn prepare_transport(
        &self,
        prepared: crate::request_binding::PreparedRequest,
        _handler: &str,
    ) -> Result<Transport, serde_json::Value> {
        Ok(Transport::Direct(Box::new(prepared)))
    }
    fn live(&self) -> bool;
    fn close(&self);
    fn transport_buffered<'a>(
        &'a self,
        request: &'a crate::broker_process::BufferedRequest,
    ) -> BoxFuture<'a, Result<crate::broker_process::BufferedResponse, serde_json::Value>>;
    fn transport<'a>(
        &'a self,
        prepared: Transport,
        handler: &'a str,
    ) -> BoxFuture<'a, Result<crate::broker::Sent, serde_json::Value>>;
    fn secret<'a>(
        &'a self,
        invocation: &'a str,
        fence: &'a str,
        name: &'a str,
    ) -> BoxFuture<'a, Result<String, ControlError>>;
    fn service_permit<'a>(
        &'a self,
        request: &'a ServicePermitIssue,
    ) -> BoxFuture<'a, Result<ServicePermit, ControlError>>;
    fn service_sending<'a>(
        &'a self,
        request: &'a ServicePermitDispatch,
    ) -> BoxFuture<'a, Result<ServicePermitDispatchAllowed, ControlError>>;
}

/// Captured from trusted admission, not selected by a private-IPC or worker message.
pub(crate) struct Scope {
    pub invocation: String,
    pub fence: String,
    pub deadline: u64,
    pub profile: String,
    pub profile_digest: String,
    pub broker: String,
    pub routes: Vec<RouteDeclaration>,
    pub destinations: Vec<ServiceDestination>,
    pub secret_names: HashSet<String>,
    pub closed: Arc<AtomicBool>,
    pub effect_started: Arc<AtomicBool>,
    pub private_ranges: Vec<olo_toolgate_contracts::v2::PrivateRangeApproval>,
    pub auths: Vec<HttpAuthDeclaration>,
    pub development_loopback_http: bool,
    pub admission: std::sync::OnceLock<(
        olo_toolgate_contracts::v2::SignedInvocationPermit,
        std::collections::BTreeMap<String, serde_json::Value>,
    )>,
}
pub(crate) fn secret_names(
    routes: &[RouteDeclaration],
    auths: &[HttpAuthDeclaration],
    secrets: &[SecretDeclaration],
) -> HashSet<String> {
    let used: HashSet<_> = routes.iter().filter_map(|r| r.auth.as_deref()).collect();
    secrets
        .iter()
        .map(|s| s.name.clone())
        .chain(
            auths
                .iter()
                .filter(|a| used.contains(a.id.as_str()))
                .filter_map(|a| a.secret.clone()),
        )
        .collect()
}
fn denied<T>() -> Result<T, ControlError> {
    Err(ControlError::Refused(403))
}
fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._:-".contains(&b))
}
impl Scope {
    fn live(&self) -> bool {
        !self.closed.load(Ordering::SeqCst) && crate::now_ms() < self.deadline
    }
    fn secret(&self, invocation: &str, fence: &str, name: &str) -> Result<(), ControlError> {
        if !self.live()
            || invocation != self.invocation
            || fence != self.fence
            || !self.secret_names.contains(name)
        {
            return denied();
        }
        Ok(())
    }
    fn target<'a>(
        &'a self,
        manifest: &olo_toolgate_contracts::PreparedHttpRequestManifest,
    ) -> Result<(&'a RouteDeclaration, &'a ServiceDestination), ControlError> {
        if !self.live() {
            return denied();
        }
        let Some(route) = self.routes.iter().find(|r| r.id == manifest.route_id) else {
            return denied();
        };
        let Some(destination) = self.destinations.iter().find(|d| d.id == route.destination) else {
            return denied();
        };
        let service = matches!(destination.id.as_str(), "olo-memory" | "olo-rag");
        let plain = self.development_loopback_http
            && crate::broker::is_loopback_host(&destination.host)
            && !service
            && destination.tls.ca_bundle.is_none()
            && destination.tls.spki_pins.is_empty();
        if manifest.version != "toolgate-http-request-v1"
            || manifest.profile_id != self.profile
            || manifest.profile_digest != self.profile_digest
            || manifest.host
                != destination
                    .host
                    .trim_matches(['[', ']'])
                    .to_ascii_lowercase()
            || manifest.port != destination.port
            || manifest.method != crate::broker::method_name(&route.method)
            || manifest.scheme != if plain { "http" } else { "https" }
            || destination.scheme != olo_toolgate_contracts::v2::DestinationScheme::Https
            || destination.mode != olo_toolgate_contracts::v2::EgressMode::Brokered
        {
            return denied();
        }
        let (auth, headers) = if service {
            if route.auth.is_some() {
                return denied();
            }
            (
                serde_json::Value::String(format!("toolgate-service-permit:{}", destination.id)),
                vec!["toolgate-service-permit".to_owned()],
            )
        } else if let Some(id) = &route.auth {
            let Some(auth) = self.auths.iter().find(|a| a.id == *id) else {
                return denied();
            };
            (
                serde_json::Value::String(id.clone()),
                vec![auth
                    .header
                    .as_deref()
                    .unwrap_or("authorization")
                    .to_ascii_lowercase()],
            )
        } else {
            (serde_json::Value::Null, Vec::new())
        };
        if manifest.auth_declaration_id != auth || manifest.credential_headers != headers {
            return denied();
        }
        Ok((route, destination))
    }
    fn issue(&self, request: &ServicePermitIssue) -> Result<(), ControlError> {
        if !self.live()
            || request.invocation_id != self.invocation
            || request.fencing_token != self.fence
            || request.broker_instance_id != self.broker
            || !identifier(&request.handler_id)
            || request.invocation_permit.jws.is_empty()
            || request.invocation_permit.jws.len() > 131072
            || request
                .effect_key
                .as_deref()
                .is_some_and(|key| !identifier(key))
        {
            return denied();
        }
        let manifest = &request.http_request_manifest;
        let Some(route) = self.routes.iter().find(|r| r.id == manifest.route_id) else {
            return denied();
        };
        let Some(destination) = self.destinations.iter().find(|d| d.id == route.destination) else {
            return denied();
        };
        let audience = match request.audience {
            olo_toolgate_contracts::ServiceAudience::OloMemory => "olo-memory",
            olo_toolgate_contracts::ServiceAudience::OloRag => "olo-rag",
        };
        if manifest.version != "toolgate-http-request-v1"
            || manifest.profile_id != self.profile
            || manifest.profile_digest != self.profile_digest
            || destination.id != audience
            || manifest.host
                != destination
                    .host
                    .trim_matches(['[', ']'])
                    .to_ascii_lowercase()
            || manifest.port != destination.port
            || manifest.scheme != "https"
            || destination.scheme != olo_toolgate_contracts::v2::DestinationScheme::Https
            || destination.mode != olo_toolgate_contracts::v2::EgressMode::Brokered
            || manifest.method != crate::broker::method_name(&route.method)
        {
            return denied();
        }
        let value = serde_json::to_value(manifest).map_err(|_| ControlError::Refused(403))?;
        let digest = crate::digest::jcs(&value).map_err(|_| ControlError::Refused(403))?;
        if request.http_request_digest != digest {
            return denied();
        }
        Ok(())
    }
}

#[derive(Clone)]
struct Issued {
    permit: ServicePermit,
    digest: String,
    effect_key: Option<String>,
}
enum Grant {
    Pending(String, Option<String>),
    Issued(Issued),
    Checking(Issued),
    Allowed(Issued),
    Sent,
}
struct Grants(Mutex<HashMap<String, Grant>>);
impl Grants {
    fn new() -> Self {
        Self(Mutex::new(HashMap::new()))
    }
    fn reserve(&self, handler: &str, digest: &str) -> Result<(), ControlError> {
        self.reserve_key(handler, digest, None)
    }
    fn reserve_key(
        &self,
        handler: &str,
        digest: &str,
        key: Option<&str>,
    ) -> Result<(), ControlError> {
        if key.is_some_and(|value| !identifier(value)) {
            return denied();
        }
        let mut grants = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if grants.len() >= 256 || grants.contains_key(handler) {
            return denied();
        }
        grants.insert(
            handler.into(),
            Grant::Pending(digest.into(), key.map(str::to_owned)),
        );
        Ok(())
    }
    fn issued(&self, handler: &str, permit: ServicePermit) -> Result<(), ControlError> {
        let mut grants = self.0.lock().unwrap_or_else(|e| e.into_inner());
        let Some(value @ Grant::Pending(_, _)) = grants.get_mut(handler) else {
            return denied();
        };
        let Grant::Pending(digest, key) = value else {
            unreachable!()
        };
        *value = Grant::Issued(Issued {
            permit,
            digest: digest.clone(),
            effect_key: key.clone(),
        });
        Ok(())
    }
    fn sending(&self, handler: &str, permit: &ServicePermit) -> Result<(), ControlError> {
        let mut grants = self.0.lock().unwrap_or_else(|e| e.into_inner());
        let Some(value) = grants.get_mut(handler) else {
            return denied();
        };
        let Grant::Issued(issued) = value else {
            return denied();
        };
        if issued.permit != *permit {
            return denied();
        }
        *value = Grant::Checking(issued.clone());
        Ok(())
    }
    fn allowed(&self, handler: &str) -> Result<(), ControlError> {
        let mut grants = self.0.lock().unwrap_or_else(|e| e.into_inner());
        let Some(value) = grants.get_mut(handler) else {
            return denied();
        };
        let Grant::Checking(issued) = value else {
            return denied();
        };
        *value = Grant::Allowed(issued.clone());
        Ok(())
    }
    fn expected(
        &self,
        handler: &str,
        digest: &str,
        write: bool,
        effect_key: Option<&str>,
    ) -> Result<ServicePermit, ControlError> {
        let grants = self.0.lock().unwrap_or_else(|e| e.into_inner());
        let issued = match grants.get(handler) {
            Some(Grant::Issued(issued)) if !write => issued,
            Some(Grant::Allowed(issued)) if write => issued,
            _ => return denied(),
        };
        if issued.digest != digest || issued.effect_key.as_deref() != effect_key {
            return denied();
        }
        Ok(issued.permit.clone())
    }
    fn take(
        &self,
        handler: &str,
        digest: &str,
        write: bool,
        effect_key: Option<&str>,
    ) -> Result<ServicePermit, ControlError> {
        let mut grants = self.0.lock().unwrap_or_else(|e| e.into_inner());
        let Some(value) = grants.get_mut(handler) else {
            return denied();
        };
        let issued = match &value {
            Grant::Issued(issued) if !write => issued,
            Grant::Allowed(issued) if write => issued,
            _ => return denied(),
        };
        if issued.digest != digest || issued.effect_key.as_deref() != effect_key {
            return denied();
        }
        let permit = issued.permit.clone();
        *value = Grant::Sent;
        Ok(permit)
    }
    fn generic(&self, handler: &str, digest: &str) -> Result<(), ControlError> {
        self.reserve(handler, digest)?;
        let mut grants = self.0.lock().unwrap_or_else(|e| e.into_inner());
        grants.insert(handler.into(), Grant::Sent);
        Ok(())
    }
}

use crate::effect_dispatch::BoundedTransport;

pub(crate) struct Local {
    control: Arc<Control>,
    scope: Scope,
    grants: Grants,
}
impl Local {
    fn client(&self, destination: &ServiceDestination) -> Result<reqwest::Client, String> {
        let policy = crate::egress::AddressPolicy {
            host: destination.host.clone(),
            port: u16::try_from(destination.port).map_err(|_| "invalid target port")?,
            private_ranges: self.scope.private_ranges.clone(),
            allow_loopback: self.scope.development_loopback_http,
        };
        let identity = if matches!(destination.id.as_str(), "olo-memory" | "olo-rag") {
            Some(self.control.service_identity()?)
        } else {
            None
        };
        crate::egress::client_with_identity(destination, policy, identity)
    }
    async fn generic_transport(
        &self,
        manifest: &olo_toolgate_contracts::PreparedHttpRequestManifest,
        digest: &str,
        handler: &str,
        destination: &ServiceDestination,
        effect_key: Option<&str>,
    ) -> Result<BoundedTransport, serde_json::Value> {
        let refused = || serde_json::json!({"refused":"fresh effect authority or bounded transport unavailable"});
        let (proof, arguments) = self.scope.admission.get().ok_or_else(refused)?;
        if !self.scope.live() || !identifier(handler) {
            return Err(refused());
        }
        self.grants
            .generic(handler, digest)
            .map_err(|_| refused())?;
        let request = olo_toolgate_contracts::v2::EffectGrantIssue {
            invocation_id: self.scope.invocation.clone(),
            fencing_token: self.scope.fence.clone(),
            invocation_permit: proof.clone(),
            broker_instance_id: self.scope.broker.clone(),
            handler_id: handler.into(),
            arguments: arguments.clone(),
            http_request_digest: digest.into(),
            http_request_manifest: manifest.clone(),
            effect_key: effect_key.map(str::to_owned),
        };
        let policy = crate::egress::AddressPolicy {
            host: destination.host.clone(),
            port: u16::try_from(destination.port).map_err(|_| refused())?,
            private_ranges: self.scope.private_ranges.clone(),
            allow_loopback: self.scope.development_loopback_http,
        };
        crate::effect_dispatch::authorize(
            &self.control,
            &request,
            destination,
            policy,
            self.scope.deadline,
            &self.scope.effect_started,
            &self.scope.closed,
        )
        .await
        .map_err(|_| refused())
    }

    pub fn new(control: Arc<Control>, scope: Scope) -> Self {
        Self {
            control,
            scope,
            grants: Grants::new(),
        }
    }
}
impl BrokerControl for Local {
    fn bind_effect_admission(
        &self,
        proof: olo_toolgate_contracts::v2::SignedInvocationPermit,
        arguments: std::collections::BTreeMap<String, serde_json::Value>,
    ) -> Result<(), &'static str> {
        self.scope
            .admission
            .set((proof, arguments))
            .map_err(|_| "effect admission cannot be replaced")
    }
    fn live(&self) -> bool {
        self.scope.live()
    }
    fn close(&self) {
        self.scope.closed.store(true, Ordering::SeqCst);
    }
    fn transport_buffered<'a>(
        &'a self,
        wire: &'a crate::broker_process::BufferedRequest,
    ) -> BoxFuture<'a, Result<crate::broker_process::BufferedResponse, serde_json::Value>> {
        Box::pin(async move {
            let refused = || serde_json::json!({"refused":"buffered transport is not authorized"});
            let (route, destination) = self.scope.target(&wire.manifest).map_err(|_| refused())?;
            if route.max_request_bytes > crate::broker_process::MAX_BODY as u64
                || route.max_response_bytes > crate::broker_process::MAX_BODY as u64
            {
                return Err(refused());
            }
            let service = matches!(destination.id.as_str(), "olo-memory" | "olo-rag");
            let write = service
                && !matches!(
                    wire.manifest.path.as_str(),
                    "/v1/memory.read" | "/v1/rag.query"
                );
            let permit = if service {
                Some(
                    self.grants
                        .expected(
                            &wire.handler_id,
                            &wire.digest,
                            write,
                            wire.effect_key.as_deref(),
                        )
                        .map_err(|_| refused())?,
                )
            } else {
                None
            };
            let bounded = if service {
                None
            } else {
                Some(
                    self.generic_transport(
                        &wire.manifest,
                        &wire.digest,
                        &wire.handler_id,
                        destination,
                        wire.effect_key.as_deref(),
                    )
                    .await?,
                )
            };
            let client = match &bounded {
                Some(transport) => transport.client.clone(),
                None => self.client(destination).map_err(|_| refused())?,
            };
            let timeout = std::time::Duration::from_millis(route.timeout_ms.clamp(
                1,
                self.scope.deadline.saturating_sub(crate::now_ms()).max(1),
            ));
            let frozen =
                crate::broker_process::rebuild_request(&client, wire, permit.as_ref(), timeout)
                    .map_err(|_| refused())?
                    .with_downstream_key(bounded.as_ref().and_then(|b| b.downstream_key.as_ref()))
                    .map_err(|_| refused())?;
            if frozen.body_len() as u64 > route.max_request_bytes {
                return Err(refused());
            }
            if service {
                self.grants
                    .take(
                        &wire.handler_id,
                        &wire.digest,
                        write,
                        wire.effect_key.as_deref(),
                    )
                    .map_err(|_| refused())?;
            }
            if !self.scope.live() {
                return Err(refused());
            }
            let response = frozen.send().await.map_err(crate::broker::failure)?;
            let received =
                crate::broker::collect_response(response, route.max_response_bytes as usize + 1)
                    .await?;
            let response: crate::broker_process::BufferedResponse =
                serde_json::from_value(received.reply)
                    .map_err(|_| serde_json::json!({"failure":"invalid transport response"}))?;
            crate::broker_process::check_response_headers(&response.headers)
                .map_err(|_| serde_json::json!({"failure":"transport response headers too large or invalid"}))?;
            drop(bounded);
            Ok(response)
        })
    }
    fn transport<'a>(
        &'a self,
        prepared: Transport,
        handler: &'a str,
    ) -> BoxFuture<'a, Result<crate::broker::Sent, serde_json::Value>> {
        Box::pin(async move {
            let Transport::Direct(prepared) = prepared else {
                return Err(serde_json::json!({"refused":"invalid direct transport"}));
            };
            let manifest: olo_toolgate_contracts::PreparedHttpRequestManifest =
                serde_json::from_value(prepared.manifest().clone())
                    .map_err(|_| serde_json::json!({"refused":"invalid prepared transport"}))?;
            let (route, destination) = self.scope.target(&manifest).map_err(
                |_| serde_json::json!({"refused":"transport is outside invocation scope"}),
            )?;
            if !identifier(handler) || prepared.body_len() as u64 > route.max_request_bytes {
                return Err(serde_json::json!({"refused":"invalid transport limits or handler"}));
            }
            let service = matches!(destination.id.as_str(), "olo-memory" | "olo-rag");
            let write =
                service && !matches!(manifest.path.as_str(), "/v1/memory.read" | "/v1/rag.query");
            let bounded = if service {
                None
            } else {
                Some(
                    self.generic_transport(
                        &manifest,
                        prepared.digest(),
                        handler,
                        destination,
                        prepared.effect_key(),
                    )
                    .await?,
                )
            };
            let client = match &bounded {
                Some(transport) => transport.client.clone(),
                None => self
                    .client(destination)
                    .map_err(|_| serde_json::json!({"refused":"trusted transport unavailable"}))?,
            };
            if service {
                let permit = self
                    .grants
                    .take(handler, prepared.digest(), write, prepared.effect_key())
                    .map_err(
                        |_| serde_json::json!({"refused":"transport owner is not authorized"}),
                    )?;
                if !prepared.service_permit_is(&permit) {
                    return Err(serde_json::json!({"refused":"transport grant was substituted"}));
                }
            }
            if !self.scope.live() {
                return Err(serde_json::json!({"refused":"invocation has ended"}));
            }
            let response = prepared
                .with_downstream_key(bounded.as_ref().and_then(|b| b.downstream_key.as_ref()))
                .map_err(|_| serde_json::json!({"refused":"invalid target effect key"}))?
                .using_client(client)
                .send()
                .await
                .map_err(crate::broker::failure)?;
            let result = crate::broker::collect_response(
                response,
                usize::try_from(route.max_response_bytes)
                    .unwrap_or(usize::MAX)
                    .saturating_add(1),
            )
            .await;
            drop(bounded);
            result
        })
    }
    fn secret<'a>(
        &'a self,
        invocation: &'a str,
        fence: &'a str,
        name: &'a str,
    ) -> BoxFuture<'a, Result<String, ControlError>> {
        Box::pin(async move {
            self.scope.secret(invocation, fence, name)?;
            self.control
                .secret(&self.scope.invocation, &self.scope.fence, name)
                .await
        })
    }
    fn service_permit<'a>(
        &'a self,
        request: &'a ServicePermitIssue,
    ) -> BoxFuture<'a, Result<ServicePermit, ControlError>> {
        Box::pin(async move {
            self.scope.issue(request)?;
            let handler = request.handler_id.as_str();
            self.grants.reserve_key(
                handler,
                request.http_request_digest.as_str(),
                request.effect_key.as_deref(),
            )?;
            let permit = self.control.service_permit(request).await?;
            if permit.jws.is_empty() || permit.jws.len() > 131072 {
                return Err(ControlError::Unavailable("invalid downstream grant".into()));
            }
            self.grants.issued(handler, permit.clone())?;
            Ok(permit)
        })
    }
    fn service_sending<'a>(
        &'a self,
        request: &'a ServicePermitDispatch,
    ) -> BoxFuture<'a, Result<ServicePermitDispatchAllowed, ControlError>> {
        Box::pin(async move {
            if !self.scope.live()
                || request.broker_instance_id != self.scope.broker
                || !identifier(&request.handler_id)
            {
                return denied();
            }
            self.grants.sending(&request.handler_id, &request.permit)?;
            let decision = self.control.service_sending(request).await?;
            if !decision.authorized {
                return denied();
            }
            self.grants.allowed(&request.handler_id)?;
            self.scope.effect_started.store(true, Ordering::SeqCst);
            Ok(decision)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn scope() -> Scope {
        let corpus: serde_json::Value = serde_json::from_str(include_str!(
            "../../../tests/fixtures/contracts/v2/valid.json"
        ))
        .unwrap();
        let mut route: RouteDeclaration =
            serde_json::from_value(corpus["RouteDeclaration"].clone()).unwrap();
        route.id = "append".into();
        route.destination = "olo-memory".into();
        route.method = olo_toolgate_contracts::v2::HttpMethod::Post;
        route.auth = None;
        let mut destination: ServiceDestination =
            serde_json::from_value(corpus["ServiceDestination"].clone()).unwrap();
        destination.id = "olo-memory".into();
        destination.host = "memory.example.test".into();
        destination.port = 443;
        destination.scheme = olo_toolgate_contracts::v2::DestinationScheme::Https;
        destination.mode = olo_toolgate_contracts::v2::EgressMode::Brokered;
        Scope {
            invocation: "operation".into(),
            fence: "fence".into(),
            deadline: crate::now_ms() + 60_000,
            profile: "profile".into(),
            profile_digest: format!("sha256:{}", "a".repeat(64)),
            broker: "broker".into(),
            routes: vec![route],
            destinations: vec![destination],
            secret_names: HashSet::from(["declared".into()]),
            closed: Arc::new(AtomicBool::new(false)),
            effect_started: Arc::new(AtomicBool::new(false)),
            private_ranges: Vec::new(),
            auths: Vec::new(),
            development_loopback_http: false,
            admission: std::sync::OnceLock::new(),
        }
    }
    fn issue(scope: &Scope) -> ServicePermitIssue {
        let manifest = serde_json::from_value(json!({
            "version":"toolgate-http-request-v1","profileId":scope.profile,"profileDigest":scope.profile_digest,
            "routeId":"append","scheme":"https","host":"memory.example.test","port":443,"method":"POST",
            "path":"/v1/memory.append","query":"","contentType":"application/json","bodyDigest":format!("sha256:{}", "b".repeat(64)),
            "headers":{"accept":["*/*"]},"authDeclarationId":"toolgate-service-permit:olo-memory","credentialHeaders":["toolgate-service-permit"]
        })).unwrap();
        let digest = crate::digest::jcs(&serde_json::to_value(&manifest).unwrap()).unwrap();
        ServicePermitIssue {
            invocation_id: scope.invocation.clone(),
            fencing_token: scope.fence.clone(),
            audience: olo_toolgate_contracts::ServiceAudience::OloMemory,
            http_request_digest: digest,
            http_request_manifest: manifest,
            invocation_permit: olo_toolgate_contracts::v2::SignedInvocationPermit {
                jws: "test-admission".into(),
            },
            broker_instance_id: scope.broker.clone(),
            handler_id: "handler".into(),
            effect_key: None,
        }
    }
    #[test]
    fn an_issued_service_key_cannot_be_substituted_at_the_private_transport_boundary() {
        let grants = Grants::new();
        let permit = ServicePermit {
            jws: "issued".into(),
        };
        grants
            .reserve_key("handler", "digest", Some("charge"))
            .unwrap();
        grants.issued("handler", permit.clone()).unwrap();
        grants.sending("handler", &permit).unwrap();
        grants.allowed("handler").unwrap();
        assert!(grants
            .expected("handler", "digest", true, Some("receipt"))
            .is_err());
        assert!(grants
            .take("handler", "digest", true, Some("receipt"))
            .is_err());
        assert!(grants.take("handler", "digest", true, None).is_err());
        assert!(grants
            .expected("handler", "digest", true, Some("charge"))
            .is_ok());
        assert!(grants
            .take("handler", "digest", true, Some("charge"))
            .is_ok());
        assert!(grants
            .take("handler", "digest", true, Some("charge"))
            .is_err());
    }
    #[test]
    fn scoped_secrets_refuse_other_operations_fences_and_undeclared_names() {
        let scope = scope();
        assert!(scope.secret("operation", "fence", "declared").is_ok());
        for (invocation, fence, name) in [
            ("other", "fence", "declared"),
            ("operation", "other", "declared"),
            ("operation", "fence", "other"),
        ] {
            assert!(scope.secret(invocation, fence, name).is_err());
        }
        scope.closed.store(true, Ordering::SeqCst);
        assert!(scope.secret("operation", "fence", "declared").is_err());
    }
    #[test]
    fn only_this_immutable_profile_owner_and_exact_manifest_can_request_a_grant() {
        let scope = scope();
        let valid = issue(&scope);
        assert!(scope.issue(&valid).is_ok());
        for field in [
            "operation",
            "fence",
            "broker",
            "handler",
            "profile",
            "route",
            "host",
            "port",
            "method",
            "audience",
            "digest",
            "unsigned",
        ] {
            let mut changed = valid.clone();
            match field {
                "operation" => changed.invocation_id = "other".into(),
                "fence" => changed.fencing_token = "other".into(),
                "broker" => changed.broker_instance_id = "other".into(),
                "handler" => changed.handler_id = "../escape".into(),
                "profile" => {
                    changed.http_request_manifest.profile_digest =
                        format!("sha256:{}", "c".repeat(64))
                }
                "route" => changed.http_request_manifest.route_id = "other".into(),
                "host" => changed.http_request_manifest.host = "control.example.test".into(),
                "port" => changed.http_request_manifest.port = 8443,
                "method" => changed.http_request_manifest.method = "GET".into(),
                "audience" => changed.audience = olo_toolgate_contracts::ServiceAudience::OloRag,
                "digest" => changed.http_request_digest = "sha256:wrong".into(),
                "unsigned" => changed.invocation_permit.jws.clear(),
                _ => unreachable!(),
            }
            assert!(scope.issue(&changed).is_err(), "{field}");
        }
    }
    #[test]
    fn expiry_and_closure_refuse_new_grants() {
        let mut scope = scope();
        let request = issue(&scope);
        scope.closed.store(true, Ordering::SeqCst);
        assert!(scope.issue(&request).is_err());
        scope.closed.store(false, Ordering::SeqCst);
        scope.deadline = crate::now_ms();
        assert!(scope.issue(&request).is_err());
    }
    #[test]
    fn handlers_are_bounded_single_use_even_after_a_lost_authority_response() {
        let grants = Grants::new();
        grants.reserve("lost", "digest").unwrap();
        assert!(grants.reserve("lost", "digest").is_err());
        let permit = ServicePermit {
            jws: "test-permit".into(),
        };
        assert!(grants.sending("lost", &permit).is_err());
        grants.reserve("issued", "digest").unwrap();
        grants.issued("issued", permit.clone()).unwrap();
        assert!(grants.sending("other", &permit).is_err());
        assert!(grants
            .sending(
                "issued",
                &ServicePermit {
                    jws: "borrowed".into()
                }
            )
            .is_err());
        grants.sending("issued", &permit).unwrap();
        assert!(grants.sending("issued", &permit).is_err());
        assert!(grants.issued("issued", permit).is_err());
        for n in 2..256 {
            grants.reserve(&format!("handler-{n}"), "digest").unwrap();
        }
        assert!(grants.reserve("overflow", "digest").is_err());
    }
    #[tokio::test]
    async fn explicit_service_effect_keys_reach_control_and_bind_single_use_grants() {
        let observed = Arc::new(Mutex::new(Vec::<ServicePermitIssue>::new()));
        let captured = observed.clone();
        let app = axum::Router::new().fallback(
            move |axum::Json(request): axum::Json<ServicePermitIssue>| {
                let captured = captured.clone();
                async move {
                    captured.lock().unwrap().push(request);
                    axum::Json(ServicePermit {
                        jws: "issued-permit".into(),
                    })
                }
            },
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let config = serde_json::from_value(json!({
            "tenantId":"10000000-0000-4000-8000-000000000001",
            "url":format!("http://{}", listener.local_addr().unwrap()),
            "developmentLoopbackHttp":true
        }))
        .unwrap();
        let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        for (audience, destination) in [
            (
                olo_toolgate_contracts::ServiceAudience::OloMemory,
                "olo-memory",
            ),
            (olo_toolgate_contracts::ServiceAudience::OloRag, "olo-rag"),
        ] {
            let mut scope = scope();
            scope.routes[0].destination = destination.into();
            scope.destinations[0].id = destination.into();
            let mut request = issue(&scope);
            request.audience = audience;
            request.http_request_manifest.auth_declaration_id =
                json!(format!("toolgate-service-permit:{destination}"));
            request.http_request_manifest.path = if destination == "olo-rag" {
                "/v1/rag.upsert".into()
            } else {
                "/v1/memory.append".into()
            };
            request.http_request_digest =
                crate::digest::jcs(&serde_json::to_value(&request.http_request_manifest).unwrap())
                    .unwrap();
            request.effect_key = Some("document-write".into());
            let port = Local::new(Arc::new(Control::new(&config).unwrap()), scope);
            let permit = port.service_permit(&request).await.unwrap();
            let captured = observed.lock().unwrap().last().unwrap().clone();
            assert_eq!(captured.effect_key.as_deref(), Some("document-write"));
            assert_eq!(captured.http_request_digest, request.http_request_digest);
            assert_eq!(captured.audience, request.audience);
            port.grants.sending(&request.handler_id, &permit).unwrap();
            port.grants.allowed(&request.handler_id).unwrap();
            for key in [None, Some("other-write")] {
                assert!(port
                    .grants
                    .take(&request.handler_id, &request.http_request_digest, true, key)
                    .is_err());
            }
            assert!(port
                .grants
                .take(
                    &request.handler_id,
                    &request.http_request_digest,
                    true,
                    request.effect_key.as_deref()
                )
                .is_ok());
            assert!(port
                .grants
                .take(
                    &request.handler_id,
                    &request.http_request_digest,
                    true,
                    request.effect_key.as_deref()
                )
                .is_err());
        }
        assert_eq!(observed.lock().unwrap().len(), 2);
        server.abort();
    }

    #[tokio::test]
    async fn refused_scope_requests_contact_no_control_endpoint() {
        use std::sync::atomic::AtomicUsize;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let calls = Arc::new(AtomicUsize::new(0));
        let observed = calls.clone();
        let app = axum::Router::new().fallback(move || {
            let observed = observed.clone();
            async move {
                observed.fetch_add(1, Ordering::SeqCst);
                axum::Json(json!({"value":"test-value"}))
            }
        });
        let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let config = serde_json::from_value(json!({
            "tenantId":"10000000-0000-4000-8000-000000000001",
            "url":url,"developmentLoopbackHttp":true
        }))
        .unwrap();
        let control = Arc::new(Control::new(&config).unwrap());
        let scope = scope();
        let valid = issue(&scope);
        let port = Local::new(control, scope);
        // Prove the live fixture would observe an allowed Control call.
        assert_eq!(
            port.secret("operation", "fence", "declared").await.unwrap(),
            "test-value"
        );
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        for (invocation, fence, name) in [
            ("other", "fence", "declared"),
            ("operation", "other", "declared"),
            ("operation", "fence", "undeclared"),
        ] {
            assert_eq!(
                port.secret(invocation, fence, name).await,
                Err(ControlError::Refused(403))
            );
        }
        let mut foreign = valid.clone();
        foreign.invocation_id = "other".into();
        assert_eq!(
            port.service_permit(&foreign).await,
            Err(ControlError::Refused(403))
        );
        let dispatch = ServicePermitDispatch {
            permit: ServicePermit {
                jws: "borrowed".into(),
            },
            broker_instance_id: "broker".into(),
            handler_id: "never-issued".into(),
        };
        assert_eq!(
            port.service_sending(&dispatch).await,
            Err(ControlError::Refused(403))
        );
        port.scope.closed.store(true, Ordering::SeqCst);
        assert_eq!(
            port.secret("operation", "fence", "declared").await,
            Err(ControlError::Refused(403))
        );
        assert_eq!(
            port.service_permit(&valid).await,
            Err(ControlError::Refused(403))
        );
        assert_eq!(
            calls.load(Ordering::SeqCst),
            1,
            "denied operations must not reach Control"
        );
        server.abort();
    }
    #[test]
    fn auths_on_ungranted_routes_do_not_expand_the_secret_port() {
        let corpus: serde_json::Value = serde_json::from_str(include_str!(
            "../../../tests/fixtures/contracts/v2/valid.json"
        ))
        .unwrap();
        let mut route = scope().routes.remove(0);
        route.auth = None;
        let mut auth: HttpAuthDeclaration =
            serde_json::from_value(corpus["HttpAuthDeclaration"].clone()).unwrap();
        auth.id = "route-auth".into();
        auth.secret = Some("brokered".into());
        let mut declared: SecretDeclaration =
            serde_json::from_value(corpus["SecretDeclaration"].clone()).unwrap();
        declared.name = "declared".into();
        assert_eq!(
            secret_names(&[route.clone()], &[auth.clone()], &[declared.clone()]),
            HashSet::from(["declared".into()])
        );
        route.auth = Some(auth.id.clone());
        assert_eq!(
            secret_names(&[route], &[auth], &[declared]),
            HashSet::from(["declared".into(), "brokered".into()])
        );
    }
    #[test]
    fn a_write_transport_needs_a_positive_gate_and_its_exact_digest_once() {
        let grants = Grants::new();
        let permit = ServicePermit {
            jws: "issued".into(),
        };
        grants.reserve("write", "digest").unwrap();
        grants.issued("write", permit.clone()).unwrap();
        assert!(grants.take("write", "digest", true, None).is_err());
        grants.sending("write", &permit).unwrap();
        assert!(
            grants.take("write", "digest", true, None).is_err(),
            "an HTTP error/false or lost decision is not an allowed gate"
        );
        grants.allowed("write").unwrap();
        assert!(grants.take("write", "changed", true, None).is_err());
        assert_eq!(grants.take("write", "digest", true, None).unwrap(), permit);
        assert!(grants.take("write", "digest", true, None).is_err());
        grants.reserve("read", "read-digest").unwrap();
        grants.issued("read", permit.clone()).unwrap();
        assert_eq!(
            grants.take("read", "read-digest", false, None).unwrap(),
            permit
        );
        assert!(grants.take("read", "read-digest", false, None).is_err());
    }
    #[test]
    fn concurrent_transport_delegates_have_one_owner_after_the_same_allowed_gate() {
        let grants = Arc::new(Grants::new());
        let permit = ServicePermit {
            jws: "issued".into(),
        };
        grants.reserve("write", "digest").unwrap();
        grants.issued("write", permit.clone()).unwrap();
        grants.sending("write", &permit).unwrap();
        grants.allowed("write").unwrap();
        let mut handles = Vec::new();
        for _ in 0..8 {
            let grants = grants.clone();
            handles.push(std::thread::spawn(move || {
                grants.take("write", "digest", true, None).is_ok()
            }));
        }
        assert_eq!(
            handles
                .into_iter()
                .map(|h| h.join().unwrap())
                .filter(|won| *won)
                .count(),
            1
        );
    }

    #[tokio::test]
    async fn buffered_transport_checks_scope_body_and_replay_before_actual_target_contact() {
        use std::sync::atomic::AtomicUsize;
        let calls = Arc::new(AtomicUsize::new(0));
        let seen = calls.clone();
        let app = axum::Router::new().fallback(move || {
            let seen = seen.clone();
            async move {
                seen.fetch_add(1, Ordering::SeqCst);
                axum::Json(serde_json::json!({"ok":true}))
            }
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let foreign_listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let foreign_address = foreign_listener.local_addr().unwrap();
        let foreign_server_app = app.clone();
        let foreign_server = tokio::spawn(async move {
            axum::serve(foreign_listener, foreign_server_app)
                .await
                .unwrap()
        });
        let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let control_config=serde_json::from_value(serde_json::json!({"tenantId":"10000000-0000-4000-8000-000000000001","url":format!("http://{address}"),"developmentLoopbackHttp":true})).unwrap();
        let control = Arc::new(Control::new(&control_config).unwrap());
        let mut scope = scope();
        scope.development_loopback_http = true;
        scope.destinations[0].id = "approved".into();
        scope.destinations[0].host = "127.0.0.1".into();
        scope.destinations[0].port = address.port() as u64;
        scope.routes[0].destination = "approved".into();
        scope.routes[0].max_request_bytes = 1024;
        scope.routes[0].max_response_bytes = 1024;
        let client = reqwest::Client::builder().no_proxy().build().unwrap();
        let raw = client
            .post(format!("http://{address}/allowed"))
            .body("{}")
            .build()
            .unwrap();
        let prepared = crate::request_binding::PreparedRequest::new(
            &client,
            raw.try_clone().unwrap(),
            crate::request_binding::Metadata {
                profile_id: scope.profile.clone(),
                profile_digest: scope.profile_digest.clone(),
                route_id: scope.routes[0].id.clone(),
                auth_id: None,
                credential_headers: Vec::new(),
            },
        )
        .unwrap();
        let manifest = serde_json::from_value(prepared.manifest().clone()).unwrap();
        let wire =
            crate::broker_process::freeze_request(&client, raw, &manifest, "handler").unwrap();
        let port = Local::new(control, scope);
        let mut foreign = wire.clone();
        foreign.manifest.port = foreign_address.port() as u64;
        foreign.digest =
            crate::digest::jcs(&serde_json::to_value(&foreign.manifest).unwrap()).unwrap();
        assert!(port.transport_buffered(&foreign).await.is_err());
        assert_eq!(calls.load(Ordering::SeqCst), 0);
        // A syntactically valid generic transport still has no authority without original admission.
        // Allowed direct/private-child TLS sends are witnessed by tests/generic_dispatch.rs.
        assert!(port.transport_buffered(&wire).await.is_err());
        assert!(port.transport_buffered(&wire).await.is_err());
        assert_eq!(calls.load(Ordering::SeqCst), 0);
        server.abort();
        foreign_server.abort();
    }
}
