// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Private, bounded supervisor/broker IPC. This is not the worker protocol or a network API.
use olo_toolgate_contracts::v2::{
    BrokerRequestFrame, BrokerResponseFrame, HttpAuthDeclaration, PrivateRangeApproval,
    RouteDeclaration, SecretDeclaration, ServiceDestination, SignedInvocationPermit,
};
use olo_toolgate_contracts::{PreparedHttpRequestManifest, ServiceAudience, ServicePermit};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use std::collections::{BTreeMap, HashSet};
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWrite, AsyncWriteExt};

pub const MAX_FRAME: usize = 4 * 1024 * 1024;
const MAX_FRAMES: usize = 4096;
const MAX_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_CALLS: u64 = 256;
const MAX_AUTHORITY_CALLS: u64 = 1024;
pub const MAX_BODY: usize = 2 * 1024 * 1024;
const MAX_HEADERS: usize = 64 * 1024;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum Protocol {
    #[serde(rename = "toolgate-invocation-broker-v1")]
    V1,
}

/// Admission-derived policy only: no Control URL, workload identity, private key or host path.
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Policy {
    pub invocation_id: String,
    pub fencing_token: String,
    pub deadline_unix_ms: u64,
    pub profile_id: String,
    pub profile_digest: String,
    pub broker_instance_id: String,
    pub invocation_permit: SignedInvocationPermit,
    pub routes: Vec<RouteDeclaration>,
    pub destinations: Vec<ServiceDestination>,
    pub private_ranges: Vec<PrivateRangeApproval>,
    pub auths: Vec<HttpAuthDeclaration>,
    pub secrets: Vec<SecretDeclaration>,
    pub secret_handles: BTreeMap<String, String>,
    pub development_loopback_http: bool,
}
fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._:-".contains(&b))
}
pub(crate) fn supports_routes(
    routes: &[RouteDeclaration],
    destinations: &[ServiceDestination],
) -> bool {
    use olo_toolgate_contracts::v2::{DestinationScheme, EgressMode, Streaming};
    routes.len() <= 256
        && destinations.len() <= 64
        && routes.iter().map(|r| &r.id).collect::<HashSet<_>>().len() == routes.len()
        && destinations
            .iter()
            .map(|d| &d.id)
            .collect::<HashSet<_>>()
            .len()
            == destinations.len()
        && routes.iter().all(|route| {
            route.max_request_bytes <= MAX_BODY as u64
                && route.max_response_bytes <= MAX_BODY as u64
                && route.streaming == Streaming::None
                && destinations
                    .iter()
                    .find(|d| d.id == route.destination)
                    .is_some_and(|d| {
                        d.scheme == DestinationScheme::Https && d.mode == EgressMode::Brokered
                    })
        })
}
impl Policy {
    pub fn validate(&self, now: u64) -> Result<(), &'static str> {
        if !identifier(&self.invocation_id)
            || !identifier(&self.fencing_token)
            || !identifier(&self.broker_instance_id)
            || !identifier(&self.profile_id)
            || self.deadline_unix_ms <= now
            || self.deadline_unix_ms > 9_007_199_254_740_991
            || !self
                .profile_digest
                .strip_prefix("sha256:")
                .is_some_and(|v| {
                    v.len() == 64
                        && v.bytes()
                            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                })
            || self.invocation_permit.jws.is_empty()
            || self.invocation_permit.jws.len() > 131072
            || !supports_routes(&self.routes, &self.destinations)
            || self.private_ranges.len() > 64
            || self.auths.len() > 128
            || self.secrets.len() > 128
            || self.secret_handles.len() > 128
        {
            return Err("invalid broker policy");
        }
        let routes: HashSet<_> = self.routes.iter().map(|r| &r.id).collect();
        let destinations: HashSet<_> = self.destinations.iter().map(|d| &d.id).collect();
        let auths: HashSet<_> = self.auths.iter().map(|a| &a.id).collect();
        let secrets: HashSet<_> = self.secrets.iter().map(|s| &s.name).collect();
        if routes.len() != self.routes.len()
            || destinations.len() != self.destinations.len()
            || auths.len() != self.auths.len()
            || secrets.len() != self.secrets.len()
            || self.routes.iter().any(|r| {
                !identifier(&r.id)
                    || !destinations.contains(&r.destination)
                    || r.auth.as_ref().is_some_and(|a| !auths.contains(a))
            })
            || self
                .secret_handles
                .iter()
                .any(|(handle, name)| !identifier(handle) || !secrets.contains(name))
        {
            return Err("ambiguous or unbound broker policy");
        }
        Ok(())
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BufferedRequest {
    pub handler_id: String,
    pub manifest: PreparedHttpRequestManifest,
    pub digest: String,
    pub headers: BTreeMap<String, Vec<String>>,
    pub body_base64: Option<String>,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BufferedResponse {
    pub status: u16,
    pub headers: BTreeMap<String, Vec<String>>,
    pub body_base64: String,
}

/// Consuming transport: there is no mutation or clone API after verification.
pub struct FrozenTransport {
    prepared: crate::request_binding::PreparedRequest,
}
impl FrozenTransport {
    pub fn body_len(&self) -> usize {
        self.prepared.body_len()
    }
    pub fn digest(&self) -> &str {
        self.prepared.digest()
    }
    pub fn manifest(&self) -> &serde_json::Value {
        self.prepared.manifest()
    }
    pub async fn send(self) -> Result<reqwest::Response, reqwest::Error> {
        self.prepared.send().await
    }
}
fn metadata(
    manifest: &PreparedHttpRequestManifest,
) -> Result<crate::request_binding::Metadata, &'static str> {
    let auth_id = match &manifest.auth_declaration_id {
        serde_json::Value::Null => None,
        serde_json::Value::String(value) => Some(value.clone()),
        _ => return Err("invalid broker credential declaration"),
    };
    Ok(crate::request_binding::Metadata {
        profile_id: manifest.profile_id.clone(),
        profile_digest: manifest.profile_digest.clone(),
        route_id: manifest.route_id.clone(),
        auth_id,
        credential_headers: manifest.credential_headers.clone(),
    })
}
fn check_headers(headers: &BTreeMap<String, Vec<String>>) -> Result<(), &'static str> {
    if headers.len() > 256 {
        return Err("too many broker headers");
    }
    let mut bytes = 0usize;
    for (name, values) in headers {
        if name != &name.to_ascii_lowercase()
            || values.is_empty()
            || values.len() > 64
            || matches!(
                name.as_str(),
                "host"
                    | "content-length"
                    | "transfer-encoding"
                    | "connection"
                    | "user-agent"
                    | "traceparent"
                    | "tracestate"
            )
            || reqwest::header::HeaderName::from_bytes(name.as_bytes()).is_err()
        {
            return Err("invalid broker header");
        }
        bytes = bytes.saturating_add(name.len());
        for value in values {
            bytes = bytes.saturating_add(value.len());
            if value.len() > 8192
                || !value.is_ascii()
                || reqwest::header::HeaderValue::from_str(value).is_err()
            {
                return Err("invalid broker header value");
            }
        }
        if bytes > MAX_HEADERS {
            return Err("broker headers too large");
        }
    }
    Ok(())
}
pub(crate) fn check_response_headers(
    headers: &BTreeMap<String, Vec<String>>,
) -> Result<(), &'static str> {
    let mut bytes = 0usize;
    if headers.len() > 256 {
        return Err("too many broker response headers");
    }
    for (name, values) in headers {
        if name != &name.to_ascii_lowercase()
            || reqwest::header::HeaderName::from_bytes(name.as_bytes()).is_err()
            || values.is_empty()
            || values.len() > 32
        {
            return Err("invalid broker response header");
        }
        bytes = bytes.saturating_add(name.len());
        for value in values {
            bytes = bytes.saturating_add(value.len());
            if value.len() > 8192
                || !value.is_ascii()
                || reqwest::header::HeaderValue::from_str(value).is_err()
            {
                return Err("invalid broker response header value");
            }
        }
        if bytes > MAX_HEADERS {
            return Err("broker response headers too large");
        }
    }
    Ok(())
}
/// Encode the already bound buffered request; no target I/O occurs here.
pub fn freeze_request(
    client: &reqwest::Client,
    request: reqwest::Request,
    manifest: &PreparedHttpRequestManifest,
    handler: &str,
) -> Result<BufferedRequest, &'static str> {
    use base64::Engine;
    if !identifier(handler) {
        return Err("invalid broker handler");
    }
    let mut headers = BTreeMap::<String, Vec<String>>::new();
    for (name, value) in request.headers() {
        headers.entry(name.as_str().into()).or_default().push(
            value
                .to_str()
                .map_err(|_| "non-ASCII broker header")?
                .into(),
        );
    }
    check_headers(&headers)?;
    let body_base64 = match request.body() {
        None => None,
        Some(body) => {
            let bytes = body.as_bytes().ok_or("broker body is not buffered")?;
            if bytes.len() > MAX_BODY {
                return Err("broker body too large");
            }
            Some(base64::engine::general_purpose::STANDARD.encode(bytes))
        }
    };
    let prepared =
        crate::request_binding::PreparedRequest::new(client, request, metadata(manifest)?)
            .map_err(|_| "invalid broker request")?;
    if prepared.manifest()
        != &serde_json::to_value(manifest).map_err(|_| "invalid broker manifest")?
    {
        return Err("broker manifest does not match request");
    }
    Ok(BufferedRequest {
        handler_id: handler.into(),
        manifest: manifest.clone(),
        digest: prepared.digest().into(),
        headers,
        body_base64,
    })
}
/// Scope/owner checks remain mandatory in the supervisor before this reconstruction or its single send.
pub fn rebuild_request(
    client: &reqwest::Client,
    wire: &BufferedRequest,
    permit: Option<&ServicePermit>,
    timeout: std::time::Duration,
) -> Result<FrozenTransport, &'static str> {
    use base64::Engine;
    if !identifier(&wire.handler_id) || timeout.is_zero() {
        return Err("invalid broker transport binding");
    }
    check_headers(&wire.headers)?;
    for name in &wire.manifest.credential_headers {
        if wire
            .headers
            .get(name)
            .is_none_or(|values| values.len() != 1)
        {
            return Err("missing or ambiguous broker credential");
        }
    }
    let service = wire
        .manifest
        .credential_headers
        .iter()
        .any(|h| h == "toolgate-service-permit");
    if service != permit.is_some() {
        return Err("broker service permit binding is missing");
    }
    if let Some(permit) = permit {
        if permit.jws.is_empty() || permit.jws.len() > 131072 {
            return Err("invalid supervisor service permit");
        }
        if wire
            .headers
            .get("toolgate-service-permit")
            .is_none_or(|values| values.as_slice() != [permit.jws.as_str()])
        {
            return Err("broker service permit was substituted");
        }
    }
    let host = if wire.manifest.host.contains(':') {
        format!("[{}]", wire.manifest.host)
    } else {
        wire.manifest.host.clone()
    };
    if !wire.manifest.path.starts_with('/') || wire.manifest.path.contains(['?', '#']) {
        return Err("invalid broker path");
    }
    let query = if wire.manifest.query.is_empty() {
        String::new()
    } else {
        format!("?{}", wire.manifest.query)
    };
    let url = format!(
        "{}://{}:{}{}{}",
        wire.manifest.scheme, host, wire.manifest.port, wire.manifest.path, query
    );
    let method = reqwest::Method::from_bytes(wire.manifest.method.as_bytes())
        .map_err(|_| "invalid broker method")?;
    let mut builder = client.request(method, url).timeout(timeout);
    for (name, values) in &wire.headers {
        if name == "toolgate-service-permit" {
            continue;
        }
        for value in values {
            let mut value = reqwest::header::HeaderValue::from_str(value)
                .map_err(|_| "invalid broker header")?;
            if wire.manifest.credential_headers.iter().any(|h| h == name) {
                value.set_sensitive(true);
            }
            builder = builder.header(name, value);
        }
    }
    if let Some(encoded) = &wire.body_base64 {
        if encoded.len() > MAX_BODY.div_ceil(3) * 4 {
            return Err("broker body too large");
        }
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(encoded)
            .map_err(|_| "invalid broker body encoding")?;
        if bytes.len() > MAX_BODY {
            return Err("broker body too large");
        }
        builder = builder.body(bytes);
    }
    let request = builder
        .build()
        .map_err(|_| "invalid broker transport request")?;
    let mut prepared =
        crate::request_binding::PreparedRequest::new(client, request, metadata(&wire.manifest)?)
            .map_err(|_| "invalid reconstructed broker request")?;
    if prepared.manifest()
        != &serde_json::to_value(&wire.manifest).map_err(|_| "invalid broker manifest")?
        || prepared.digest() != wire.digest
    {
        return Err("broker request fingerprint changed");
    }
    if let Some(permit) = permit {
        prepared = prepared
            .with_service_permit(&permit.jws)
            .map_err(|_| "invalid service permit header")?;
    }
    Ok(FrozenTransport { prepared })
}

/// Scope selectors are intentionally absent; the supervisor fixes them from admission.
#[derive(Clone, Serialize, Deserialize)]
#[serde(
    tag = "operation",
    rename_all = "SCREAMING_SNAKE_CASE",
    deny_unknown_fields
)]
pub enum AuthorityRequest {
    Secret {
        name: String,
    },
    Issue {
        audience: ServiceAudience,
        manifest: PreparedHttpRequestManifest,
        digest: String,
        #[serde(rename = "handlerId")]
        handler_id: String,
    },
    Sending {
        permit: ServicePermit,
        #[serde(rename = "handlerId")]
        handler_id: String,
    },
    Transport {
        request: BufferedRequest,
    },
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(
    tag = "result",
    rename_all = "SCREAMING_SNAKE_CASE",
    deny_unknown_fields
)]
pub enum AuthorityReply {
    Secret { value: String },
    Permit { permit: ServicePermit },
    Sending {},
    Transport { response: BufferedResponse },
    Refused {},
    Unavailable {},
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "SCREAMING_SNAKE_CASE", deny_unknown_fields)]
pub enum ToBroker {
    Start {
        protocol: Protocol,
        policy: Box<Policy>,
    },
    Call {
        sequence: u64,
        request: BrokerRequestFrame,
    },
    AuthorityReply {
        sequence: u64,
        reply: AuthorityReply,
    },
    Close {},
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "SCREAMING_SNAKE_CASE", deny_unknown_fields)]
pub enum FromBroker {
    Ready {
        protocol: Protocol,
        #[serde(rename = "invocationId")]
        invocation_id: String,
        #[serde(rename = "brokerInstanceId")]
        broker_instance_id: String,
    },
    Authority {
        sequence: u64,
        request: Box<AuthorityRequest>,
    },
    Response {
        sequence: u64,
        response: BrokerResponseFrame,
        egress: Vec<crate::broker::Egress>,
    },
}

/// Private-channel delegate; implementations never receive caller-selected scope identifiers.
pub trait Delegate: Send + Sync {
    fn live(&self) -> bool;
    fn close(&self);
    fn policy(&self) -> &Policy;
    fn answer<'a>(
        &'a self,
        request: &'a AuthorityRequest,
    ) -> futures_util::future::BoxFuture<'a, AuthorityReply>;
}
pub struct ParentAuthority {
    policy: Policy,
    port: std::sync::Arc<dyn crate::broker_authority::BrokerControl>,
}
impl ParentAuthority {
    pub fn from_invocation(invocation: &crate::broker::Invocation) -> Result<Self, &'static str> {
        Ok(Self {
            policy: invocation.broker_policy()?,
            port: invocation.authority_port(),
        })
    }
    pub fn policy(&self) -> &Policy {
        &self.policy
    }
}
fn authority_error(error: crate::control::ControlError) -> AuthorityReply {
    match error {
        crate::control::ControlError::Refused(_) => AuthorityReply::Refused {},
        _ => AuthorityReply::Unavailable {},
    }
}
impl Delegate for ParentAuthority {
    fn live(&self) -> bool {
        self.port.live()
    }
    fn close(&self) {
        self.port.close();
    }
    fn policy(&self) -> &Policy {
        &self.policy
    }
    fn answer<'a>(
        &'a self,
        request: &'a AuthorityRequest,
    ) -> futures_util::future::BoxFuture<'a, AuthorityReply> {
        Box::pin(async move {
            match request {
                AuthorityRequest::Secret { name } => match self
                    .port
                    .secret(&self.policy.invocation_id, &self.policy.fencing_token, name)
                    .await
                {
                    Ok(value) => AuthorityReply::Secret { value },
                    Err(error) => authority_error(error),
                },
                AuthorityRequest::Issue {
                    audience,
                    manifest,
                    digest,
                    handler_id,
                } => {
                    let issue = olo_toolgate_contracts::ServicePermitIssue {
                        invocation_id: self.policy.invocation_id.clone(),
                        fencing_token: self.policy.fencing_token.clone(),
                        audience: audience.clone(),
                        http_request_digest: Some(digest.clone()),
                        http_request_manifest: Some(manifest.clone()),
                        invocation_permit: Some(self.policy.invocation_permit.clone()),
                        broker_instance_id: Some(self.policy.broker_instance_id.clone()),
                        handler_id: Some(handler_id.clone()),
                    };
                    match self.port.service_permit(&issue).await {
                        Ok(permit) => AuthorityReply::Permit { permit },
                        Err(error) => authority_error(error),
                    }
                }
                AuthorityRequest::Sending { permit, handler_id } => {
                    let sending = olo_toolgate_contracts::ServicePermitDispatch {
                        permit: permit.clone(),
                        broker_instance_id: self.policy.broker_instance_id.clone(),
                        handler_id: handler_id.clone(),
                    };
                    match self.port.service_sending(&sending).await {
                        Ok(value) if value.authorized => AuthorityReply::Sending {},
                        Ok(_) => AuthorityReply::Refused {},
                        Err(error) => authority_error(error),
                    }
                }
                AuthorityRequest::Transport { request } => {
                    match self.port.transport_buffered(request).await {
                        Ok(response) => AuthorityReply::Transport { response },
                        Err(error) if error.get("refused").is_some() => AuthorityReply::Refused {},
                        Err(_) => AuthorityReply::Unavailable {},
                    }
                }
            }
        })
    }
}

/// Only one worker call and one privileged request may be outstanding. Any mismatch poisons the session.
pub struct Session {
    invocation: String,
    broker: String,
    ready: bool,
    call: Option<u64>,
    last_call: u64,
    authority: Option<u64>,
    last_authority: u64,
    closed: bool,
}
impl Session {
    pub fn new(invocation: String, broker: String) -> Result<Self, &'static str> {
        if !identifier(&invocation) || !identifier(&broker) {
            return Err("invalid broker binding");
        }
        Ok(Self {
            invocation,
            broker,
            ready: false,
            call: None,
            last_call: 0,
            authority: None,
            last_authority: 0,
            closed: false,
        })
    }
    fn poison<T>(&mut self) -> Result<T, &'static str> {
        self.closed = true;
        Err("broker protocol out of sequence")
    }
    pub fn ready(&mut self, invocation: &str, broker: &str) -> Result<(), &'static str> {
        if self.closed || self.ready || invocation != self.invocation || broker != self.broker {
            return self.poison();
        }
        self.ready = true;
        Ok(())
    }
    pub fn call(&mut self) -> Result<u64, &'static str> {
        if self.closed || !self.ready || self.call.is_some() || self.last_call >= MAX_CALLS {
            return self.poison();
        }
        self.last_call += 1;
        self.call = Some(self.last_call);
        Ok(self.last_call)
    }
    pub fn authority(&mut self, sequence: u64) -> Result<(), &'static str> {
        if self.closed
            || self.call.is_none()
            || self.authority.is_some()
            || self.last_authority >= MAX_AUTHORITY_CALLS
            || sequence != self.last_authority + 1
        {
            return self.poison();
        }
        self.last_authority = sequence;
        self.authority = Some(sequence);
        Ok(())
    }
    pub fn answered(&mut self, sequence: u64) -> Result<(), &'static str> {
        if self.closed || self.authority != Some(sequence) {
            return self.poison();
        }
        self.authority = None;
        Ok(())
    }
    pub fn response(&mut self, sequence: u64) -> Result<(), &'static str> {
        if self.closed || self.authority.is_some() || self.call != Some(sequence) {
            return self.poison();
        }
        self.call = None;
        Ok(())
    }
    pub fn close(&mut self) {
        self.closed = true;
    }
}

pub struct CallReply {
    pub response: BrokerResponseFrame,
    pub egress: Vec<crate::broker::Egress>,
}
/// The delegate supplies both the policy and authority; a caller cannot pair another operation's delegate.
pub struct ParentChannel<R, W> {
    reader: R,
    writer: W,
    budget: Budget,
    session: Session,
    delegate: std::sync::Arc<dyn Delegate>,
    deadline: u64,
    egress: Vec<crate::broker::Egress>,
}
impl<R: AsyncBufRead + Unpin + Send, W: AsyncWrite + Unpin + Send> ParentChannel<R, W> {
    pub async fn open(
        reader: R,
        writer: W,
        delegate: std::sync::Arc<dyn Delegate>,
    ) -> Result<Self, &'static str> {
        if !delegate.live() {
            return Err("broker authority has ended");
        }
        let policy = delegate.policy().clone();
        if let Err(error) = policy.validate(crate::now_ms()) {
            delegate.close();
            return Err(error);
        }
        let session = Session::new(
            policy.invocation_id.clone(),
            policy.broker_instance_id.clone(),
        )?;
        let mut channel = Self {
            reader,
            writer,
            budget: Budget::default(),
            session,
            delegate,
            deadline: policy
                .deadline_unix_ms
                .min(crate::now_ms().saturating_add(5000)),
            egress: Vec::new(),
        };
        channel
            .write(&ToBroker::Start {
                protocol: Protocol::V1,
                policy: Box::new(policy.clone()),
            })
            .await?;
        match channel.read().await? {
            FromBroker::Ready {
                protocol: Protocol::V1,
                invocation_id,
                broker_instance_id,
            } => {
                channel.session.ready(&invocation_id, &broker_instance_id)?;
            }
            _ => return channel.fail("broker did not establish its exact binding"),
        }
        channel.deadline = policy.deadline_unix_ms;
        Ok(channel)
    }
    fn fail<T>(&mut self, message: &'static str) -> Result<T, &'static str> {
        self.delegate.close();
        self.session.close();
        self.budget.poisoned = true;
        Err(message)
    }
    fn remaining(&mut self) -> Result<std::time::Duration, &'static str> {
        if !self.delegate.live() {
            return self.fail("broker authority has ended");
        }
        let millis = self.deadline.saturating_sub(crate::now_ms());
        if millis == 0 {
            return self.fail("broker operation deadline elapsed");
        }
        Ok(std::time::Duration::from_millis(millis))
    }
    async fn read(&mut self) -> Result<FromBroker, &'static str> {
        let remaining = self.remaining()?;
        match tokio::time::timeout(remaining, read_frame(&mut self.reader, &mut self.budget)).await
        {
            Ok(Ok(value)) => Ok(value),
            Ok(Err(error)) => self.fail(error),
            Err(_) => self.fail("broker IPC deadline elapsed"),
        }
    }
    async fn write(&mut self, message: &ToBroker) -> Result<(), &'static str> {
        let remaining = self.remaining()?;
        match tokio::time::timeout(
            remaining,
            write_frame(&mut self.writer, &mut self.budget, message),
        )
        .await
        {
            Ok(Ok(())) => Ok(()),
            Ok(Err(error)) => self.fail(error),
            Err(_) => self.fail("broker IPC deadline elapsed"),
        }
    }
    pub async fn call(&mut self, request: BrokerRequestFrame) -> Result<CallReply, &'static str> {
        if request.kind != "BROKER_REQUEST"
            || request.request_id.is_empty()
            || request.request_id.len() > 128
            || ["route", "handle"].iter().any(|name| {
                request
                    .request
                    .get(*name)
                    .and_then(|v| v.as_str())
                    .is_some_and(|v| v.len() > 128)
            })
        {
            return self.fail("invalid worker broker request");
        }
        let sequence = self.session.call()?;
        let request_id = request.request_id.clone();
        self.write(&ToBroker::Call { sequence, request }).await?;
        loop {
            match self.read().await? {
                FromBroker::Authority { sequence, request } => {
                    if let Err(error) = self.session.authority(sequence) {
                        return self.fail(error);
                    }
                    let remaining = self.remaining()?;
                    let reply =
                        match tokio::time::timeout(remaining, self.delegate.answer(&request)).await
                        {
                            Ok(reply) => reply,
                            Err(_) => return self.fail("broker authority deadline elapsed"),
                        };
                    if let Err(error) = self.session.answered(sequence) {
                        return self.fail(error);
                    }
                    self.write(&ToBroker::AuthorityReply { sequence, reply })
                        .await?;
                }
                FromBroker::Response {
                    sequence: reply_sequence,
                    response,
                    egress,
                } => {
                    if response.kind != "BROKER_RESPONSE"
                        || response.request_id != request_id
                        || response.result.is_some() == response.error.is_some()
                        || response.error.as_ref().is_some_and(|e| e.retryable)
                        || egress.len() > 256
                        || egress.len() < self.egress.len()
                        || egress.get(..self.egress.len()) != Some(self.egress.as_slice())
                        || egress.iter().any(|e| e.route_id.len() > 128)
                    {
                        return self.fail("invalid or rewritten broker response");
                    }
                    if let Err(error) = self.session.response(reply_sequence) {
                        return self.fail(error);
                    }
                    self.egress = egress.clone();
                    return Ok(CallReply { response, egress });
                }
                _ => return self.fail("unexpected broker message"),
            }
        }
    }
    pub async fn finish(&mut self) {
        if !self.budget.poisoned {
            let _ = tokio::time::timeout(
                std::time::Duration::from_millis(100),
                write_frame(&mut self.writer, &mut self.budget, &ToBroker::Close {}),
            )
            .await;
        }
        self.stop();
    }
    pub fn stop(&mut self) {
        self.delegate.close();
        self.session.close();
        self.budget.poisoned = true;
    }
}
impl<R, W> Drop for ParentChannel<R, W> {
    fn drop(&mut self) {
        self.delegate.close();
        self.session.close();
        self.budget.poisoned = true;
    }
}

pub trait Connection: Send + Sync {
    fn abort(&self);
    fn call<'a>(
        &'a self,
        request: BrokerRequestFrame,
    ) -> futures_util::future::BoxFuture<'a, Result<CallReply, &'static str>>;
    fn finish(&self) -> futures_util::future::BoxFuture<'_, ()>;
    fn failed(&self) -> tokio::sync::watch::Receiver<bool>;
}
struct Streams<R, W> {
    channel: tokio::sync::Mutex<Option<ParentChannel<R, W>>>,
    failed: tokio::sync::watch::Sender<bool>,
}
impl<R: AsyncBufRead + Unpin + Send + 'static, W: AsyncWrite + Unpin + Send + 'static> Connection
    for Streams<R, W>
{
    fn abort(&self) {
        let _ = self.failed.send_replace(true);
    }
    fn call<'a>(
        &'a self,
        request: BrokerRequestFrame,
    ) -> futures_util::future::BoxFuture<'a, Result<CallReply, &'static str>> {
        Box::pin(async move {
            let mut owner = self.channel.lock().await;
            let result = match owner.as_mut() {
                Some(channel) => channel.call(request).await,
                None => Err("broker connection has ended"),
            };
            if result.is_err() {
                let _ = self.failed.send_replace(true);
            }
            result
        })
    }
    fn finish(&self) -> futures_util::future::BoxFuture<'_, ()> {
        Box::pin(async move {
            if let Some(mut channel) = self.channel.lock().await.take() {
                channel.finish().await;
            }
        })
    }
    fn failed(&self) -> tokio::sync::watch::Receiver<bool> {
        self.failed.subscribe()
    }
}
pub(crate) async fn connect<
    R: AsyncBufRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
>(
    invocation: &crate::broker::Invocation,
    reader: R,
    writer: W,
) -> Result<std::sync::Arc<dyn Connection>, &'static str> {
    let delegate = std::sync::Arc::new(ParentAuthority::from_invocation(invocation)?);
    let channel = ParentChannel::open(reader, writer, delegate).await?;
    let (failed, _) = tokio::sync::watch::channel(false);
    Ok(std::sync::Arc::new(Streams {
        channel: tokio::sync::Mutex::new(Some(channel)),
        failed,
    }))
}

struct ChildIo<R, W> {
    reader: R,
    writer: W,
    budget: Budget,
    session: Session,
}
struct Remote<R, W> {
    io: std::sync::Arc<tokio::sync::Mutex<ChildIo<R, W>>>,
    policy: Policy,
    closed: std::sync::Arc<std::sync::atomic::AtomicBool>,
}
impl<R: AsyncBufRead + Unpin + Send + 'static, W: AsyncWrite + Unpin + Send + 'static>
    Remote<R, W>
{
    async fn rpc(
        &self,
        request: AuthorityRequest,
    ) -> Result<AuthorityReply, crate::control::ControlError> {
        use crate::broker_authority::BrokerControl;
        use crate::control::ControlError;
        if !self.live() {
            return Err(ControlError::Refused(403));
        }
        let remaining = std::time::Duration::from_millis(
            self.policy
                .deadline_unix_ms
                .saturating_sub(crate::now_ms())
                .max(1),
        );
        let result = tokio::time::timeout(remaining, async {
            let mut io = self.io.lock().await;
            if !self.live() {
                return Err("broker authority ended");
            }
            let sequence = io.session.last_authority + 1;
            io.session.authority(sequence)?;
            let ChildIo {
                reader,
                writer,
                budget,
                ..
            } = &mut *io;
            write_frame(
                writer,
                budget,
                &FromBroker::Authority {
                    sequence,
                    request: Box::new(request),
                },
            )
            .await?;
            match read_frame::<_, ToBroker>(reader, budget).await? {
                ToBroker::AuthorityReply {
                    sequence: reply_sequence,
                    reply,
                } if reply_sequence == sequence => {
                    io.session.answered(sequence)?;
                    Ok(reply)
                }
                _ => Err("unexpected broker authority reply"),
            }
        })
        .await;
        match result {
            Ok(Ok(reply)) => Ok(reply),
            _ => {
                self.close();
                let mut io = self.io.lock().await;
                io.session.close();
                io.budget.poisoned = true;
                Err(ControlError::Unavailable(
                    "private broker authority channel ended".into(),
                ))
            }
        }
    }
    fn malformed<T>(&self) -> Result<T, crate::control::ControlError> {
        self.closed.store(true, std::sync::atomic::Ordering::SeqCst);
        Err(crate::control::ControlError::Unavailable(
            "invalid private authority reply".into(),
        ))
    }
}
impl<R: AsyncBufRead + Unpin + Send + 'static, W: AsyncWrite + Unpin + Send + 'static>
    crate::broker_authority::BrokerControl for Remote<R, W>
{
    fn live(&self) -> bool {
        !self.closed.load(std::sync::atomic::Ordering::SeqCst)
            && crate::now_ms() < self.policy.deadline_unix_ms
    }
    fn close(&self) {
        self.closed.store(true, std::sync::atomic::Ordering::SeqCst);
    }
    fn prepare_transport(
        &self,
        prepared: crate::request_binding::PreparedRequest,
        handler: &str,
    ) -> Result<crate::broker_authority::Transport, serde_json::Value> {
        prepared
            .into_buffered(handler)
            .map(|wire| crate::broker_authority::Transport::Buffered(Box::new(wire)))
            .map_err(|_| serde_json::json!({"refused":"broker transport cannot be buffered"}))
    }
    fn transport_buffered<'a>(
        &'a self,
        _: &'a BufferedRequest,
    ) -> futures_util::future::BoxFuture<'a, Result<BufferedResponse, serde_json::Value>> {
        Box::pin(async {
            Err(serde_json::json!({"refused":"broker has no direct network delegate"}))
        })
    }
    fn transport<'a>(
        &'a self,
        prepared: crate::broker_authority::Transport,
        handler: &'a str,
    ) -> futures_util::future::BoxFuture<'a, Result<crate::broker::Sent, serde_json::Value>> {
        Box::pin(async move {
            use base64::Engine;
            let crate::broker_authority::Transport::Buffered(wire) = prepared else {
                return Err(serde_json::json!({"refused":"invalid remote transport"}));
            };
            if wire.handler_id != handler {
                return Err(serde_json::json!({"refused":"transport handler changed"}));
            }
            match self
                .rpc(AuthorityRequest::Transport { request: *wire })
                .await
            {
                Ok(AuthorityReply::Transport { response }) => {
                    if check_response_headers(&response.headers).is_err()
                        || !(100..=599).contains(&response.status)
                    {
                        self.close();
                        return Err(
                            serde_json::json!({"failure":"invalid broker response headers"}),
                        );
                    }
                    if response.body_base64.len() > (MAX_BODY + 1).div_ceil(3) * 4 {
                        self.close();
                        return Err(serde_json::json!({"failure":"broker response too large"}));
                    }
                    let bytes = base64::engine::general_purpose::STANDARD
                        .decode(&response.body_base64)
                        .map_err(|_| {
                            self.close();
                            serde_json::json!({"failure":"invalid broker body"})
                        })?;
                    if bytes.len() > MAX_BODY + 1 {
                        self.close();
                        return Err(serde_json::json!({"failure":"broker response too large"}));
                    }
                    let upstream_id = ["x-request-id", "request-id"].iter().find_map(|name| {
                        response.headers.get(*name).and_then(|v| v.first()).cloned()
                    });
                    Ok(crate::broker::Sent {
                        bytes: bytes.len(),
                        status: response.status,
                        upstream_id,
                        reply: serde_json::json!({"status":response.status,"headers":response.headers,"bodyBase64":response.body_base64}),
                    })
                }
                Ok(AuthorityReply::Refused {}) => {
                    Err(serde_json::json!({"refused":"trusted transport refused"}))
                }
                Ok(AuthorityReply::Unavailable {}) | Err(_) => {
                    Err(serde_json::json!({"failure":"trusted transport unavailable"}))
                }
                _ => {
                    self.close();
                    Err(serde_json::json!({"failure":"invalid transport reply"}))
                }
            }
        })
    }
    fn secret<'a>(
        &'a self,
        invocation: &'a str,
        fence: &'a str,
        name: &'a str,
    ) -> futures_util::future::BoxFuture<'a, Result<String, crate::control::ControlError>> {
        Box::pin(async move {
            if invocation != self.policy.invocation_id || fence != self.policy.fencing_token {
                return Err(crate::control::ControlError::Refused(403));
            }
            match self
                .rpc(AuthorityRequest::Secret { name: name.into() })
                .await?
            {
                AuthorityReply::Secret { value } => Ok(value),
                AuthorityReply::Refused {} => Err(crate::control::ControlError::Refused(403)),
                AuthorityReply::Unavailable {} => Err(crate::control::ControlError::Unavailable(
                    "trusted secret authority unavailable".into(),
                )),
                _ => self.malformed(),
            }
        })
    }
    fn service_permit<'a>(
        &'a self,
        request: &'a olo_toolgate_contracts::ServicePermitIssue,
    ) -> futures_util::future::BoxFuture<'a, Result<ServicePermit, crate::control::ControlError>>
    {
        Box::pin(async move {
            if request.invocation_id != self.policy.invocation_id
                || request.fencing_token != self.policy.fencing_token
                || request.broker_instance_id.as_deref()
                    != Some(self.policy.broker_instance_id.as_str())
                || request.invocation_permit.as_ref() != Some(&self.policy.invocation_permit)
            {
                return Err(crate::control::ControlError::Refused(403));
            }
            let message = AuthorityRequest::Issue {
                audience: request.audience.clone(),
                manifest: request
                    .http_request_manifest
                    .clone()
                    .ok_or(crate::control::ControlError::Refused(403))?,
                digest: request
                    .http_request_digest
                    .clone()
                    .ok_or(crate::control::ControlError::Refused(403))?,
                handler_id: request
                    .handler_id
                    .clone()
                    .ok_or(crate::control::ControlError::Refused(403))?,
            };
            match self.rpc(message).await? {
                AuthorityReply::Permit { permit }
                    if !permit.jws.is_empty() && permit.jws.len() <= 131072 =>
                {
                    Ok(permit)
                }
                AuthorityReply::Refused {} => Err(crate::control::ControlError::Refused(403)),
                AuthorityReply::Unavailable {} => Err(crate::control::ControlError::Unavailable(
                    "trusted grant authority unavailable".into(),
                )),
                _ => self.malformed(),
            }
        })
    }
    fn service_sending<'a>(
        &'a self,
        request: &'a olo_toolgate_contracts::ServicePermitDispatch,
    ) -> futures_util::future::BoxFuture<
        'a,
        Result<olo_toolgate_contracts::ServicePermitDispatchAllowed, crate::control::ControlError>,
    > {
        Box::pin(async move {
            if request.broker_instance_id != self.policy.broker_instance_id {
                return Err(crate::control::ControlError::Refused(403));
            }
            match self
                .rpc(AuthorityRequest::Sending {
                    permit: request.permit.clone(),
                    handler_id: request.handler_id.clone(),
                })
                .await?
            {
                AuthorityReply::Sending {} => {
                    Ok(olo_toolgate_contracts::ServicePermitDispatchAllowed { authorized: true })
                }
                AuthorityReply::Refused {} => Err(crate::control::ControlError::Refused(403)),
                AuthorityReply::Unavailable {} => Err(crate::control::ControlError::Unavailable(
                    "trusted dispatch authority unavailable".into(),
                )),
                _ => self.malformed(),
            }
        })
    }
}

/// Run one trusted broker on owned private streams. No workload identity or general Control client is constructed.
pub async fn serve<
    R: AsyncBufRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
>(
    mut reader: R,
    mut writer: W,
) -> Result<(), &'static str> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let mut budget = Budget::default();
    let start = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        read_frame::<_, ToBroker>(&mut reader, &mut budget),
    )
    .await
    .map_err(|_| "broker startup deadline elapsed")??;
    let ToBroker::Start {
        protocol: Protocol::V1,
        policy,
    } = start
    else {
        return Err("broker start required");
    };
    policy.validate(crate::now_ms())?;
    let closed = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let mut session = Session::new(
        policy.invocation_id.clone(),
        policy.broker_instance_id.clone(),
    )?;
    session.ready(&policy.invocation_id, &policy.broker_instance_id)?;
    write_frame(
        &mut writer,
        &mut budget,
        &FromBroker::Ready {
            protocol: Protocol::V1,
            invocation_id: policy.invocation_id.clone(),
            broker_instance_id: policy.broker_instance_id.clone(),
        },
    )
    .await?;
    let io = std::sync::Arc::new(tokio::sync::Mutex::new(ChildIo {
        reader,
        writer,
        budget,
        session,
    }));
    let remote = std::sync::Arc::new(Remote {
        io: io.clone(),
        policy: (*policy).clone(),
        closed: closed.clone(),
    });
    let invocation =
        crate::broker::Invocation::from_broker_policy(&policy, remote, closed.clone())?;
    loop {
        let remaining = policy.deadline_unix_ms.saturating_sub(crate::now_ms());
        if remaining == 0 || closed.load(std::sync::atomic::Ordering::SeqCst) {
            return Err("broker invocation ended");
        }
        let message = tokio::time::timeout(std::time::Duration::from_millis(remaining), async {
            let mut io = io.lock().await;
            let ChildIo { reader, budget, .. } = &mut *io;
            read_frame::<_, ToBroker>(reader, budget).await
        })
        .await
        .map_err(|_| "broker invocation deadline elapsed")??;
        let (sequence, request) = match message {
            ToBroker::Close {} => {
                closed.store(true, std::sync::atomic::Ordering::SeqCst);
                return Ok(());
            }
            ToBroker::Call { sequence, request } => (sequence, request),
            _ => return Err("unexpected broker invocation message"),
        };
        {
            let mut io = io.lock().await;
            if io.session.call()? != sequence {
                return Err("invalid broker call sequence");
            }
        }
        let response = crate::broker::answer(
            &invocation,
            &serde_json::to_value(request).map_err(|_| "invalid broker request")?,
        )
        .await;
        if closed.load(std::sync::atomic::Ordering::SeqCst) {
            return Err("broker authority channel ended");
        }
        let response: BrokerResponseFrame =
            serde_json::from_value(response).map_err(|_| "invalid broker response")?;
        let egress = invocation
            .egress
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        let mut io = io.lock().await;
        io.session.response(sequence)?;
        let ChildIo { writer, budget, .. } = &mut *io;
        write_frame(
            writer,
            budget,
            &FromBroker::Response {
                sequence,
                response,
                egress,
            },
        )
        .await?;
    }
}

/// Budget state is terminal after an encoding, I/O, framing or parsing error.
#[derive(Default)]
pub struct Budget {
    reads: usize,
    writes: usize,
    read_bytes: usize,
    write_bytes: usize,
    poisoned: bool,
}
impl Budget {
    fn reserve(&mut self, bytes: usize, write: bool) -> Result<(), &'static str> {
        let (frames, total) = if write {
            (&mut self.writes, &mut self.write_bytes)
        } else {
            (&mut self.reads, &mut self.read_bytes)
        };
        if self.poisoned
            || *frames >= MAX_FRAMES
            || bytes > MAX_FRAME + 1
            || bytes > MAX_BYTES.saturating_sub(*total)
        {
            self.poisoned = true;
            return Err("broker IPC budget exhausted");
        }
        *frames += 1;
        *total += bytes;
        Ok(())
    }
    fn fail<T>(&mut self, message: &'static str) -> Result<T, &'static str> {
        self.poisoned = true;
        Err(message)
    }
}
pub async fn read_frame<R: AsyncBufRead + Unpin, T: DeserializeOwned>(
    reader: &mut R,
    budget: &mut Budget,
) -> Result<T, &'static str> {
    if budget.poisoned {
        return Err("broker IPC is closed");
    }
    let mut bytes = Vec::new();
    loop {
        let chunk = match reader.fill_buf().await {
            Ok(v) => v,
            Err(_) => return budget.fail("broker IPC read failed"),
        };
        if chunk.is_empty() {
            return budget.fail("broker IPC truncated");
        }
        let end = chunk.iter().position(|b| *b == b'\n');
        let length = end.map_or(chunk.len(), |n| n + 1);
        if length > MAX_FRAME + 1 - bytes.len() {
            return budget.fail("broker IPC frame too large");
        }
        bytes.extend_from_slice(&chunk[..length]);
        reader.consume(length);
        if end.is_some() {
            break;
        }
        if bytes.len() > MAX_FRAME {
            return budget.fail("broker IPC frame too large");
        }
    }
    budget.reserve(bytes.len(), false)?;
    if bytes.last() != Some(&b'\n') {
        return budget.fail("broker IPC framing failed");
    }
    bytes.pop();
    match serde_json::from_slice(&bytes) {
        Ok(v) => Ok(v),
        Err(_) => budget.fail("invalid broker IPC message"),
    }
}
struct Limited(Vec<u8>);
impl std::io::Write for Limited {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        if bytes.len() > MAX_FRAME.saturating_sub(self.0.len()) {
            return Err(std::io::Error::other("IPC frame too large"));
        }
        self.0.extend_from_slice(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}
pub async fn write_frame<W: AsyncWrite + Unpin, T: Serialize>(
    writer: &mut W,
    budget: &mut Budget,
    value: &T,
) -> Result<(), &'static str> {
    if budget.poisoned {
        return Err("broker IPC is closed");
    }
    let mut encoded = Limited(Vec::new());
    if serde_json::to_writer(&mut encoded, value).is_err() {
        return budget.fail("broker IPC encoding failed");
    }
    encoded.0.push(b'\n');
    budget.reserve(encoded.0.len(), true)?;
    if writer.write_all(&encoded.0).await.is_err() || writer.flush().await.is_err() {
        return budget.fail("broker IPC write failed");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn session() -> Session {
        let mut s = Session::new("operation".into(), "broker".into()).unwrap();
        s.ready("operation", "broker").unwrap();
        s
    }
    #[test]
    fn separated_response_header_limits_reject_excessive_or_malformed_fields() {
        let allowed = BTreeMap::from([("content-type".into(), vec!["application/json".into()])]);
        assert!(check_response_headers(&allowed).is_ok());
        assert!(check_response_headers(&BTreeMap::from([(
            "Content-Type".into(),
            vec!["application/json".into()]
        )]))
        .is_err());
        assert!(check_response_headers(&BTreeMap::from([(
            "x-test".into(),
            vec!["x".repeat(8193)]
        )]))
        .is_err());
        let oversized = (0..10)
            .map(|n| (format!("x-{n}"), vec!["x".repeat(8192)]))
            .collect();
        assert!(check_response_headers(&oversized).is_err());
        assert!(check_response_headers(&BTreeMap::from([(
            "x-test".into(),
            vec!["bad\nheader".into()]
        )]))
        .is_err());
    }
    #[test]
    fn readiness_is_bound_to_one_operation_and_one_broker() {
        for (invocation, broker) in [("other", "broker"), ("operation", "other")] {
            let mut s = Session::new("operation".into(), "broker".into()).unwrap();
            assert!(s.ready(invocation, broker).is_err());
            assert!(s.ready("operation", "broker").is_err());
        }
        let mut s = session();
        assert!(s.ready("operation", "broker").is_err());
        assert!(s.call().is_err());
        let mut s = Session::new("operation".into(), "broker".into()).unwrap();
        assert!(s.call().is_err());
    }
    #[test]
    fn only_ordered_single_outstanding_calls_and_authority_replies_are_accepted() {
        let mut s = session();
        let call = s.call().unwrap();
        s.authority(1).unwrap();
        s.answered(1).unwrap();
        s.response(call).unwrap();
        assert_eq!(s.call().unwrap(), 2);
        let mut s = session();
        s.call().unwrap();
        assert!(s.call().is_err());
        let mut s = session();
        s.call().unwrap();
        assert!(s.authority(2).is_err());
        let mut s = session();
        s.call().unwrap();
        s.authority(1).unwrap();
        assert!(s.response(1).is_err());
        let mut s = session();
        s.call().unwrap();
        s.authority(1).unwrap();
        assert!(s.answered(2).is_err());
        let mut s = session();
        let n = s.call().unwrap();
        s.response(n).unwrap();
        assert!(s.response(n).is_err());
        let mut s = session();
        s.close();
        assert!(s.call().is_err());
    }
    #[test]
    fn call_and_privileged_operation_counts_are_bounded() {
        let mut s = session();
        for _ in 0..MAX_CALLS {
            let n = s.call().unwrap();
            s.response(n).unwrap();
        }
        assert!(s.call().is_err());
        let mut s = session();
        s.call().unwrap();
        for n in 1..=MAX_AUTHORITY_CALLS {
            s.authority(n).unwrap();
            s.answered(n).unwrap();
        }
        assert!(s.authority(MAX_AUTHORITY_CALLS + 1).is_err());
    }
    #[test]
    fn worker_frames_cannot_become_privileged_messages_or_select_a_foreign_scope() {
        for request in [
            json!({"operation":"SECRET","name":"declared","invocationId":"foreign"}),
            json!({"operation":"SECRET","name":"declared","fencingToken":"foreign"}),
            json!({"operation":"SECRET","name":"declared","controlUrl":"https://control.example.test"}),
            json!({"operation":"GET_IDENTITY"}),
            json!({"operation":"HTTP","url":"https://control.example.test"}),
        ] {
            assert!(serde_json::from_value::<AuthorityRequest>(request).is_err());
        }
        assert!(serde_json::from_value::<ToBroker>(json!({
            "kind":"CALL","sequence":1,"request":{"kind":"AUTHORITY","operation":"SECRET","requestId":"worker","request":{"name":"credential"}}
        })).is_err());
        assert!(serde_json::from_value::<ToBroker>(
            json!({"kind":"CLOSE","url":"https://elsewhere"})
        )
        .is_err());
        for result in ["SENDING", "REFUSED", "UNAVAILABLE"] {
            assert!(serde_json::from_value::<AuthorityReply>(
                json!({"result":result,"url":"https://elsewhere"})
            )
            .is_err());
        }
        assert!(
            serde_json::from_value::<Protocol>(json!("toolgate-invocation-broker-v0")).is_err()
        );
    }
    #[tokio::test]
    async fn framed_io_handles_split_lines_and_never_merges_two_messages() {
        let (mut writer, reader) = tokio::io::duplex(8);
        let task = tokio::spawn(async move {
            let mut b = Budget::default();
            write_frame(&mut writer, &mut b, &ToBroker::Close {})
                .await
                .unwrap();
            write_frame(&mut writer, &mut b, &ToBroker::Close {})
                .await
                .unwrap();
        });
        let mut reader = tokio::io::BufReader::with_capacity(3, reader);
        let mut b = Budget::default();
        assert!(matches!(
            read_frame::<_, ToBroker>(&mut reader, &mut b)
                .await
                .unwrap(),
            ToBroker::Close {}
        ));
        assert!(matches!(
            read_frame::<_, ToBroker>(&mut reader, &mut b)
                .await
                .unwrap(),
            ToBroker::Close {}
        ));
        task.await.unwrap();
        assert!(read_frame::<_, ToBroker>(&mut reader, &mut b)
            .await
            .is_err());
    }
    #[tokio::test]
    async fn malformed_truncated_and_overlong_messages_poison_the_channel() {
        for bytes in [
            b"{bad}\n".to_vec(),
            b"{\"kind\":\"CLOSE\"}".to_vec(),
            vec![b'x'; MAX_FRAME + 2],
        ] {
            let mut reader = tokio::io::BufReader::new(bytes.as_slice());
            let mut budget = Budget::default();
            assert!(read_frame::<_, ToBroker>(&mut reader, &mut budget)
                .await
                .is_err());
            let good = b"{\"kind\":\"CLOSE\"}\n";
            let mut reader = tokio::io::BufReader::new(good.as_slice());
            assert!(read_frame::<_, ToBroker>(&mut reader, &mut budget)
                .await
                .is_err());
        }
    }
    #[tokio::test]
    async fn output_and_total_budgets_fail_before_contacting_the_writer() {
        let mut writer = Vec::new();
        let mut b = Budget::default();
        assert!(write_frame(&mut writer, &mut b, &"x".repeat(MAX_FRAME))
            .await
            .is_err());
        assert!(writer.is_empty());
        let mut b = Budget {
            write_bytes: MAX_BYTES,
            ..Budget::default()
        };
        assert!(write_frame(&mut writer, &mut b, &ToBroker::Close {})
            .await
            .is_err());
        assert!(writer.is_empty());
        let mut b = Budget {
            writes: MAX_FRAMES,
            ..Budget::default()
        };
        assert!(write_frame(&mut writer, &mut b, &ToBroker::Close {})
            .await
            .is_err());
        assert!(writer.is_empty());
    }
    fn policy() -> Policy {
        let corpus: serde_json::Value = serde_json::from_str(include_str!(
            "../../../tests/fixtures/contracts/v2/valid.json"
        ))
        .unwrap();
        let mut profile: olo_toolgate_contracts::v2::ServiceProfile =
            serde_json::from_value(corpus["ServiceProfile"].clone()).unwrap();
        for route in &mut profile.routes {
            route.auth = None;
            route.max_request_bytes = route.max_request_bytes.min(MAX_BODY as u64);
            route.max_response_bytes = route.max_response_bytes.min(MAX_BODY as u64);
        }
        Policy {
            invocation_id: "operation".into(),
            fencing_token: "fence".into(),
            deadline_unix_ms: 1000,
            profile_id: "profile".into(),
            profile_digest: format!("sha256:{}", "a".repeat(64)),
            broker_instance_id: "broker".into(),
            invocation_permit: SignedInvocationPermit {
                jws: "test-admission".into(),
            },
            routes: profile.routes,
            destinations: profile.destinations,
            private_ranges: profile.private_ranges,
            auths: Vec::new(),
            secrets: Vec::new(),
            secret_handles: BTreeMap::new(),
            development_loopback_http: false,
        }
    }
    #[test]
    fn startup_policy_is_closed_and_cannot_carry_supervisor_credentials_or_paths() {
        let policy = policy();
        policy.validate(999).unwrap();
        assert!(policy.validate(1000).is_err());
        let original = serde_json::to_value(&policy).unwrap();
        for field in [
            "controlUrl",
            "identity",
            "certificatePem",
            "privateKey",
            "storeDir",
            "podmanPath",
        ] {
            let mut changed = original.clone();
            changed[field] = json!("forbidden");
            assert!(
                serde_json::from_value::<Policy>(changed).is_err(),
                "{field}"
            );
        }
        let mut changed = policy.clone();
        changed.routes.push(changed.routes[0].clone());
        assert!(changed.validate(1).is_err());
        let mut changed = policy.clone();
        changed.routes[0].destination = "foreign".into();
        assert!(changed.validate(1).is_err());
        let mut changed = policy.clone();
        changed
            .secret_handles
            .insert("handle".into(), "undeclared".into());
        assert!(changed.validate(1).is_err());
        let mut changed = policy.clone();
        changed.routes = vec![changed.routes[0].clone(); 257];
        assert!(changed.validate(1).is_err());
    }
    #[tokio::test]
    async fn failed_writes_are_terminal_and_duplicate_fields_cannot_select_another_message() {
        let (mut writer, reader) = tokio::io::duplex(8);
        drop(reader);
        let mut budget = Budget::default();
        assert!(write_frame(&mut writer, &mut budget, &ToBroker::Close {})
            .await
            .is_err());
        let mut alternate = Vec::new();
        assert!(
            write_frame(&mut alternate, &mut budget, &ToBroker::Close {})
                .await
                .is_err()
        );
        assert!(alternate.is_empty());
        let bytes = b"{\"kind\":\"CLOSE\",\"kind\":\"CALL\"}\n";
        let mut reader = tokio::io::BufReader::new(bytes.as_slice());
        assert!(
            read_frame::<_, ToBroker>(&mut reader, &mut Budget::default())
                .await
                .is_err()
        );
    }
    fn wire(service: bool) -> (reqwest::Client, BufferedRequest) {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let client = reqwest::Client::builder()
            .http1_only()
            .redirect(reqwest::redirect::Policy::none())
            .no_proxy()
            .build()
            .unwrap();
        let header = if service {
            "toolgate-service-permit"
        } else {
            "authorization"
        };
        let value = if service {
            "fixture-service-permit"
        } else {
            "test-credential"
        };
        let request = client
            .post("https://example.test/path?z=2&a=1")
            .header(header, value)
            .header("x-recipient", "alice")
            .header("content-type", "Application/JSON; Charset=UTF-8")
            .body("{\"x\":1}")
            .build()
            .unwrap();
        let metadata = crate::request_binding::Metadata {
            profile_id: "profile".into(),
            profile_digest: format!("sha256:{}", "a".repeat(64)),
            route_id: "route".into(),
            auth_id: Some(
                if service {
                    "toolgate-service-permit:olo-memory"
                } else {
                    "auth"
                }
                .into(),
            ),
            credential_headers: vec![header.into()],
        };
        let prepared = crate::request_binding::PreparedRequest::new(
            &client,
            request.try_clone().unwrap(),
            metadata,
        )
        .unwrap();
        let manifest: PreparedHttpRequestManifest =
            serde_json::from_value(prepared.manifest().clone()).unwrap();
        let encoded = freeze_request(&client, request, &manifest, "handler").unwrap();
        assert_eq!(encoded.digest, prepared.digest());
        (client, encoded)
    }
    #[test]
    fn buffered_transport_round_trip_preserves_the_exact_fingerprint_without_credential_values() {
        let (client, wire) = wire(false);
        let rebuilt =
            rebuild_request(&client, &wire, None, std::time::Duration::from_secs(1)).unwrap();
        assert_eq!(rebuilt.digest(), wire.digest);
        assert_eq!(
            rebuilt.manifest(),
            &serde_json::to_value(&wire.manifest).unwrap()
        );
        assert!(!rebuilt.manifest().to_string().contains("test-credential"));
        for field in [
            "body",
            "method",
            "path",
            "query",
            "host",
            "recipient",
            "digest",
            "hidden-host",
            "framing",
            "duplicate-credential",
        ] {
            let mut changed = wire.clone();
            match field {
                "body" => changed.body_base64 = Some("e30=".into()),
                "method" => changed.manifest.method = "GET".into(),
                "path" => changed.manifest.path = "/other".into(),
                "query" => changed.manifest.query = "z=3".into(),
                "host" => changed.manifest.host = "other.example.test".into(),
                "recipient" => {
                    changed
                        .headers
                        .insert("x-recipient".into(), vec!["bob".into()]);
                }
                "digest" => changed.digest = format!("sha256:{}", "b".repeat(64)),
                "hidden-host" => {
                    changed
                        .headers
                        .insert("host".into(), vec!["other.example.test".into()]);
                }
                "framing" => {
                    changed
                        .headers
                        .insert("content-length".into(), vec!["999".into()]);
                }
                "duplicate-credential" => {
                    changed
                        .headers
                        .insert("authorization".into(), vec!["one".into(), "two".into()]);
                }
                _ => unreachable!(),
            }
            assert!(
                rebuild_request(&client, &changed, None, std::time::Duration::from_secs(1))
                    .is_err(),
                "{field}"
            );
        }
    }
    #[test]
    fn service_credentials_must_match_the_supervisors_issued_grant_even_when_excluded_from_the_digest(
    ) {
        let (client, wire) = wire(true);
        let permit = ServicePermit {
            jws: "fixture-service-permit".into(),
        };
        let timeout = std::time::Duration::from_secs(1);
        assert!(rebuild_request(&client, &wire, Some(&permit), timeout).is_ok());
        assert!(rebuild_request(&client, &wire, None, timeout).is_err());
        assert!(rebuild_request(
            &client,
            &wire,
            Some(&ServicePermit {
                jws: "borrowed".into()
            }),
            timeout
        )
        .is_err());
        let mut changed = wire.clone();
        changed.headers.remove("toolgate-service-permit");
        assert!(rebuild_request(&client, &changed, Some(&permit), timeout).is_err());
        let mut changed = wire.clone();
        changed
            .headers
            .insert("toolgate-service-permit".into(), vec!["borrowed".into()]);
        assert!(rebuild_request(&client, &changed, Some(&permit), timeout).is_err());
    }
    #[test]
    fn separated_tier_refuses_oversized_declared_routes_instead_of_silently_truncating_them() {
        let mut policy = policy();
        policy.validate(1).unwrap();
        policy.routes[0].max_response_bytes = MAX_BODY as u64 + 1;
        assert!(policy.validate(1).is_err());
        policy.routes[0].max_response_bytes = 0;
        policy.routes[0].max_request_bytes = MAX_BODY as u64 + 1;
        assert!(policy.validate(1).is_err());
        let (client, mut wire) = wire(false);
        wire.body_base64 = Some("x".repeat(MAX_BODY.div_ceil(3) * 4 + 1));
        assert!(rebuild_request(&client, &wire, None, std::time::Duration::from_secs(1)).is_err());
    }
    #[tokio::test]
    async fn reconstructed_transport_sends_the_bound_body_and_credentials_once() {
        use std::sync::{
            atomic::{AtomicUsize, Ordering},
            Arc,
        };
        let calls = Arc::new(AtomicUsize::new(0));
        let seen = calls.clone();
        let app = axum::Router::new().fallback(
            move |headers: axum::http::HeaderMap, body: axum::body::Bytes| {
                let seen = seen.clone();
                async move {
                    assert_eq!(headers["authorization"], "test-credential");
                    assert_eq!(headers["x-recipient"], "alice");
                    assert_eq!(body.as_ref(), b"{\"x\":1}");
                    seen.fetch_add(1, Ordering::SeqCst);
                    axum::Json(json!({"ok":true}))
                }
            },
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/path", listener.local_addr().unwrap());
        let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let (client, mut wire) = wire(false);
        wire.manifest.scheme = "http".into();
        wire.manifest.host = "127.0.0.1".into();
        wire.manifest.port = reqwest::Url::parse(&url).unwrap().port().unwrap() as u64;
        wire.manifest.query = String::new();
        wire.digest = crate::digest::jcs(&serde_json::to_value(&wire.manifest).unwrap()).unwrap();
        let transport =
            rebuild_request(&client, &wire, None, std::time::Duration::from_secs(1)).unwrap();
        let response = transport.send().await.unwrap();
        assert_eq!(response.status(), 200);
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        server.abort();
    }
    struct FakeDelegate {
        policy: Policy,
        calls: std::sync::Arc<std::sync::atomic::AtomicUsize>,
        hang: bool,
        closed: std::sync::atomic::AtomicBool,
    }
    impl Delegate for FakeDelegate {
        fn live(&self) -> bool {
            !self.closed.load(std::sync::atomic::Ordering::SeqCst)
        }
        fn close(&self) {
            self.closed.store(true, std::sync::atomic::Ordering::SeqCst);
        }
        fn policy(&self) -> &Policy {
            &self.policy
        }
        fn answer<'a>(
            &'a self,
            _: &'a AuthorityRequest,
        ) -> futures_util::future::BoxFuture<'a, AuthorityReply> {
            Box::pin(async move {
                self.calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                if self.hang {
                    std::future::pending::<()>().await;
                }
                AuthorityReply::Secret {
                    value: "scope-value".into(),
                }
            })
        }
    }
    fn delegate(
        hang: bool,
    ) -> (
        std::sync::Arc<dyn Delegate>,
        std::sync::Arc<std::sync::atomic::AtomicUsize>,
    ) {
        let mut policy = policy();
        policy.deadline_unix_ms = crate::now_ms() + 60_000;
        let calls = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        (
            std::sync::Arc::new(FakeDelegate {
                policy,
                calls: calls.clone(),
                hang,
                closed: std::sync::atomic::AtomicBool::new(false),
            }),
            calls,
        )
    }
    fn worker() -> BrokerRequestFrame {
        serde_json::from_value(json!({"kind":"BROKER_REQUEST","requestId":"worker","operation":"SECRET_REDEEM","request":{"handle":"handle"}})).unwrap()
    }
    async fn fake_ready<R: AsyncBufRead + Unpin, W: AsyncWrite + Unpin>(
        reader: &mut R,
        writer: &mut W,
        budget: &mut Budget,
    ) {
        let ToBroker::Start {
            protocol: Protocol::V1,
            policy,
        } = read_frame(reader, budget).await.unwrap()
        else {
            panic!("start required")
        };
        write_frame(
            writer,
            budget,
            &FromBroker::Ready {
                protocol: Protocol::V1,
                invocation_id: policy.invocation_id,
                broker_instance_id: policy.broker_instance_id,
            },
        )
        .await
        .unwrap();
    }
    fn response(sequence: u64, egress: Vec<crate::broker::Egress>) -> FromBroker {
        FromBroker::Response {sequence,response:serde_json::from_value(json!({"kind":"BROKER_RESPONSE","requestId":"worker","result":{"value":"scope-value"}})).unwrap(),egress}
    }
    #[tokio::test]
    async fn parent_channel_owns_policy_and_correlates_both_worker_and_authority_replies() {
        let (parent, child) = tokio::io::duplex(8192);
        let (reader, writer) = tokio::io::split(child);
        let child = tokio::spawn(async move {
            let mut reader = tokio::io::BufReader::new(reader);
            let mut writer = writer;
            let mut budget = Budget::default();
            fake_ready(&mut reader, &mut writer, &mut budget).await;
            let ToBroker::Call { sequence, request } =
                read_frame(&mut reader, &mut budget).await.unwrap()
            else {
                panic!("call required")
            };
            assert_eq!(request.request_id, "worker");
            write_frame(
                &mut writer,
                &mut budget,
                &FromBroker::Authority {
                    sequence: 1,
                    request: Box::new(AuthorityRequest::Secret {
                        name: "declared".into(),
                    }),
                },
            )
            .await
            .unwrap();
            assert!(matches!(
                read_frame::<_, ToBroker>(&mut reader, &mut budget)
                    .await
                    .unwrap(),
                ToBroker::AuthorityReply {
                    sequence: 1,
                    reply: AuthorityReply::Secret { .. }
                }
            ));
            write_frame(&mut writer, &mut budget, &response(sequence, Vec::new()))
                .await
                .unwrap();
        });
        let (reader, writer) = tokio::io::split(parent);
        let (delegate, calls) = delegate(false);
        let mut channel = ParentChannel::open(tokio::io::BufReader::new(reader), writer, delegate)
            .await
            .unwrap();
        assert_eq!(
            channel.call(worker()).await.unwrap().response.request_id,
            "worker"
        );
        assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), 1);
        channel.stop();
        assert!(channel.call(worker()).await.is_err());
        child.await.unwrap();
    }
    #[tokio::test]
    async fn authority_before_readiness_or_with_a_foreign_binding_never_reaches_the_delegate() {
        for malicious in [
            FromBroker::Ready {
                protocol: Protocol::V1,
                invocation_id: "foreign".into(),
                broker_instance_id: "broker".into(),
            },
            FromBroker::Authority {
                sequence: 1,
                request: Box::new(AuthorityRequest::Secret {
                    name: "declared".into(),
                }),
            },
        ] {
            let (parent, child) = tokio::io::duplex(8192);
            let (reader, writer) = tokio::io::split(child);
            let child = tokio::spawn(async move {
                let mut reader = tokio::io::BufReader::new(reader);
                let mut writer = writer;
                let mut budget = Budget::default();
                let _: ToBroker = read_frame(&mut reader, &mut budget).await.unwrap();
                write_frame(&mut writer, &mut budget, &malicious)
                    .await
                    .unwrap();
            });
            let (reader, writer) = tokio::io::split(parent);
            let (delegate, calls) = delegate(false);
            assert!(
                ParentChannel::open(tokio::io::BufReader::new(reader), writer, delegate)
                    .await
                    .is_err()
            );
            assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), 0);
            child.await.unwrap();
        }
    }
    #[tokio::test]
    async fn replayed_authority_sequence_cannot_repeat_a_privileged_action() {
        let (parent, child) = tokio::io::duplex(8192);
        let (reader, writer) = tokio::io::split(child);
        let child = tokio::spawn(async move {
            let mut reader = tokio::io::BufReader::new(reader);
            let mut writer = writer;
            let mut budget = Budget::default();
            fake_ready(&mut reader, &mut writer, &mut budget).await;
            let _: ToBroker = read_frame(&mut reader, &mut budget).await.unwrap();
            let message = FromBroker::Authority {
                sequence: 1,
                request: Box::new(AuthorityRequest::Secret {
                    name: "declared".into(),
                }),
            };
            write_frame(&mut writer, &mut budget, &message)
                .await
                .unwrap();
            let _: ToBroker = read_frame(&mut reader, &mut budget).await.unwrap();
            write_frame(&mut writer, &mut budget, &message)
                .await
                .unwrap();
        });
        let (reader, writer) = tokio::io::split(parent);
        let (delegate, calls) = delegate(false);
        let mut channel = ParentChannel::open(tokio::io::BufReader::new(reader), writer, delegate)
            .await
            .unwrap();
        assert!(channel.call(worker()).await.is_err());
        assert!(channel.call(worker()).await.is_err());
        assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), 1);
        child.await.unwrap();
    }
    #[tokio::test]
    async fn a_later_response_cannot_rewrite_retained_broker_trace_records() {
        let (parent, child) = tokio::io::duplex(8192);
        let (reader, writer) = tokio::io::split(child);
        let child = tokio::spawn(async move {
            let mut reader = tokio::io::BufReader::new(reader);
            let mut writer = writer;
            let mut budget = Budget::default();
            fake_ready(&mut reader, &mut writer, &mut budget).await;
            let mut egress = vec![crate::broker::Egress {
                route_id: "route".into(),
                started_unix_ms: 1,
                duration_ms: 1,
                status: Some(200),
                refused: false,
            }];
            for sequence in 1..=2 {
                let _: ToBroker = read_frame(&mut reader, &mut budget).await.unwrap();
                if sequence == 2 {
                    egress[0].status = Some(500);
                }
                write_frame(
                    &mut writer,
                    &mut budget,
                    &response(sequence, egress.clone()),
                )
                .await
                .unwrap();
            }
        });
        let (reader, writer) = tokio::io::split(parent);
        let (delegate, _) = delegate(false);
        let mut channel = ParentChannel::open(tokio::io::BufReader::new(reader), writer, delegate)
            .await
            .unwrap();
        assert_eq!(
            channel.call(worker()).await.unwrap().egress[0].status,
            Some(200)
        );
        assert!(channel.call(worker()).await.is_err());
        child.await.unwrap();
    }
    #[tokio::test(start_paused = true)]
    async fn a_silent_broker_or_stalled_authority_is_terminal_without_a_reconnect() {
        let (parent, child) = tokio::io::duplex(8192);
        let (reader, writer) = tokio::io::split(child);
        let child = tokio::spawn(async move {
            let mut reader = tokio::io::BufReader::new(reader);
            let _writer = writer;
            let _: ToBroker = read_frame(&mut reader, &mut Budget::default())
                .await
                .unwrap();
            std::future::pending::<()>().await;
        });
        let (reader, writer) = tokio::io::split(parent);
        let (authority, calls) = delegate(false);
        assert!(
            ParentChannel::open(tokio::io::BufReader::new(reader), writer, authority)
                .await
                .is_err()
        );
        assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), 0);
        child.abort();
        let (parent, child) = tokio::io::duplex(8192);
        let (reader, writer) = tokio::io::split(child);
        let child = tokio::spawn(async move {
            let mut reader = tokio::io::BufReader::new(reader);
            let mut writer = writer;
            let mut budget = Budget::default();
            fake_ready(&mut reader, &mut writer, &mut budget).await;
            let _: ToBroker = read_frame(&mut reader, &mut budget).await.unwrap();
            write_frame(
                &mut writer,
                &mut budget,
                &FromBroker::Authority {
                    sequence: 1,
                    request: Box::new(AuthorityRequest::Secret {
                        name: "declared".into(),
                    }),
                },
            )
            .await
            .unwrap();
            std::future::pending::<()>().await;
        });
        let (reader, writer) = tokio::io::split(parent);
        let (authority, calls) = delegate(true);
        let mut channel = ParentChannel::open(tokio::io::BufReader::new(reader), writer, authority)
            .await
            .unwrap();
        assert!(channel.call(worker()).await.is_err());
        assert!(channel.call(worker()).await.is_err());
        assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), 1);
        child.abort();
    }
}
