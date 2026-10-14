// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Thin ingress adapters. Security processing stays in the application/ports.
use crate::{
    application::Gateway,
    auth::Authenticator,
    config::Config,
    digest,
    limits::{Metrics, RateLimit},
    unix_ms,
};
use axum::{
    body::to_bytes,
    extract::{Request, State},
    http::{HeaderMap, HeaderValue, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use olo_toolgate_contracts::v2::RequestContext;
use olo_toolgate_contracts::{
    AuthorizationRequest, EnterpriseInvocationState, ErrorCode, ErrorEnvelope,
};
use serde_json::Value;
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc,
};
use tokio::{
    sync::Semaphore,
    time::{timeout, Duration, Instant},
};
use tracing::Instrument;

/// Per-process safety state; no distributed authorization state is held here.
pub struct AppState {
    pub gateway: Gateway,
    pub auth: Authenticator,
    pub config: Config,
    pub metrics: Metrics,
    pub draining: AtomicBool,
    pub admission: Semaphore,
    pub tool_hosts: crate::toolhost::ToolHosts,
    rate: RateLimit,
    sequence: AtomicU64,
    instance: String,
}

#[derive(Clone)]
pub(crate) struct Correlation {
    pub(crate) request_id: String,
    pub(crate) trace_id: String,
    traceparent: String,
}
#[derive(Clone, Copy)]
pub(crate) struct CredentialDeadline(pub(crate) u64);

impl AppState {
    /// Construct only after startup validation and credential loading.
    pub fn new(gateway: Gateway, auth: Authenticator, config: Config) -> Self {
        let instance = digest(
            format!(
                "{}-{}-{:?}",
                std::process::id(),
                unix_ms().unwrap_or(0),
                std::time::Instant::now()
            )
            .as_bytes(),
        );
        Self {
            admission: Semaphore::new(config.limits.max_concurrent_requests),
            rate: RateLimit::new(config.limits.requests_per_second),
            tool_hosts: crate::toolhost::ToolHosts::new(&config.tool_hosts),
            gateway,
            auth,
            config,
            metrics: Metrics::default(),
            draining: AtomicBool::new(false),
            sequence: AtomicU64::new(1),
            instance,
        }
    }

    /// Readiness includes freshness, credentials, audit and the drain state.
    pub fn ready(&self) -> bool {
        !self.draining.load(Ordering::Acquire)
            && unix_ms().is_some_and(|now| self.gateway.ready(now) && self.auth.ready(now))
    }

    /// Re-read the mounted credential file; on any error the current set stays live.
    pub fn reload_credentials(&self, path: &std::path::Path) -> Result<usize, &'static str> {
        let bytes = crate::config::read_bytes(path)?;
        let now = unix_ms().ok_or("system clock unavailable")?;
        self.auth.reload(&bytes, &self.gateway.contracts, now)
    }

    fn correlation(&self, headers: &HeaderMap) -> Correlation {
        let n = self.sequence.fetch_add(1, Ordering::Relaxed);
        let id = digest(format!("{}-{n}", self.instance).as_bytes());
        let parent = single_header(headers, "traceparent").filter(|s| valid_traceparent(s));
        let trace_id = parent
            .map(|v| v[3..35].to_owned())
            .unwrap_or_else(|| id[..32].into());
        Correlation {
            request_id: format!("gw-{}", &id[..32]),
            traceparent: format!(
                "00-{trace_id}-{}-{}",
                &id[32..48],
                parent.map(|v| &v[53..55]).unwrap_or("00")
            ),
            trace_id,
        }
    }
}

/// Runtime ingress only. Management endpoints are deliberately absent here.
pub fn runtime_router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/access/invocations", post(authorize))
        .route("/access/catalog", post(catalog))
        .route("/mcp", post(crate::mcp::ingress))
        .fallback(not_found)
        .method_not_allowed_fallback(method_not_allowed)
        .layer(middleware::from_fn_with_state(state.clone(), guard))
        .with_state(state)
}

/// Bind this router to a separately restricted management listener.
pub fn management_router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/v1/health/live", get(|| async { StatusCode::OK }))
        .route(
            "/v1/health/ready",
            get(|State(s): State<Arc<AppState>>| async move {
                if s.ready() {
                    StatusCode::OK
                } else {
                    StatusCode::SERVICE_UNAVAILABLE
                }
            }),
        )
        // Which credential file is live: its digest and size only, so a writer can confirm a reload.
        .route(
            "/v1/credentials",
            get(|State(s): State<Arc<AppState>>| async move {
                let (sha256, count) = s.auth.loaded();
                Json(serde_json::json!({"sha256": sha256, "count": count}))
            }),
        )
        .route(
            "/v1/metrics",
            get(|State(s): State<Arc<AppState>>| async move {
                (
                    [("content-type", "text/plain; version=0.0.4")],
                    s.metrics.render(),
                )
            }),
        )
        .with_state(state)
}

pub(crate) fn single_header<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    let mut values = headers.get_all(name).iter();
    let value = values.next()?.to_str().ok()?;
    values.next().is_none().then_some(value)
}

fn valid_traceparent(v: &str) -> bool {
    v.is_ascii()
        && v.len() == 55
        && v.starts_with("00-")
        && v.as_bytes()[35] == b'-'
        && v.as_bytes()[52] == b'-'
        && v[3..35]
            .bytes()
            .chain(v[36..52].bytes())
            .chain(v[53..].bytes())
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        && v[3..35].bytes().any(|b| b != b'0')
        && v[36..52].bytes().any(|b| b != b'0')
}

pub(crate) fn status(code: &ErrorCode) -> StatusCode {
    match code {
        ErrorCode::Validation => StatusCode::BAD_REQUEST,
        ErrorCode::Unauthorized => StatusCode::UNAUTHORIZED,
        ErrorCode::Forbidden => StatusCode::FORBIDDEN,
        ErrorCode::NotFound => StatusCode::NOT_FOUND,
        ErrorCode::Timeout => StatusCode::GATEWAY_TIMEOUT,
        ErrorCode::DependencyUnavailable => StatusCode::SERVICE_UNAVAILABLE,
        ErrorCode::Unsupported => StatusCode::NOT_IMPLEMENTED,
        _ => StatusCode::INTERNAL_SERVER_ERROR,
    }
}

pub(crate) fn error(code: ErrorCode, id: &str) -> Response {
    let retryable = matches!(code, ErrorCode::DependencyUnavailable | ErrorCode::Timeout);
    let mut response = (
        status(&code),
        Json(ErrorEnvelope {
            code: code.clone(),
            request_id: id.into(),
            retryable,
        }),
    )
        .into_response();
    if code == ErrorCode::Unauthorized {
        response.headers_mut().insert(
            "www-authenticate",
            HeaderValue::from_static("Bearer realm=\"toolgate-runtime\""),
        );
    }
    response
}

async fn guard(State(s): State<Arc<AppState>>, mut request: Request, next: Next) -> Response {
    let started = Instant::now();
    let mcp_packet = request.uri().path() == "/mcp";
    let correlation = s.correlation(request.headers());
    request.extensions_mut().insert(correlation.clone());
    let span = tracing::info_span!("runtime_request", service="gateway", version=env!("CARGO_PKG_VERSION"), request_id=%correlation.request_id, trace_id=%correlation.trace_id);
    let result = async {
        let header_bytes: usize = request
            .headers()
            .iter()
            .map(|(name, value)| name.as_str().len() + value.as_bytes().len())
            .sum();
        if header_bytes > s.config.limits.max_header_bytes {
            let mut r = error(ErrorCode::Validation, &correlation.request_id);
            *r.status_mut() = StatusCode::REQUEST_HEADER_FIELDS_TOO_LARGE;
            return r;
        }
        if s.draining.load(Ordering::Acquire) {
            return error(ErrorCode::DependencyUnavailable, &correlation.request_id);
        }
        let Ok(_permit) = s.admission.try_acquire() else {
            s.metrics.limited.fetch_add(1, Ordering::Relaxed);
            let mut r = error(ErrorCode::DependencyUnavailable, &correlation.request_id);
            r.headers_mut()
                .insert("retry-after", HeaderValue::from_static("1"));
            return r;
        };
        if !s.rate.admit(Instant::now()) {
            s.metrics.limited.fetch_add(1, Ordering::Relaxed);
            let mut r = error(ErrorCode::DependencyUnavailable, &correlation.request_id);
            *r.status_mut() = StatusCode::TOO_MANY_REQUESTS;
            r.headers_mut()
                .insert("retry-after", HeaderValue::from_static("1"));
            return r;
        }
        if request.headers().contains_key("origin")
            && !single_header(request.headers(), "origin")
                .is_some_and(|v| s.config.allowed_origins.iter().any(|o| o == v))
        {
            return error(ErrorCode::Forbidden, &correlation.request_id);
        }
        let bearer = single_header(request.headers(), "authorization")
            .and_then(|v| v.strip_prefix("Bearer "));
        let context = bearer.and_then(|b| {
            unix_ms().and_then(|now| s.auth.authenticate(b, correlation.request_id.clone(), now))
        });
        let Some((context, credential_expiry)) = context else {
            return error(ErrorCode::Unauthorized, &correlation.request_id);
        };
        request.extensions_mut().insert(context);
        request
            .extensions_mut()
            .insert(CredentialDeadline(credential_expiry));
        match timeout(
            Duration::from_millis(s.config.limits.request_timeout_ms),
            next.run(request),
        )
        .await
        {
            Ok(response) if unix_ms().is_some_and(|now| now < credential_expiry) => response,
            Ok(_) => error(ErrorCode::Unauthorized, &correlation.request_id),
            Err(_) => error(ErrorCode::Timeout, &correlation.request_id),
        }
    }
    .instrument(span.clone())
    .await;
    let mut result = result;
    if mcp_packet {
        crate::diagnostics::packet(
            "SEND",
            "/mcp",
            &correlation.request_id,
            Some(result.status().as_u16()),
            serde_json::json!({"code":"HTTP_RESPONSE"}),
        );
    }
    result.headers_mut().insert(
        "x-request-id",
        HeaderValue::from_str(&correlation.request_id).expect("generated ASCII identifier"),
    );
    result.headers_mut().insert(
        "traceparent",
        HeaderValue::from_str(&correlation.traceparent).expect("generated ASCII trace"),
    );
    result
        .headers_mut()
        .insert("cache-control", HeaderValue::from_static("no-store"));
    if result.status().is_client_error() || result.status().is_server_error() {
        s.metrics.errors.fetch_add(1, Ordering::Relaxed);
    }
    s.metrics.observe(started.elapsed());
    let _entered = span.enter();
    tracing::info!(
        event = "request_completed",
        status = result.status().as_u16(),
        duration_micros = started.elapsed().as_micros() as u64
    );
    result
}

pub(crate) async fn parse(
    s: &AppState,
    request: Request,
) -> Result<(Value, RequestContext, Correlation, HeaderMap), Response> {
    let correlation = request
        .extensions()
        .get::<Correlation>()
        .expect("guard correlation")
        .clone();
    let context = request
        .extensions()
        .get::<RequestContext>()
        .expect("guard authentication")
        .clone();
    if !single_header(request.headers(), "content-type").is_some_and(|v| {
        v.split(';')
            .next()
            .is_some_and(|t| t.trim() == "application/json")
    }) {
        let mut r = error(ErrorCode::Validation, &correlation.request_id);
        *r.status_mut() = StatusCode::UNSUPPORTED_MEDIA_TYPE;
        return Err(r);
    }
    if request.headers().contains_key("content-encoding") {
        return Err(error(ErrorCode::Unsupported, &correlation.request_id));
    }
    let (parts, body) = request.into_parts();
    let bytes = to_bytes(body, s.config.limits.max_body_bytes)
        .await
        .map_err(|_| {
            let mut r = error(ErrorCode::Validation, &correlation.request_id);
            *r.status_mut() = StatusCode::PAYLOAD_TOO_LARGE;
            r
        })?;
    let value = crate::validation::strict_json(&bytes).map_err(|_| {
        if parts.uri.path() == "/mcp" {
            crate::mcp::rpc_error(
                StatusCode::BAD_REQUEST,
                Value::Null,
                -32700,
                "Parse error",
                Value::Null,
            )
        } else {
            error(ErrorCode::Validation, &correlation.request_id)
        }
    })?;
    Ok((value, context, correlation, parts.headers))
}

async fn catalog(State(s): State<Arc<AppState>>, request: Request) -> Response {
    let (value, context, correlation, _) = match parse(&s, request).await {
        Ok(v) => v,
        Err(r) => return r,
    };
    if !value.is_object()
        || value
            .as_object()
            .is_some_and(|v| v.keys().any(|k| k != "agentId"))
        || value
            .get("agentId")
            .is_some_and(|v| v.as_str() != context.agent_id.as_deref())
    {
        return error(ErrorCode::Validation, &correlation.request_id);
    }
    match s.gateway.authority.catalog(&context).await {
        Ok(c) => Json(c).into_response(),
        Err(e) => error(e, &correlation.request_id),
    }
}
async fn authorize(State(s): State<Arc<AppState>>, request: Request) -> Response {
    let (value, mut context, correlation, headers) = match parse(&s, request).await {
        Ok(v) => v,
        Err(r) => return r,
    };
    if !s.gateway.contracts.valid("AuthorizationRequest", &value) {
        return error(ErrorCode::Validation, &correlation.request_id);
    }
    let request: AuthorizationRequest = match serde_json::from_value(value) {
        Ok(v) => v,
        Err(_) => return error(ErrorCode::Validation, &correlation.request_id),
    };
    let Some(key) = single_header(&headers, "idempotency-key").filter(|v| {
        !v.is_empty()
            && v.len() <= 128
            && v.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
    }) else {
        return error(ErrorCode::Validation, &correlation.request_id);
    };
    context.request_id = invocation_id(&context, key);
    match s.gateway.authorize(request, context).await {
        Ok(outcome) => match s.gateway.reserve(outcome).await {
            Ok(outcome) => {
                if matches!(
                    outcome.invocation.state,
                    EnterpriseInvocationState::Reserved
                ) {
                    &s.metrics.allow
                } else {
                    &s.metrics.block
                }
                .fetch_add(1, Ordering::Relaxed);
                Json(outcome).into_response()
            }
            Err(code) => error(code, &correlation.request_id),
        },
        Err(code) => error(code, &correlation.request_id),
    }
}

pub(crate) fn invocation_id(context: &RequestContext, key: &str) -> String {
    let mut identity = context.clone();
    identity.request_id = "identity".into();
    identity.credential_sha256 = None;
    format!(
        "inv-{}",
        &digest(&serde_json::to_vec(&(identity, key)).expect("serializable identity"))[..48]
    )
}

async fn not_found(request: Request) -> Response {
    error(
        ErrorCode::NotFound,
        &request
            .extensions()
            .get::<Correlation>()
            .expect("guard")
            .request_id,
    )
}
async fn method_not_allowed(request: Request) -> Response {
    let mut r = error(
        ErrorCode::Unsupported,
        &request
            .extensions()
            .get::<Correlation>()
            .expect("guard")
            .request_id,
    );
    *r.status_mut() = StatusCode::METHOD_NOT_ALLOWED;
    r.headers_mut()
        .insert("allow", HeaderValue::from_static("POST"));
    r
}
