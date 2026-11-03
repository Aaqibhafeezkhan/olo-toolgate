// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Immutable native MCP admission. A Session may propose only its fully prepared tools/call bytes.
use crate::{control::Control, effect_dispatch::BoundedTransport};
use olo_toolgate_contracts::v2::{
    Dispatch, EffectGrantIssue, InvocationPermitClaims, ServiceDestination, ServiceProfile,
};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};

pub(crate) struct Authority<'a> {
    control: &'a Control,
    dispatch: &'a Dispatch,
    claims: &'a InvocationPermitClaims,
    profile: &'a ServiceProfile,
    fence: &'a str,
    deadline: u64,
    broker: String,
    used: AtomicBool,
    pub(crate) started: Arc<AtomicBool>,
    closed: Arc<AtomicBool>,
}
pub(crate) fn route(tool: &str) -> String {
    format!(
        "mcp-{}",
        &toolgate_cli::canonical::sha256_tagged(tool.as_bytes())[7..39]
    )
}
impl<'a> Authority<'a> {
    pub(crate) fn new(
        control: &'a Control,
        dispatch: &'a Dispatch,
        claims: &'a InvocationPermitClaims,
        profile: &'a ServiceProfile,
        fence: &'a str,
        deadline: u64,
    ) -> Self {
        Self {
            control,
            dispatch,
            claims,
            profile,
            fence,
            deadline,
            broker: crate::random_hex(16),
            used: AtomicBool::new(false),
            started: Arc::new(AtomicBool::new(false)),
            closed: Arc::new(AtomicBool::new(false)),
        }
    }
    pub(crate) fn metadata(
        &self,
        slug: &str,
        credential: &str,
    ) -> crate::request_binding::Metadata {
        crate::request_binding::Metadata {
            profile_id: self.profile.profile_id.clone(),
            profile_digest: self.claims.target.service_profile_digest.clone(),
            route_id: route(&self.claims.target.tool_id),
            auth_id: Some(format!("toolgate-mcp-upstream:{slug}")),
            credential_headers: vec![credential.to_owned()],
        }
    }
    pub(crate) async fn authorize(
        &self,
        prepared: &crate::request_binding::PreparedRequest,
        handler: &str,
        destination: &ServiceDestination,
        policy: crate::egress::AddressPolicy,
    ) -> Result<BoundedTransport, crate::stream::StreamError> {
        let refused =
            || crate::stream::StreamError::Connect("native MCP effect authority refused".into());
        if self.used.swap(true, Ordering::SeqCst) {
            return Err(refused());
        }
        let manifest =
            serde_json::from_value(prepared.manifest().clone()).map_err(|_| refused())?;
        let request = EffectGrantIssue {
            invocation_id: self.claims.invocation_id.clone(),
            fencing_token: self.fence.into(),
            invocation_permit: self.dispatch.permit.clone(),
            broker_instance_id: self.broker.clone(),
            handler_id: handler.into(),
            arguments: self.dispatch.arguments.clone(),
            http_request_digest: prepared.digest().into(),
            http_request_manifest: manifest,
        };
        crate::effect_dispatch::authorize(
            self.control,
            &request,
            destination,
            policy,
            self.deadline,
            &self.started,
            &self.closed,
        )
        .await
        .map_err(|_| refused())
    }
}
impl Drop for Authority<'_> {
    fn drop(&mut self) {
        self.closed.store(true, Ordering::SeqCst);
    }
}

#[cfg(all(test, unix))]
#[path = "mcp_effect_tests.rs"]
mod tests;
