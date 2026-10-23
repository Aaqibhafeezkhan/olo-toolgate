// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The MCP scan worker (docs/planning/mcp-proxy/plan.md §6, §7 Scan). It takes the scans Control queued for this host,
//! verifies and consumes each scan permit, redeems the upstream credential, and then, within the permit's signed
//! deadline: connects (with the approved pins for a refresh, so a changed key fails before the credential is sent),
//! runs `initialize`, pages through `tools/list` within the scan limits, and reports the raw definitions with the
//! observed SPKI pin. Control recomputes every digest.

use crate::mcp::{self, Session};
use crate::server::Host;
use crate::stream::Limits;
use olo_toolgate_contracts::v2::{
    McpCheckFailure, McpScanClaims, McpScanDispatch, McpScanFailureReport, McpScanPurpose,
    McpScanResult,
};
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::Duration;
use tokio::time::Instant;

const PULL_INTERVAL: Duration = Duration::from_secs(1);

/// Takes the scans Control holds for this host, one task each. Until the host is registered Control refuses, and the
/// loop asks again later.
pub async fn pull(host: Arc<Host>) {
    loop {
        match host.control().next_scan().await {
            Ok(Some(dispatch)) => {
                tokio::spawn(scan(host.clone(), dispatch));
            }
            Ok(None) => tokio::time::sleep(PULL_INTERVAL).await,
            Err(e) => {
                tracing::debug!(error = %e, "no MCP scan");
                tokio::time::sleep(PULL_INTERVAL * 8).await;
            }
        }
    }
}

/// Runs one scan and reports its outcome. A permit that does not verify is dropped: nothing is redeemed or contacted.
pub async fn scan(host: Arc<Host>, dispatch: McpScanDispatch) {
    let claims = match host
        .verifier()
        .verify_scan(&dispatch.permit, crate::now_ms())
    {
        Ok(claims) if claims.scan_id == dispatch.scan_id => claims,
        Ok(_) | Err(_) => {
            tracing::info!(target: "toolgate::audit", scan_id = %dispatch.scan_id, "scan permit refused");
            return;
        }
    };
    let control = host.control();
    if let Err(e) = control.consume_scan(&claims).await {
        tracing::info!(target: "toolgate::audit", scan_id = %claims.scan_id, error = %e, "Control refused the scan permit");
        return;
    }
    let outcome = match control
        .scan_secret(&claims.scan_id, &claims.fencing_token)
        .await
    {
        Ok(secret) => observe(&claims, secret, host.allow_loopback()).await,
        Err(e) => {
            tracing::warn!(scan_id = %claims.scan_id, error = %e, "no upstream credential for the scan");
            Err((McpCheckFailure::UpstreamError, None))
        }
    };
    let reported = match outcome {
        Ok(result) => control.scan_result(&result).await,
        Err((failure, observed)) => {
            tracing::info!(target: "toolgate::audit", scan_id = %claims.scan_id, failure = ?failure, "MCP scan failed");
            control
                .scan_failure(&McpScanFailureReport {
                    scan_id: claims.scan_id.clone(),
                    fencing_token: claims.fencing_token.clone(),
                    failure,
                    observed_spki_pin: observed,
                })
                .await
        }
    };
    if let Err(e) = reported {
        tracing::warn!(scan_id = %claims.scan_id, error = %e, "Control did not take the scan outcome");
    }
}

/// What the upstream says, within the permit's deadline and the scan limits; on failure, why, with the pin observed.
pub async fn observe(
    claims: &McpScanClaims,
    secret: String,
    allow_loopback: bool,
) -> Result<McpScanResult, (McpCheckFailure, Option<String>)> {
    let deadline = Instant::now()
        + Duration::from_millis(claims.deadline_unix_ms.saturating_sub(crate::now_ms()));
    // A refresh is always pinned: only an administrator's DISCOVER scan connects without the approved pins.
    if claims.purpose == McpScanPurpose::Refresh && claims.spki_pins.is_empty() {
        return Err((McpCheckFailure::PinMismatch, None));
    }
    let mut session = Session::open(
        &claims.url,
        &claims.spki_pins,
        &claims.auth,
        secret,
        allow_loopback,
        Limits::MCP,
        deadline,
    )
    .map_err(|e| (e.check(), None))?;
    let initialized = match session.initialize().await {
        Ok(initialized) => initialized,
        Err(e) => return Err((e.check(), session.observed_pin.clone())),
    };
    let tools = match session.list().await {
        Ok(tools) => tools,
        Err(e) => return Err((e.check(), session.observed_pin.clone())),
    };
    let pin = session
        .observed_pin
        .clone()
        .ok_or((McpCheckFailure::TlsError, None))?;
    let failed = |failure| (failure, Some(pin.clone()));
    let tools: Vec<Value> = tools
        .iter()
        .map(mcp::project)
        .collect::<Option<_>>()
        .ok_or_else(|| failed(McpCheckFailure::ProtocolError))?;
    let info = &initialized.server_info;
    let mut server_info = json!({"name": info["name"], "version": info["version"]});
    if let Some(title) = info.get("title").filter(|t| t.is_string()) {
        server_info["title"] = title.clone();
    }
    serde_json::from_value(json!({
        "scanId": claims.scan_id, "fencingToken": claims.fencing_token, "serverInfo": server_info,
        "protocolVersion": initialized.protocol_version, "spkiPin": pin, "tools": tools,
    }))
    .map_err(|_| failed(McpCheckFailure::ProtocolError))
}
