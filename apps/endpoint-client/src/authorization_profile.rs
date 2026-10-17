// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Signed managed registrations pin actual code and extraction versions without granting access.
use crate::{contracts::Contracts, Failure, Result};
use olo_toolgate_contracts::*;
use serde_json::{json, Value};

pub fn tool_digest(profile: &InstalledAuthorizationProfile) -> Result<String> {
    let canonical = |v: &Value| serde_json::to_string(v).map_err(|_| Failure::Validation);
    Ok(crate::digest(
        format!(
            "{}\n{}\n{}\n{}",
            canonical(
                &serde_json::to_value(&profile.tool.definition).map_err(|_| Failure::Validation)?
            )?,
            profile.tool.version,
            profile.tool.package_digest,
            canonical(&serde_json::to_value(&profile.extractor).map_err(|_| Failure::Validation)?)?
        )
        .as_bytes(),
    ))
}
pub fn validate(profile: &InstalledAuthorizationProfile) -> Result<()> {
    Contracts::shared()?.encode("InstalledAuthorizationProfile", profile)?;
    if !profile.tool.enabled
        || !profile.extractor.enabled
        || profile.tool.id != profile.tool.definition.id
        || profile.tool.extractor_id != profile.extractor.id
    {
        return Err(Failure::Validation);
    }
    Ok(())
}
pub fn managed_package_digest(
    tool: &LocalToolRegistration,
    runtime: &ManagedRuntime,
) -> Result<String> {
    let mut registration = serde_json::to_value(tool).map_err(|_| Failure::Validation)?;
    registration
        .as_object_mut()
        .ok_or(Failure::Validation)?
        .remove("authorizationProfile");
    Ok(crate::digest(
        &serde_json::to_vec(&json!({"registration":registration,"runtime":runtime}))
            .map_err(|_| Failure::Validation)?,
    ))
}
pub fn managed_info(
    tool: &LocalToolRegistration,
    runtime: &ManagedRuntime,
) -> Result<LocalToolInfo> {
    let profile = &tool.authorization_profile;
    validate(profile)?;
    if profile.tool.id != tool.tool_id
        || profile.tool.definition.input_schema != tool.input_schema
        || profile.tool.definition.output_schema != tool.output_schema
        || profile.tool.package_digest != managed_package_digest(tool, runtime)?
        || !profile
            .tool
            .definition
            .actions
            .iter()
            .any(|a| a.name == tool.action)
    {
        return Err(Failure::Validation);
    }
    Ok(LocalToolInfo {
        tool_id: tool.tool_id.clone(),
        action: tool.action.clone(),
        description: profile.tool.definition.description.clone(),
        enabled: true,
        input_schema: tool.input_schema.clone(),
        tool_digest: tool_digest(profile)?,
        package_digest: profile.tool.package_digest.clone(),
    })
}
