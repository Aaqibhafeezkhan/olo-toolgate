// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Control is the online authority. Catalog entries are metadata, never effect grants.
use crate::{relay::RelayPort, validation::Contracts};
use olo_toolgate_contracts::v2::RequestContext;
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
        let submission = EnterpriseInvocationRequest {
            context: context.clone(),
            request,
            tool_digest: tool.tool_digest.clone(),
            package_digest: tool.package_digest.clone(),
            downstream_idempotency_key: None,
        };
        let invocation = self.authority.invoke(&submission).await?;
        // A dependency cannot substitute another actor, binding or input. Control chooses the device.
        let e = &invocation.evaluation;
        if invocation.id != context.request_id
            || e.context.request_id != context.request_id
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
