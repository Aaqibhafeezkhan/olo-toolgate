// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Control is the online authority. Catalog entries are metadata, never effect grants.
use crate::{relay::RelayPort, validation::Contracts};
use olo_toolgate_contracts::v2::{ExecutionContext, RequestContext};
use olo_toolgate_contracts::*;
use std::sync::Arc;

pub struct Gateway {
    pub contracts: Contracts,
    pub authority: Arc<dyn RelayPort>,
}
impl Gateway {
    pub async fn authorize(
        &self,
        request: AuthorizationRequest,
        context: RequestContext,
    ) -> Result<EnterpriseAuthorizationOutcome, ErrorCode> {
        self.authorize_with(request, context, None).await
    }
    /// As [`Self::authorize`], also forwarding the caller's declared correlation (ADR 031). Control records it for
    /// Insights and keeps it out of the request's idempotency digest; it never affects the decision.
    pub async fn authorize_with(
        &self,
        request: AuthorizationRequest,
        context: RequestContext,
        execution_context: Option<ExecutionContext>,
    ) -> Result<EnterpriseAuthorizationOutcome, ErrorCode> {
        self.authorize_business(request, context, execution_context, None)
            .await
    }
    /// Bind an explicit caller key durably at Control, independently of transport request IDs.
    pub async fn authorize_business(
        &self,
        request: AuthorizationRequest,
        mut context: RequestContext,
        execution_context: Option<ExecutionContext>,
        business_key: Option<&str>,
    ) -> Result<EnterpriseAuthorizationOutcome, ErrorCode> {
        let supplied_hash = business_key
            .map(|key| key_hash(&context, key))
            .transpose()?;
        if !self.contracts.valid(
            "AuthorizationRequest",
            &serde_json::to_value(&request).map_err(|_| ErrorCode::Validation)?,
        ) {
            return Err(ErrorCode::Validation);
        }
        let catalog = self.authority.catalog(&context).await?;
        let mut matches = catalog
            .tools
            .iter()
            .filter(|t| t.enabled && t.tool_id == request.tool_id && t.action == request.action);
        let tool = matches.next().ok_or(ErrorCode::Forbidden)?;
        if matches.next().is_some() {
            return Err(ErrorCode::Validation);
        }
        if let Some(policy) = &tool.business_key_policy {
            if !self.contracts.valid(
                "BusinessKeyPolicy",
                &serde_json::to_value(policy).map_err(|_| ErrorCode::Validation)?,
            ) {
                return Err(ErrorCode::Validation);
            }
        }
        let identity = operation_identity(
            &context,
            &request,
            tool.business_key_policy.as_ref(),
            supplied_hash,
        )?;
        context.request_id = identity.request_id;
        let submission = EnterpriseInvocationRequest {
            context: context.clone(),
            request,
            tool_digest: tool.tool_digest.clone(),
            package_digest: tool.package_digest.clone(),
            downstream_idempotency_key: None,
            execution_context,
            business_key_hash: identity.key_hash,
        };
        let invocation = self.authority.invoke(&submission).await?;
        // A dependency cannot substitute another actor, binding or input. Control chooses the device.
        let e = &invocation.evaluation;
        let valid_id = if identity.window {
            canonical_operation_id(&invocation.id)
        } else {
            invocation.id == context.request_id
        };
        if !valid_id
            || e.context.request_id != invocation.id
            || e.context.tenant_id != context.tenant_id
            || e.context.mode != context.mode
            || e.context.user_id != context.user_id
            || e.context.agent_id != context.agent_id
            || e.context.workload_binding_id != context.workload_binding_id
            || e.context.chain != context.chain
            || e.context.session_epoch != context.session_epoch
            || e.context.credential_epoch != context.credential_epoch
            || context.binding_id.as_deref() != Some(e.context.binding_id.as_str())
            || e.tool_id != submission.request.tool_id
            || e.action != submission.request.action
            || e.arguments_digest
                != crate::digest(
                    &serde_json::to_vec(&submission.request.arguments)
                        .map_err(|_| ErrorCode::Validation)?,
                )
            || e.tool_digest != submission.tool_digest
            || e.package_digest != submission.package_digest
        {
            return Err(ErrorCode::Validation);
        }
        // Approval and queue status can be returned safely. Native callers explicitly
        // reserve; MCP dispatch reserves on the device poll so short permits remain fresh.
        Ok(EnterpriseAuthorizationOutcome {
            invocation,
            reservation: None,
        })
    }
    pub async fn reserve(
        &self,
        outcome: EnterpriseAuthorizationOutcome,
    ) -> Result<EnterpriseAuthorizationOutcome, ErrorCode> {
        if outcome.invocation.state != EnterpriseInvocationState::Queued {
            return Ok(outcome);
        }
        let reservation = self
            .authority
            .reserve(&EnterpriseReservationRequest {
                invocation_id: outcome.invocation.id.clone(),
                expected_revision: outcome.invocation.revision,
            })
            .await?;
        if reservation.invocation.id != outcome.invocation.id
            || reservation.invocation.request_digest != outcome.invocation.request_digest
            || reservation.invocation.evaluation != outcome.invocation.evaluation
            || reservation.invocation.state != EnterpriseInvocationState::Reserved
            || reservation.invocation.revision != outcome.invocation.revision + 1
        {
            return Err(ErrorCode::Validation);
        }
        Ok(EnterpriseAuthorizationOutcome {
            invocation: reservation.invocation.clone(),
            reservation: Some(reservation),
        })
    }
    pub fn ready(&self, now: u64) -> bool {
        self.authority.ready(now)
    }
}

/// Tenant, tool and authenticated namespace domain separation; shared with MCP response metadata.
struct OperationIdentity {
    request_id: String,
    key_hash: Option<String>,
    window: bool,
}

fn key_hash(context: &RequestContext, key: &str) -> Result<String, ErrorCode> {
    if key.is_empty()
        || key.len() > 128
        || !key
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
    {
        return Err(ErrorCode::Validation);
    }
    Ok(crate::digest(
        format!("toolgate-business-key-v1\0{}\0{}", context.tenant_id, key).as_bytes(),
    ))
}

fn operation_identity(
    context: &RequestContext,
    request: &AuthorizationRequest,
    policy: Option<&BusinessKeyPolicy>,
    mut hash: Option<String>,
) -> Result<OperationIdentity, ErrorCode> {
    if let Some(policy) = policy {
        let retention = policy.retention_ms.unwrap_or(2_592_000_000);
        if !(1000..=31_536_000_000).contains(&retention)
            || policy
                .dedupe_window_ms
                .is_some_and(|window| !(1000..=86_400_000).contains(&window) || window > retention)
            || policy
                .upstream_idempotency_window_ms
                .is_some_and(|window| window < 1000 || window > retention)
        {
            return Err(ErrorCode::Validation);
        }
        if let Some(argument) = &policy.argument {
            if let Some(value) = request.arguments.get(argument) {
                let derived = key_hash(context, value.as_str().ok_or(ErrorCode::Validation)?)?;
                if hash.as_ref().is_some_and(|supplied| supplied != &derived) {
                    return Err(ErrorCode::Conflict);
                }
                hash = Some(derived);
            }
        }
        if policy.required && hash.is_none() {
            return Err(ErrorCode::Validation);
        }
    }
    let window = hash.is_none() && policy.is_some_and(|policy| policy.dedupe_window_ms.is_some());
    if hash.is_none() && !window {
        return Ok(OperationIdentity {
            request_id: context.request_id.clone(),
            key_hash: None,
            window: false,
        });
    }
    let (namespace, identity) = match policy.map(|policy| &policy.namespace) {
        Some(BusinessKeyNamespace::Agent) => (
            "AGENT",
            context
                .agent_id
                .as_deref()
                .filter(|id| !id.is_empty())
                .ok_or(ErrorCode::Forbidden)?,
        ),
        Some(BusinessKeyNamespace::DelegatedUser) => {
            if context.mode == olo_toolgate_contracts::v2::RequestMode::Service {
                return Err(ErrorCode::Forbidden);
            }
            (
                "DELEGATED_USER",
                context
                    .user_id
                    .as_deref()
                    .filter(|id| !id.is_empty())
                    .ok_or(ErrorCode::Forbidden)?,
            )
        }
        Some(BusinessKeyNamespace::Tenant) => ("TENANT", ""),
        None => match context.agent_id.as_deref() {
            Some(agent) => ("AGENT", agent),
            None => (
                "DELEGATED_USER",
                context.user_id.as_deref().ok_or(ErrorCode::Forbidden)?,
            ),
        },
    };
    let request_id = match &hash {
        Some(hash) => format!(
            "op-{}",
            crate::digest(
                format!(
                    "toolgate-operation-v1\n{}\n{}\n{}\n{}\n{}",
                    context.tenant_id, request.tool_id, namespace, identity, hash
                )
                .as_bytes()
            )
        ),
        None => context.request_id.clone(),
    };
    Ok(OperationIdentity {
        request_id,
        key_hash: hash,
        window,
    })
}

fn canonical_operation_id(id: &str) -> bool {
    id.strip_prefix("op-").is_some_and(|suffix| {
        suffix.len() == 64
            && suffix
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    })
}
