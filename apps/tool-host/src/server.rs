// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Gateway dispatch (platform-requirements.md §11.3). `POST /v1/dispatch` takes a `Dispatch` and answers NDJSON:
//! one `AdmissionResponse` line, definitive, and after `ADMITTED` one `ResultMessage` line. The
//! tool starts only after the admission line is written. `GET /v1/health` reports readiness.
//!
//! Pull mode ([`pull`]) takes the same dispatches from Control over the host's own connection,
//! for a host the Gateway cannot reach: a laptop behind NAT runs tools exactly as a server does.

use crate::broker::Invocation;
use crate::config::{Config, CEILING};
use crate::control::{Control, ControlError};
use crate::permit::{PermitError, Verifier};
use crate::runner::{self, Launch, Ran};
use crate::store::{Prepared, Store, StoreError};
use crate::{digest, now_ms, random_hex};
use axum::body::{Body, Bytes};
use axum::extract::State;
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::Router;
use olo_toolgate_contracts::v2::{
    AdmissionKind, AdmissionResponse, Dispatch, InvocationOutcome, InvocationPermitClaims,
    InvocationResultReport, Permission, PermitConsumption, RefusalReason, ResultFrame,
    ResultMessage, ResultType, SecretHandle, ToolErrorBody, ToolInput,
};
use serde_json::Value;
use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tokio::sync::{mpsc, OwnedSemaphorePermit, Semaphore};

pub struct Host {
    pub(crate) config: Config,
    verifier: Verifier,
    store: Store,
    control: Arc<Control>,
    slots: Arc<Semaphore>,
    outbox: crate::outbox::Outbox,
    intents: crate::outbox::Outbox,
    sandbox: Option<Arc<crate::sandbox::Sandbox>>,
    revocations: Option<crate::revocation::Supervisor>,
}

impl Host {
    pub async fn supervise_revocations(self: Arc<Self>) {
        if let Some(supervisor) = &self.revocations {
            supervisor.run(&self.control).await;
        }
    }
    pub async fn qualify_execution(&self) -> Result<(), String> {
        if let Some(pool) = &self.sandbox {
            pool.qualify().await?;
        }
        Ok(())
    }
    fn execution_tier(&self) -> olo_toolgate_contracts::v2::RuntimeTier {
        if self.sandbox.is_some() {
            olo_toolgate_contracts::v2::RuntimeTier::OciSandbox
        } else {
            olo_toolgate_contracts::v2::RuntimeTier::HostNative
        }
    }
    /// The Control client dispatch uses; enrollment swaps its certificate in place.
    pub fn control(&self) -> Arc<Control> {
        self.control.clone()
    }

    pub fn verifier(&self) -> &Verifier {
        &self.verifier
    }

    /// Plain http to a loopback upstream, for local development and tests only.
    pub fn allow_loopback(&self) -> bool {
        self.config.development_loopback_http
    }

    pub fn new(config: Config) -> Result<Arc<Host>, String> {
        config.validate()?;
        let mut keys = HashMap::new();
        for key in &config.permit_keys {
            let pem = std::fs::read(&key.public_key_path)
                .map_err(|e| format!("{}: {e}", key.public_key_path.display()))?;
            let der = crate::permit::rsa_public_key(&pem)
                .map_err(|e| format!("{}: {e}", key.public_key_path.display()))?;
            keys.insert(key.kid.clone(), der);
        }
        std::fs::create_dir_all(config.work_dir.join("i"))
            .map_err(|e| format!("{}: {e}", config.work_dir.display()))?;
        let control = Control::new(&config.control)?;
        control.validate_host(&config.host_id)?;
        let outbox = crate::outbox::Outbox::open(config.work_dir.join("result-outbox"))?;
        let intents = crate::outbox::Outbox::open(config.work_dir.join("admission-spool"))?;
        intents.recover_into(&outbox)?;
        let sandbox = config
            .sandbox
            .clone()
            .map(|sandbox| {
                crate::sandbox::Sandbox::open(
                    sandbox,
                    &config.work_dir,
                    &format!("{}:{}", config.control.tenant_id, config.host_id),
                    config.limits.max_concurrent,
                )
            })
            .transpose()?;
        Ok(Arc::new(Host {
            verifier: Verifier::new(
                keys,
                &config.permit_issuer,
                &config.host_id,
                &config.control.tenant_id,
                config.limits.max_permit_window_ms,
            ),
            revocations: if config.control.url.starts_with("https://") {
                Some(crate::revocation::Supervisor::open(
                    &config.control.tenant_id,
                    config.control.url.trim_end_matches('/'),
                    &config.work_dir,
                )?)
            } else {
                None
            },
            store: Store::new(config.store_dir.clone(), config.work_dir.clone()),
            control: Arc::new(control),
            slots: Arc::new(Semaphore::new(config.limits.max_concurrent)),
            outbox,
            intents,
            sandbox,
            config,
        }))
    }
}

pub fn router(host: Arc<Host>) -> Router {
    let limit = host.config.limits.max_input_bytes + 64 * 1024;
    Router::new()
        .route("/v1/dispatch", post(dispatch))
        .route(
            "/v1/kills",
            post(kill_push).layer(axum::extract::DefaultBodyLimit::max(2 * 1024 * 1024)),
        )
        .route("/v1/health", get(health))
        .layer(axum::extract::DefaultBodyLimit::max(limit))
        .with_state(host)
}

async fn kill_push(State(host): State<Arc<Host>>, body: Bytes) -> Response {
    // This authority relay is available only on the dedicated mTLS listener.
    if host.config.dispatch_tls.is_none() {
        return StatusCode::FORBIDDEN.into_response();
    }
    let Some(supervisor) = &host.revocations else {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    };
    let Ok(epoch) = supervisor.relay(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    // Merge and its cancellation notification precede the independently
    // authenticated durable receipt. A receipt failure never undoes a kill.
    if host
        .control
        .kill_acknowledge(epoch, olo_toolgate_contracts::v2::KillDeliveryPath::Push)
        .await
        .is_err()
    {
        // Retain and enforce the kill, but retry receipt delivery rather than
        // letting the sender cache an acknowledgement Control did not record.
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    }
    (
        StatusCode::OK,
        [(header::CACHE_CONTROL, "no-store")],
        axum::Json(olo_toolgate_contracts::v2::KillAcknowledgement {
            authorization_epoch: epoch,
            delivery_path: olo_toolgate_contracts::v2::KillDeliveryPath::Push,
        }),
    )
        .into_response()
}

async fn health(State(host): State<Arc<Host>>) -> Response {
    let ready = host.sandbox.as_ref().is_none_or(|pool| pool.available())
        && host
            .revocations
            .as_ref()
            .is_none_or(|supervisor| supervisor.ready());
    let body = serde_json::json!({
        "ready": ready,
        "hostId": host.config.host_id,
        "available": host.slots.available_permits(),
    });
    (
        if ready {
            StatusCode::OK
        } else {
            StatusCode::SERVICE_UNAVAILABLE
        },
        [(header::CACHE_CONTROL, "no-store")],
        axum::Json(body),
    )
        .into_response()
}

fn line<T: serde::Serialize>(value: &T) -> Bytes {
    let mut bytes = serde_json::to_vec(value).expect("contract types serialize");
    bytes.push(b'\n');
    Bytes::from(bytes)
}

fn ndjson(body: Body) -> Response {
    (
        [
            (header::CONTENT_TYPE, "application/x-ndjson"),
            (header::CACHE_CONTROL, "no-store"),
        ],
        body,
    )
        .into_response()
}

fn refusal(invocation_id: &str, reason: RefusalReason) -> AdmissionResponse {
    tracing::info!(target: "toolgate::audit", invocation_id, reason = ?reason, "dispatch refused");
    let id = if valid_identifier(invocation_id) {
        invocation_id
    } else {
        "unknown"
    };
    AdmissionResponse {
        kind: AdmissionKind::Refused,
        invocation_id: id.into(),
        fencing_token: None,
        reason: Some(reason),
    }
}

fn valid_identifier(id: &str) -> bool {
    let bytes = id.as_bytes();
    !bytes.is_empty()
        && bytes.len() <= 128
        && bytes[0].is_ascii_alphanumeric()
        && bytes
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || b"._:/-".contains(b))
}

/// The invocation id a refused permit claims, unverified, only to label the refusal.
fn claimed_id(jws: &str) -> String {
    claimed_field(jws, "invocationId").unwrap_or_default()
}

fn claimed_nonce(jws: &str) -> Option<String> {
    claimed_field(jws, "nonce").filter(|nonce| valid_identifier(nonce))
}

fn claimed_field(jws: &str, field: &str) -> Option<String> {
    use base64::Engine;
    jws.split('.')
        .nth(1)
        .and_then(|p| {
            base64::engine::general_purpose::URL_SAFE_NO_PAD
                .decode(p)
                .ok()
        })
        .and_then(|p| serde_json::from_slice::<Value>(&p).ok())
        .and_then(|v| v.get(field).and_then(Value::as_str).map(str::to_string))
}

async fn dispatch(State(host): State<Arc<Host>>, body: Bytes) -> Response {
    let Ok(dispatch) = serde_json::from_slice::<Dispatch>(&body) else {
        return (StatusCode::BAD_REQUEST, "the body is not a Dispatch").into_response();
    };
    let admitted = match admit(&host, dispatch).await {
        Ok(admitted) => admitted,
        Err(refused) => return ndjson(Body::from(line(&refused))),
    };
    let (tx, rx) = mpsc::channel::<Bytes>(2);
    let _ = tx.send(line(&admitted.admission())).await;
    tokio::spawn(async move {
        let result = run(host, admitted).await;
        let _ = tx.send(line(&result)).await;
    });
    let stream = futures_util::stream::unfold(rx, |mut rx| async move {
        rx.recv().await.map(|b| (Ok::<_, std::io::Error>(b), rx))
    });
    ndjson(Body::from_stream(stream))
}

/// A dispatch the host admitted: its permit claimed with Control and a slot held. Nothing has
/// run yet.
pub struct Admitted {
    claims: InvocationPermitClaims,
    prepared: Prepared,
    dispatch: Dispatch,
    fencing_token: String,
    kill_execution: Option<olo_toolgate_tenant_identity::revocation::Execution>,
    slot: OwnedSemaphorePermit,
}

impl Admitted {
    pub fn admission(&self) -> AdmissionResponse {
        AdmissionResponse {
            kind: AdmissionKind::Admitted,
            invocation_id: self.claims.invocation_id.clone(),
            fencing_token: Some(self.fencing_token.clone()),
            reason: None,
        }
    }
}

/// Reserved `ToolInput` claim: the principal Control signed into the permit (M4.2). SDKs lift it out of the claims.
pub const PRINCIPAL_CLAIM: &str = "toolgate";

/// The pass-through claims plus the verified principal. Only permit fields, never arguments, feed it.
fn principal_claims(claims: &InvocationPermitClaims) -> std::collections::BTreeMap<String, Value> {
    let identity = &claims.identity;
    let mut principal = serde_json::Map::new();
    principal.insert("tenantId".into(), Value::from(identity.tenant_id.clone()));
    principal.insert(
        "mode".into(),
        serde_json::to_value(&identity.mode).unwrap_or(Value::Null),
    );
    if let Some(user) = &identity.user_id {
        principal.insert("userId".into(), Value::from(user.clone()));
    }
    if let Some(agent) = &identity.agent_id {
        principal.insert("agentId".into(), Value::from(agent.clone()));
    }
    principal.insert(
        "chain".into(),
        Value::from(
            identity
                .chain
                .iter()
                .map(|hop| Value::from(hop.agent_id.clone()))
                .collect::<Vec<_>>(),
        ),
    );
    principal.insert("operation".into(), Value::from(claims.action.clone()));
    principal.insert(
        "resources".into(),
        serde_json::to_value(&claims.resources).unwrap_or_default(),
    );
    principal.insert(
        "invocationId".into(),
        Value::from(claims.invocation_id.clone()),
    );
    principal.insert("attempt".into(), Value::from(claims.attempt));
    let mut out = identity.claims.clone().unwrap_or_default();
    out.insert(PRINCIPAL_CLAIM.into(), Value::Object(principal));
    out
}

/// Checks a dispatch, finds what its permit pins and claims the permit with Control. A refusal
/// is definitive: nothing ran.
pub async fn admit(host: &Arc<Host>, dispatch: Dispatch) -> Result<Admitted, AdmissionResponse> {
    let jws = &dispatch.permit.jws;
    let claims = match host.verifier.verify(jws, now_ms()) {
        Ok(claims) => claims,
        Err(PermitError::Expired) => {
            return Err(refusal(&claimed_id(jws), RefusalReason::PermitExpired));
        }
        Err(PermitError::Invalid(why)) => {
            tracing::info!(target: "toolgate::audit", why, "permit invalid");
            return Err(refusal(&claimed_id(jws), RefusalReason::PermitInvalid));
        }
    };
    let id = claims.invocation_id.clone();
    if host
        .revocations
        .as_ref()
        .is_some_and(|supervisor| !supervisor.ready())
    {
        return Err(refusal(&id, RefusalReason::StoreUnavailable));
    }
    let arguments = Value::Object(dispatch.arguments.clone().into_iter().collect());
    if digest::jcs(&arguments).ok().as_deref() != Some(claims.arguments_digest.as_str()) {
        tracing::info!(target: "toolgate::audit", invocation_id = %id, "arguments differ from the permit's argumentsDigest");
        return Err(refusal(&id, RefusalReason::PermitInvalid));
    }
    if claims
        .identity
        .claims
        .as_ref()
        .is_some_and(|c| c.contains_key(PRINCIPAL_CLAIM))
    {
        tracing::info!(target: "toolgate::audit", invocation_id = %id, "pass-through claims use the reserved principal claim");
        return Err(refusal(&id, RefusalReason::PermitInvalid));
    }
    let prepared = match host.store.prepare(&claims.target) {
        Ok(prepared) => prepared,
        Err(StoreError::NotPresent(why)) => {
            tracing::info!(target: "toolgate::audit", invocation_id = %id, why, "content not present");
            return Err(refusal(&id, RefusalReason::DigestNotPresent));
        }
        Err(StoreError::Unavailable(why)) => {
            tracing::warn!(invocation_id = %id, why, "content store unavailable");
            return Err(refusal(&id, RefusalReason::StoreUnavailable));
        }
        Err(StoreError::Unsupported(why)) => {
            tracing::info!(target: "toolgate::audit", invocation_id = %id, why, "runtime unsupported");
            return Err(refusal(&id, RefusalReason::UnsupportedTier));
        }
    };
    let supported = match &host.sandbox {
        Some(pool) => {
            pool.accepts(
                &prepared.runtime.runtime_id,
                prepared.runtime.base_image_digest.as_deref(),
            ) && crate::broker_process::supports_routes(
                &prepared.routes,
                &prepared.profile.destinations,
            ) && prepared.profile.private_ranges.len() <= 64
                && prepared.tool.descriptor.http_auth.len() <= 128
                && prepared.tool.descriptor.variables.secrets.len() <= 128
        }
        None => prepared.runtime.base_image_digest.is_none(),
    };
    if !supported {
        return Err(refusal(&id, RefusalReason::UnsupportedTier));
    }
    let input_cap = prepared
        .tool
        .limits
        .as_ref()
        .and_then(|l| l.max_input_bytes)
        .map_or(host.config.limits.max_input_bytes, |m| {
            (m as usize).min(host.config.limits.max_input_bytes)
        });
    if serde_json::to_vec(&arguments).map_or(usize::MAX, |b| b.len()) > input_cap {
        return Err(refusal(&id, RefusalReason::PermitInvalid));
    }
    let Ok(slot) = host.slots.clone().try_acquire_owned() else {
        return Err(refusal(&id, RefusalReason::Busy));
    };
    // Register before T5 so even kill+recovery arriving during the request is
    // retained until this admission either fails or the worker has exited.
    let kill_execution = host
        .revocations
        .as_ref()
        .map(|supervisor| {
            supervisor.register(olo_toolgate_tenant_identity::revocation::Subject {
                package_id: Some(prepared.package_id.clone()),
                package_digest: Some(claims.target.package_digest.clone()),
                publisher: Some(prepared.publisher.clone()),
                tool_id: Some(claims.target.tool_id.clone()),
                pool_id: None,
                image_digest: prepared.runtime.base_image_digest.clone(),
            })
        })
        .transpose()
        .map_err(|_| refusal(&id, RefusalReason::StoreUnavailable))?;
    // Persist uncertainty before T5: a lost acceptance reply must survive a supervisor crash.
    let intent = admission_intent(&id, &claims.nonce, host.execution_tier());
    if host.intents.enqueue(&intent).is_err() {
        return Err(refusal(&id, RefusalReason::StoreUnavailable));
    }
    let consumed = host
        .control
        .consume(&PermitConsumption {
            invocation_id: id.clone(),
            permit: dispatch.permit.clone(),
            arguments_digest: claims.arguments_digest.clone(),
            tool_descriptor_digest: claims.target.tool_descriptor_digest.clone(),
            package_digest: claims.target.package_digest.clone(),
            artifact_digest: claims.target.artifact_digest.clone(),
        })
        .await;
    let consumed = match consumed {
        Ok(c)
            if consumed_binding(&c, &claims, &prepared)
                && kill_execution.as_ref().is_none_or(|execution| {
                    execution
                        .accept(
                            c.authorization_epoch,
                            crate::revocation::subject(&c.kill_scope),
                        )
                        .is_ok()
                }) =>
        {
            c
        }
        Ok(_) => return Err(refusal(&id, RefusalReason::StoreUnavailable)),
        Err(ControlError::Refused(status)) => {
            // A definite refusal did not accept authority; unknown/lost replies retain the intent.
            let _ = host.intents.remove_report(&intent);
            tracing::info!(target: "toolgate::audit", invocation_id = %id, status, "Control refused the permit");
            return Err(refusal(&id, RefusalReason::PermitInvalid));
        }
        Err(ControlError::Unavailable(why)) => {
            tracing::warn!(invocation_id = %id, why, "Control unavailable for permit consumption");
            return Err(refusal(&id, RefusalReason::StoreUnavailable));
        }
    };
    tracing::info!(
        target: "toolgate::audit",
        invocation_id = %id,
        tool_id = %claims.target.tool_id,
        attempt = claims.attempt,
        tenant_id = %claims.identity.tenant_id,
        mode = ?claims.identity.mode,
        user_id = claims.identity.user_id.as_deref().unwrap_or(""),
        agent_id = claims.identity.agent_id.as_deref().unwrap_or(""),
        chain = claims.identity.chain.len(),
        operation = %claims.action,
        resources = claims.resources.len(),
        authorization_epoch = claims.identity.authorization_epoch,
        "dispatch admitted"
    );
    Ok(Admitted {
        claims,
        prepared,
        dispatch,
        fencing_token: consumed.fencing_token,
        kill_execution,
        slot,
    })
}

// The online acceptance response pins placement facts at the same transaction as T5.
fn consumed_binding(
    consumed: &olo_toolgate_contracts::v2::PermitConsumed,
    claims: &InvocationPermitClaims,
    prepared: &Prepared,
) -> bool {
    let scope = &consumed.kill_scope;
    consumed.invocation_id == claims.invocation_id
        && consumed.fencing_token == claims.nonce
        && consumed.authorization_epoch >= claims.identity.authorization_epoch
        && consumed.lease_expires_at_unix_ms > now_ms()
        && consumed.lease_expires_at_unix_ms <= claims.deadline_unix_ms
        && scope.package_digest == claims.target.package_digest
        && scope.tool_id == claims.target.tool_id
        && scope
            .package_id
            .as_ref()
            .is_none_or(|id| id == &prepared.package_id)
        && scope
            .publisher
            .as_ref()
            .is_none_or(|publisher| publisher == &prepared.publisher)
        && scope.image_digest.as_ref().is_none_or(|image| {
            image == prepared.runtime.base_image_digest.as_deref().unwrap_or("")
        })
}

fn error_frame(code: &str, message: &str, retryable: bool) -> ResultFrame {
    let mut text = BTreeMap::new();
    text.insert("type".to_string(), Value::String("text".into()));
    text.insert("text".to_string(), Value::String(message.into()));
    ResultFrame {
        kind: "RESULT".into(),
        result_type: ResultType::Complete,
        content: vec![text],
        structured_content: None,
        is_error: true,
        error: Some(ToolErrorBody {
            code: code.into(),
            message: message.into(),
            retryable,
            data: None,
        }),
        provenance: None,
    }
}

/// Runs an admitted invocation and reports its outcome to Control.
pub async fn run(host: Arc<Host>, admitted: Admitted) -> ResultMessage {
    let id = admitted.claims.invocation_id.clone();
    let fence = admitted.fencing_token.clone();
    let deadline = admitted.claims.deadline_unix_ms;
    let kill_execution = admitted.kill_execution.clone();
    let termination = async {
        if let (Some(supervisor), Some(execution)) = (&host.revocations, &kill_execution) {
            supervisor.terminated(execution).await;
        } else {
            std::future::pending::<()>().await;
        }
    };
    let execution = async {
        let initial = host.control.heartbeat(&id, &fence).await;
        if let Ok(initial) = initial {
            let supervision = crate::lease::supervise(
                &host.control,
                &id,
                &fence,
                deadline,
                initial,
                host.config.limits.control_loss_grace_ms,
            );
            tokio::select! {
                biased;
                _=supervision=>{},
                result=run_authorized(host.clone(),admitted)=>return Some(result),
            }
        }
        None
    };
    tokio::select! {
        biased;
        _=termination=>{},
        result=execution=>if let Some(result)=result { return result; },
    }
    if let Some(pool) = &host.sandbox {
        pool.stop(&id).await;
    }
    // Dropping the worker future kills its process. A revoked or partitioned run
    // may already have produced effects; never turn lost authority into FAILED.
    finish(
        &host,
        id,
        fence,
        InvocationOutcome::OutcomeUnknown,
        error_frame(
            "OUTCOME_UNKNOWN",
            "Execution authority ended; reconcile any effects",
            false,
        ),
        None,
    )
    .await
}

async fn run_authorized(host: Arc<Host>, admitted: Admitted) -> ResultMessage {
    let Admitted {
        claims,
        prepared,
        dispatch,
        fencing_token,
        kill_execution: _,
        slot,
    } = admitted;
    let id = claims.invocation_id.clone();
    let limits = &host.config.limits;
    let deadline = claims
        .deadline_unix_ms
        .min(now_ms() + limits.sync_deadline_ms.min(CEILING.sync_deadline_ms));
    let started = now_ms();
    // The tool's trace parent and this host's execution span are one span.
    let traceparent = crate::trace::child_traceparent(dispatch.traceparent.as_deref());
    let mut egress = Vec::new();
    if let Some(document) = prepared.proxy.clone() {
        // The native MCP proxy runtime: no process, no sandbox.
        let (outcome, result, not_started) = crate::mcp_call::run(
            &host,
            &claims,
            &prepared,
            &dispatch,
            &fencing_token,
            deadline,
            &document,
        )
        .await;
        drop(slot);
        return finish(&host, id, fencing_token, outcome, result, not_started).await;
    }
    let sandbox = host.config.work_dir.join("i").join(random_hex(8));
    let descriptor = &prepared.tool.descriptor;
    let in_sandbox = descriptor
        .permissions
        .contains(&Permission::CredentialsInSandbox);
    let mut handles = HashMap::new();
    let mut secret_handles = Vec::new();
    if in_sandbox {
        for secret in &descriptor.variables.secrets {
            let handle = random_hex(16);
            handles.insert(handle.clone(), secret.name.clone());
            secret_handles.push(SecretHandle {
                name: secret.name.clone(),
                handle,
            });
        }
    }
    let mut invocation = Invocation::new(
        id.clone(),
        fencing_token.clone(),
        deadline,
        prepared.profile.profile_id.clone(),
        claims.target.service_profile_digest.clone(),
        prepared.routes.clone(),
        prepared.profile.destinations.clone(),
        prepared.profile.private_ranges.clone(),
        descriptor.http_auth.clone(),
        descriptor.variables.secrets.clone(),
        handles,
        host.config.development_loopback_http,
        host.control.clone(),
    );
    invocation.effect_key_headers = prepared
        .profile
        .effect_reviews
        .iter()
        .flatten()
        .filter_map(|review| {
            review
                .policy
                .target_dedupe_guarantee
                .as_ref()
                .map(|g| (review.policy.route_id.clone(), g.header.clone()))
        })
        .collect();
    invocation.invocation_permit = Some(dispatch.permit.clone());
    // The arguments were already checked against the signed admission above.
    if invocation
        .bind_effect_arguments(dispatch.arguments.clone())
        .is_err()
    {
        return finish(
            &host,
            id,
            fencing_token,
            InvocationOutcome::Failed,
            error_frame(
                "TOOL_HOST_UNAVAILABLE",
                "Effect admission unavailable",
                false,
            ),
            Some(RefusalReason::StoreUnavailable),
        )
        .await;
    }
    let invocation = Arc::new(invocation);

    let (outcome, result, not_started) = match std::fs::create_dir_all(&sandbox) {
        Err(e) => {
            tracing::warn!(invocation_id = %id, error = %e, "cannot prepare the sandbox");
            let frame = error_frame(
                "TOOL_HOST_UNAVAILABLE",
                "The Tool Host could not prepare the invocation",
                true,
            );
            (
                InvocationOutcome::Failed,
                frame,
                Some(RefusalReason::StoreUnavailable),
            )
        }
        Ok(()) => {
            let input = ToolInput {
                protocol_version: 2,
                invocation_id: id.clone(),
                task_id: claims.task_id.clone(),
                tool_id: claims.target.tool_id.clone(),
                entry_point: prepared.tool.entry_point.clone().unwrap_or_default(),
                arguments: dispatch.arguments.clone(),
                globals: prepared.globals.clone(),
                claims: principal_claims(&claims),
                secret_handles,
                deadline_unix_ms: deadline,
                idempotency_key: None,
                catalog_revision: claims.catalog_revision.clone(),
                traceparent: traceparent.clone(),
                fencing_token: Some(fencing_token.clone()),
                execution_context: Some(crate::trace::execution_context(
                    dispatch.execution_context.as_ref(),
                    &claims,
                    &fencing_token,
                )),
            };
            let memory = prepared
                .tool
                .limits
                .as_ref()
                .and_then(|l| l.memory_bytes)
                .map_or(limits.memory_bytes, |m| m.min(limits.memory_bytes));
            let output = prepared
                .tool
                .limits
                .as_ref()
                .and_then(|l| l.max_output_bytes)
                .map_or(limits.max_output_bytes, |m| {
                    (m as usize).min(limits.max_output_bytes)
                });
            let launch = Launch {
                runtime_id: &prepared.runtime.runtime_id,
                executable: &prepared.executable,
                entry_point: &input.entry_point,
                sandbox_dir: &sandbox,
                java_path: &host.config.runtimes.java_path,
                python_path: &host.config.runtimes.python_path,
                memory_bytes: memory,
                max_output_bytes: output,
            };
            let (sink, source) = crate::events::channel();
            let sink = Arc::new(sink);
            let forwarder = tokio::spawn(crate::events::forward(
                host.control.clone(),
                id.clone(),
                fencing_token.clone(),
                claims.attempt,
                source,
            ));
            let ran = if let Some(pool) = &host.sandbox {
                pool.run(
                    &id,
                    &prepared.executable,
                    &input,
                    memory,
                    output,
                    sink.clone(),
                    invocation.clone(),
                )
                .await
            } else {
                runner::run(&launch, &input, deadline, Some(&sink), &invocation).await
            };
            // Frames reach Control before the result, so the stream's terminal event follows them.
            drop(sink);
            if tokio::time::timeout(std::time::Duration::from_secs(5), forwarder)
                .await
                .is_err()
            {
                tracing::warn!(invocation_id = %id, "run events still pending at the result report");
            }
            invocation.closed.store(true, Ordering::SeqCst);
            egress = invocation
                .egress
                .lock()
                .map(|e| e.clone())
                .unwrap_or_default();
            let service_write = invocation.service_effect_started.load(Ordering::SeqCst);
            let unknown = claims.requires_execution_ledger
                || service_write
                || host.sandbox.as_ref().is_some_and(|pool| !pool.available());
            match ran {
                Ran::Frame(frame)
                    if frame.is_error
                        && (service_write
                            || frame
                                .error
                                .as_ref()
                                .is_some_and(|e| e.code == "OUTCOME_UNKNOWN")) =>
                {
                    (
                        InvocationOutcome::OutcomeUnknown,
                        error_frame(
                            "OUTCOME_UNKNOWN",
                            "The downstream write may have committed; reconcile its storage receipt",
                            false,
                        ),
                        None,
                    )
                }
                Ran::Frame(frame) if frame.is_error => (InvocationOutcome::Failed, frame, None),
                Ran::Frame(frame) => (InvocationOutcome::Completed, frame, None),
                Ran::TimedOut if unknown => (
                    InvocationOutcome::OutcomeUnknown,
                    error_frame(
                        "OUTCOME_UNKNOWN",
                        "The tool did not finish before its deadline; its effect may have happened",
                        false,
                    ),
                    None,
                ),
                Ran::TimedOut => (
                    InvocationOutcome::Failed,
                    error_frame(
                        "DEADLINE_EXCEEDED",
                        "The tool did not finish before its deadline",
                        true,
                    ),
                    None,
                ),
                Ran::Crashed(why) => {
                    tracing::warn!(invocation_id = %id, why, "tool process failed");
                    if unknown {
                        let message =
                            "The tool stopped without a result; its effect may have happened";
                        (
                            InvocationOutcome::OutcomeUnknown,
                            error_frame("OUTCOME_UNKNOWN", message, false),
                            None,
                        )
                    } else {
                        (
                            InvocationOutcome::Failed,
                            error_frame(
                                "INTERNAL_ERROR",
                                "The tool stopped without a result",
                                false,
                            ),
                            None,
                        )
                    }
                }
            }
        }
    };
    let _ = std::fs::remove_dir_all(&sandbox);
    drop(slot);
    send_spans(
        &host,
        &claims,
        &dispatch,
        traceparent.as_deref(),
        &fencing_token,
        started,
        &result,
        &egress,
    );
    finish(&host, id, fencing_token, outcome, result, not_started).await
}

/// DETAILED Insights: the run's spans go to Control in the background when the dispatch asks for them. Best-effort;
/// a failure never touches the result.
#[allow(clippy::too_many_arguments)]
fn send_spans(
    host: &Arc<Host>,
    claims: &InvocationPermitClaims,
    dispatch: &Dispatch,
    traceparent: Option<&str>,
    fencing_token: &str,
    started: u64,
    result: &ResultFrame,
    egress: &[crate::broker::Egress],
) {
    let (Some(true), Some(own)) = (dispatch.collect_spans, traceparent) else {
        return;
    };
    let execution = crate::trace::execution_id(
        &claims.identity.tenant_id,
        &claims.invocation_id,
        fencing_token,
    );
    let code = result.is_error.then(|| {
        result
            .error
            .as_ref()
            .map(|e| e.code.clone())
            .unwrap_or_else(|| "TOOL_ERROR".into())
    });
    let spans = crate::trace::spans(
        dispatch.traceparent.as_deref(),
        own,
        &claims.invocation_id,
        &execution,
        started,
        now_ms(),
        code.as_deref(),
        egress,
    );
    let control = host.control.clone();
    let id = claims.invocation_id.clone();
    tokio::spawn(async move {
        let batch = olo_toolgate_contracts::v2::InsightsSpanBatch {
            spans,
            dropped_spans: None,
        };
        if let Err(e) = control.spans(&id, &batch).await {
            tracing::debug!(invocation_id = %id, error = ?e, "Insights spans not delivered");
        }
    });
}

/// Reports an invocation's outcome to Control and answers the dispatch with it.
fn admission_intent(
    id: &str,
    fence: &str,
    tier: olo_toolgate_contracts::v2::RuntimeTier,
) -> InvocationResultReport {
    InvocationResultReport {
        invocation_id: id.into(),
        fencing_token: fence.into(),
        outcome: InvocationOutcome::OutcomeUnknown,
        result: None,
        not_started: None,
        execution_tier: tier,
    }
}

async fn finish(
    host: &Arc<Host>,
    id: String,
    fencing_token: String,
    outcome: InvocationOutcome,
    result: ResultFrame,
    not_started: Option<RefusalReason>,
) -> ResultMessage {
    tracing::info!(target: "toolgate::audit", invocation_id = %id, outcome = ?outcome, "invocation finished");
    let report = InvocationResultReport {
        invocation_id: id.clone(),
        fencing_token: fencing_token.clone(),
        outcome: outcome.clone(),
        result: Some(result.clone()),
        not_started,
        execution_tier: host.execution_tier(),
    };
    match host.outbox.enqueue(&report) {
        Ok(path) => {
            if let Err(e) = host.intents.remove_report(&admission_intent(
                &id,
                &fencing_token,
                host.execution_tier(),
            )) {
                tracing::warn!(invocation_id=%id,error=%e,"admission intent cleanup incomplete");
            }
            if host.control.report(&report).await.is_ok() {
                if let Err(e) = host.outbox.acknowledge(&path) {
                    tracing::warn!(invocation_id=%id,error=%e,"result acknowledgement not persisted");
                }
            }
        }
        Err(e) => {
            tracing::error!(invocation_id=%id,error=%e,"result evidence could not be persisted");
        }
    }
    ResultMessage {
        invocation_id: id,
        fencing_token: Some(fencing_token),
        outcome,
        result: Some(result),
    }
}

/// How long pull mode waits before asking Control again when nothing is waiting.
const PULL_INTERVAL: std::time::Duration = std::time::Duration::from_millis(250);

/// Retry evidence independently of execution authority, including after restart.
pub async fn deliver_evidence(host: Arc<Host>) {
    loop {
        if let Err(e) = host.outbox.drain(&host.control).await {
            tracing::warn!(error=%e,"result outbox delivery incomplete");
        }
        tokio::time::sleep(std::time::Duration::from_secs(5)).await;
    }
}

/// Pull mode: takes the calls Control holds for this host and answers each through Control, as
/// [`dispatch`] answers the Gateway. Until the host is enrolled and registered Control refuses,
/// and the loop simply asks again later.
pub async fn pull(host: Arc<Host>) {
    loop {
        match host.control.next_dispatch().await {
            Ok(Some(dispatch)) => {
                tokio::spawn(answer(host.clone(), dispatch));
            }
            Ok(None) => tokio::time::sleep(PULL_INTERVAL).await,
            Err(e) => {
                tracing::debug!(error = %e, "no relayed dispatch");
                tokio::time::sleep(PULL_INTERVAL * 8).await;
            }
        }
    }
}

async fn answer(host: Arc<Host>, dispatch: Dispatch) {
    // Control queued the call under the invocation id its permit names; answer under that id even
    // when the permit is refused, so the Gateway hears the refusal.
    let id = claimed_id(&dispatch.permit.jws);
    let nonce = claimed_nonce(&dispatch.permit.jws);
    match admit(&host, dispatch).await {
        Err(mut refused) => {
            refused.invocation_id = id;
            // Correlates a refused dispatch; it is not evidence of permit consumption.
            refused.fencing_token = nonce;
            if let Err(e) = host.control.admission(&refused).await {
                tracing::warn!(invocation_id = %refused.invocation_id, error = %e, "Control did not take the refusal");
            }
        }
        Ok(admitted) => {
            if let Err(e) = host.control.admission(&admitted.admission()).await {
                tracing::warn!(invocation_id = %id, error = %e, "Control did not take the admission");
            }
            let result = run(host.clone(), admitted).await;
            if let Err(e) = host.control.relayed_result(&result).await {
                tracing::warn!(invocation_id = %id, error = %e, "Control did not take the result");
            }
        }
    }
}
