// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! `.tgpkg` verification, validation and packing (platform-requirements.md §7.2 and §7.6).
//!
//! - [`verify`] applies the archive rules every hop runs: the zip rules, the exact file set
//!   against a canonical `CHECKSUMS.sha256`, and manifest artifact digests.
//! - [`validate`] adds the packager's checks: the manifest against `PackageManifest` v2, every
//!   descriptor digest and the set digest, `tools/` and `triggers/` files, artifact references,
//!   ceilings and the trigger rules of ADR 058 (plan 5.7 and 5.8).
//! - [`pack`] builds a package from a staging directory and validates its own output.

use crate::archive::{self, check_collisions, check_name};
use crate::canonical::{canonical_bytes, sha256_hex, sha256_tagged};
use crate::descriptor::{export_set_digest, tool_descriptor_digest};
use crate::{strict_json, Error};
use serde_json::{Map, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

pub const CHECKSUMS: &str = "CHECKSUMS.sha256";
pub const MANIFEST: &str = "toolgate-package.json";
const SIGNATURES: &str = "signatures/";
/// Platform ceilings (platform-requirements.md §13.5): tools per package and canonical descriptor size.
pub const MAX_TOOLS: u64 = 64;
pub const MAX_DESCRIPTOR_BYTES: u64 = 1024 * 1024;
/// Optional files `pack` copies from the staging directory when present.
const OPTIONAL: [&str; 6] = [
    "marketplace/listing.json",
    "marketplace/README.md",
    "marketplace/CHANGELOG.md",
    "marketplace/icon.png",
    "tests/self-tests.json",
    "sbom/cyclonedx.json",
];

/// A package that passed [`verify`].
#[derive(Debug)]
pub struct Verified {
    /// Every entry, including `CHECKSUMS.sha256` and any `signatures/` files.
    pub files: BTreeMap<String, Vec<u8>>,
    pub manifest: Value,
    /// `"sha256:" + hex` of the exact `CHECKSUMS.sha256` bytes; never the digest of the zip.
    pub package_digest: String,
}

/// What [`validate`] established about a package.
#[derive(Debug)]
pub struct Report {
    pub package_digest: String,
    pub descriptor_set_digest: String,
    /// `(toolId, toolDescriptorDigest)` in manifest order.
    pub tools: Vec<(String, String)>,
    /// `(triggerId, triggerDescriptorDigest)` in manifest order.
    pub triggers: Vec<(String, String)>,
}

fn reject(reason: impl Into<String>) -> Error {
    Error::Rejected(reason.into())
}

/// Parses a canonical `CHECKSUMS.sha256`: UTF-8, LF only, one `<64 lowercase hex>  <NFC path>`
/// line per file, sorted bytewise by path, no blank or comment lines.
pub fn parse_checksums(bytes: &[u8]) -> Result<BTreeMap<String, String>, Error> {
    let text = std::str::from_utf8(bytes).map_err(|_| reject("CHECKSUMS.sha256 is not UTF-8"))?;
    if text.is_empty() || !text.ends_with('\n') || text.contains('\r') {
        return Err(reject(
            "CHECKSUMS.sha256 is not LF-terminated canonical text",
        ));
    }
    let mut listed = BTreeMap::new();
    let mut previous: Option<&str> = None;
    for line in text[..text.len() - 1].split('\n') {
        let (hex, path) = line
            .split_once("  ")
            .ok_or_else(|| reject(format!("malformed CHECKSUMS.sha256 line: {line:?}")))?;
        if hex.len() != 64
            || !hex
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(reject(format!(
                "CHECKSUMS.sha256 digest is not 64 lowercase hex: {line:?}"
            )));
        }
        check_name(path)?;
        if path == CHECKSUMS || path.starts_with(SIGNATURES) {
            return Err(reject(format!("CHECKSUMS.sha256 must not list {path:?}")));
        }
        if previous.is_some_and(|p| p.as_bytes() >= path.as_bytes()) {
            return Err(reject("CHECKSUMS.sha256 is not sorted bytewise by path"));
        }
        previous = Some(path);
        listed.insert(path.to_owned(), hex.to_owned());
    }
    Ok(listed)
}

/// Renders the canonical `CHECKSUMS.sha256` for a file set.
pub fn render_checksums(files: &BTreeMap<String, Vec<u8>>) -> Vec<u8> {
    // BTreeMap<String, _> iterates in bytewise order, which is the required order.
    files
        .iter()
        .map(|(path, bytes)| format!("{}  {path}\n", sha256_hex(bytes)))
        .collect::<String>()
        .into_bytes()
}

/// The archive rules run at every hop (platform-requirements.md §7.6).
pub fn verify(data: &[u8]) -> Result<Verified, Error> {
    let archive = archive::read(data)?;
    let checksums = archive
        .files
        .get(CHECKSUMS)
        .ok_or_else(|| reject("CHECKSUMS.sha256 is missing"))?;
    let listed = parse_checksums(checksums)?;
    for (path, bytes) in &archive.files {
        if path == CHECKSUMS || path.starts_with(SIGNATURES) {
            continue;
        }
        match listed.get(path) {
            None => {
                return Err(reject(format!(
                    "file not listed in CHECKSUMS.sha256: {path:?}"
                )))
            }
            Some(hex) if *hex != sha256_hex(bytes) => {
                return Err(reject(format!("checksum mismatch: {path:?}")))
            }
            Some(_) => {}
        }
    }
    if let Some(missing) = listed.keys().find(|p| !archive.files.contains_key(*p)) {
        return Err(reject(format!("listed file is missing: {missing:?}")));
    }
    let manifest = strict_json(
        archive
            .files
            .get(MANIFEST)
            .ok_or_else(|| reject("toolgate-package.json is missing"))?,
    )
    .map_err(|e| reject(format!("toolgate-package.json: {e}")))?;
    let artifacts = manifest
        .get("artifacts")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    for artifact in &artifacts {
        let path = artifact
            .get("path")
            .and_then(Value::as_str)
            .ok_or_else(|| reject("manifest artifact has no path"))?;
        let hex = listed.get(path).ok_or_else(|| {
            reject(format!(
                "manifest artifact is not in CHECKSUMS.sha256: {path:?}"
            ))
        })?;
        if artifact.get("artifactDigest").and_then(Value::as_str) != Some(&format!("sha256:{hex}"))
        {
            return Err(reject(format!(
                "manifest artifact digest differs from CHECKSUMS.sha256: {path:?}"
            )));
        }
        if let Some(size) = artifact.get("sizeBytes") {
            if size.as_u64() != Some(archive.files[path].len() as u64) {
                return Err(reject(format!("manifest artifact size differs: {path:?}")));
            }
        }
    }
    Ok(Verified {
        package_digest: sha256_tagged(checksums),
        files: archive.files,
        manifest,
    })
}

/// Validates a manifest against `PackageManifest` v2 with the embedded canonical schemas.
pub fn validate_manifest_schema(manifest: &Value) -> Result<(), Error> {
    let errors = crate::schemas::errors("PackageManifest", manifest)?;
    if errors.is_empty() {
        Ok(())
    } else {
        Err(reject(format!(
            "toolgate-package.json does not match PackageManifest v2: {}",
            errors.join("; ")
        )))
    }
}

/// The export local name: the part of a `toolId` or `triggerId` after the package id.
fn local_name(tool_id: &str) -> Result<&str, Error> {
    tool_id
        .split_once('/')
        .map(|(_, name)| name)
        .ok_or_else(|| reject(format!("export id has no local name: {tool_id:?}")))
}

fn strings(value: &Value) -> Vec<&str> {
    value
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .collect()
}

/// The trigger rules beyond the schema (ADR 058; plan 5.7, 5.8, 6.6 and 6.7), so a descriptor the
/// processor would refuse is refused here too.
fn trigger_rules(trigger_id: &str, d: &Value) -> Result<(), Error> {
    let fail = |why: &str| Err(reject(format!("{trigger_id}: {why}")));
    let executors = strings(&d["executors"]);
    if executors.iter().collect::<BTreeSet<_>>().len() != executors.len() {
        return fail("executors are listed twice");
    }
    let capabilities = ["routes", "tcpDestinations", "secrets"]
        .iter()
        .any(|k| !strings(&d[*k]).is_empty());
    match d["kind"].as_str() {
        Some("CLIENT_TRIGGER") => {
            if executors != ["CLIENT"] {
                return fail("a client trigger runs on CLIENT executors only");
            }
            if capabilities {
                return fail("a client trigger has no routes, TCP destinations or secrets");
            }
            if strings(&d["permissions"])
                .iter()
                .any(|p| *p != "DEVICE_FILES_READ")
            {
                return fail("a client trigger may only read the device files area");
            }
        }
        _ => {
            if capabilities && executors.contains(&"CLIENT") {
                return fail("a server trigger that uses routes, TCP destinations or secrets cannot run on CLIENT executors");
            }
            if strings(&d["permissions"])
                .iter()
                .any(|p| p.starts_with("DEVICE_FILES"))
            {
                return fail("a server trigger has no device files area");
            }
        }
    }
    let schedule = &d["schedule"];
    match d["mode"].as_str() {
        Some("POLL") => {
            if schedule.get("intervalMs").is_none() || schedule.get("resyncIntervalMs").is_some() {
                return fail("a POLL trigger has schedule.intervalMs and no resyncIntervalMs");
            }
        }
        _ => {
            let resync = schedule.get("resyncIntervalMs").and_then(Value::as_u64);
            if schedule.get("intervalMs").is_some() || resync.is_none_or(|r| r != 0 && r < 60_000) {
                return fail("a PUSH trigger has schedule.resyncIntervalMs, 0 or at least 60000, and no intervalMs");
            }
        }
    }
    if d["overflow"].as_str() != Some("BLOCK") {
        if d["lossTolerant"].as_bool() != Some(true) {
            return fail("DROP_* overflow is allowed only for a lossTolerant event type");
        }
        if d["onTerminalRejection"].as_str() != Some("SKIP_AND_RECORD") {
            return fail("DROP_* overflow needs onTerminalRejection SKIP_AND_RECORD");
        }
    }
    let checkpoint = d["limits"]["maxCheckpointBytes"].as_u64().unwrap_or(0);
    if checkpoint > 16_384
        && (d["sourceReplay"].as_str() != Some("STATE_SNAPSHOT") || d.get("snapshot").is_none())
    {
        return fail(
            "maxCheckpointBytes above 16384 needs a STATE_SNAPSHOT source with a snapshot capacity",
        );
    }
    let tests = strings(&d["selfTests"]);
    if tests.iter().collect::<BTreeSet<_>>().len() != tests.len() {
        return fail("selfTests are listed twice");
    }
    Ok(())
}

fn str_at<'a>(value: &'a Value, key: &str) -> Result<&'a str, Error> {
    value
        .get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| reject(format!("{key} is missing")))
}

/// The archive rules plus the packager's full checks.
pub fn validate(data: &[u8]) -> Result<Report, Error> {
    let verified = verify(data)?;
    let manifest = &verified.manifest;
    validate_manifest_schema(manifest)?;
    let package_id = str_at(manifest, "id")?;
    // A generated MCP_PROXY package has no SDK block; its descriptors are generation 1.
    let generation = if manifest.get("kind").and_then(Value::as_str) == Some("MCP_PROXY") {
        Some(1)
    } else {
        manifest
            .pointer("/sdk/descriptorGeneration")
            .and_then(Value::as_u64)
    };
    let limits = manifest.get("limits").cloned().unwrap_or(Value::Null);
    let max_tools = limits
        .get("maxTools")
        .and_then(Value::as_u64)
        .unwrap_or(0)
        .min(MAX_TOOLS);
    let max_bytes = limits
        .get("maxDescriptorBytes")
        .and_then(Value::as_u64)
        .unwrap_or(0)
        .min(MAX_DESCRIPTOR_BYTES);

    let runtimes: BTreeSet<&str> = manifest["runtimes"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|r| r.get("runtimeId").and_then(Value::as_str))
        .collect();
    let java: BTreeSet<&str> = manifest["runtimes"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|r| r.get("language").and_then(Value::as_str) == Some("java"))
        .filter_map(|r| r.get("runtimeId").and_then(Value::as_str))
        .collect();
    let mut jars = BTreeSet::new();
    let mut artifact_ids = BTreeSet::new();
    let mut artifact_paths = BTreeSet::new();
    for artifact in manifest["artifacts"].as_array().into_iter().flatten() {
        let id = str_at(artifact, "artifactId")?;
        if artifact.get("mediaType").and_then(Value::as_str) == Some("application/java-archive") {
            jars.insert(id);
        }
        let path = str_at(artifact, "path")?;
        if !artifact_ids.insert(id) || !artifact_paths.insert(path) {
            return Err(reject(format!("duplicate artifact {id:?}")));
        }
        if !path.starts_with("artifacts/") {
            return Err(reject(format!(
                "artifact path must be under artifacts/: {path:?}"
            )));
        }
        if let Some(runtime) = artifact.get("runtimeId").and_then(Value::as_str) {
            if !runtimes.contains(runtime) {
                return Err(reject(format!(
                    "artifact names an undeclared runtime: {runtime:?}"
                )));
            }
        }
    }

    let tools = manifest["tools"].as_array().cloned().unwrap_or_default();
    if tools.len() as u64 > max_tools {
        return Err(reject(format!(
            "{} tools exceed maxTools {max_tools}",
            tools.len()
        )));
    }
    let mut digests = Vec::new();
    let mut tool_files = BTreeSet::new();
    for tool in &tools {
        let descriptor = &tool["descriptor"];
        let tool_id = str_at(descriptor, "toolId")?;
        if tool_id.split_once('/').map(|(p, _)| p) != Some(package_id) {
            return Err(reject(format!(
                "toolId {tool_id:?} is not in package {package_id:?}"
            )));
        }
        if descriptor
            .get("descriptorGeneration")
            .and_then(Value::as_u64)
            != generation
        {
            return Err(reject(format!(
                "{tool_id}: descriptorGeneration differs from sdk.descriptorGeneration"
            )));
        }
        let canonical = canonical_bytes(descriptor)?;
        if canonical.len() as u64 > max_bytes {
            return Err(reject(format!(
                "{tool_id}: descriptor is over maxDescriptorBytes"
            )));
        }
        let digest = tool_descriptor_digest(descriptor)?;
        if str_at(tool, "toolDescriptorDigest")? != digest {
            return Err(reject(format!(
                "{tool_id}: toolDescriptorDigest does not match the descriptor"
            )));
        }
        let file = format!("tools/{}.json", local_name(tool_id)?);
        if verified.files.get(&file) != Some(&canonical) {
            return Err(reject(format!(
                "{file} is missing or not the canonical descriptor"
            )));
        }
        tool_files.insert(file);
        for id in tool["artifactIds"].as_array().into_iter().flatten() {
            if !id.as_str().is_some_and(|id| artifact_ids.contains(id)) {
                return Err(reject(format!("{tool_id}: unknown artifact {id}")));
            }
        }
        if let Some(runtime) = tool.get("runtimeId").and_then(Value::as_str) {
            if !runtimes.contains(runtime) {
                return Err(reject(format!("{tool_id}: undeclared runtime {runtime:?}")));
            }
            // A Java tool is one self-contained jar, so a device needs only a JRE to run it.
            if java.contains(runtime) {
                match tool["artifactIds"].as_array().map(Vec::as_slice) {
                    Some([only]) if only.as_str().is_some_and(|id| jars.contains(id)) => {}
                    _ => {
                        return Err(reject(format!(
                            "{tool_id}: a Java tool must run from one self-contained jar"
                        )))
                    }
                }
            }
        }
        digests.push((tool_id.to_owned(), digest));
    }
    if let Some(extra) = verified
        .files
        .keys()
        .find(|p| p.starts_with("tools/") && !tool_files.contains(*p))
    {
        return Err(reject(format!("{extra} matches no tool in the manifest")));
    }

    // Trigger exports (ADR 058): the same digest rules as tools, in one export namespace.
    let tool_names: BTreeSet<String> = digests
        .iter()
        .filter_map(|(id, _)| local_name(id).ok().map(str::to_owned))
        .collect();
    let mut trigger_digests = Vec::new();
    let mut trigger_files = BTreeSet::new();
    for trigger in manifest["triggers"].as_array().into_iter().flatten() {
        let descriptor = &trigger["descriptor"];
        let trigger_id = str_at(descriptor, "triggerId")?;
        if trigger_id.split_once('/').map(|(p, _)| p) != Some(package_id) {
            return Err(reject(format!(
                "triggerId {trigger_id:?} is not in package {package_id:?}"
            )));
        }
        let local = local_name(trigger_id)?;
        if tool_names.contains(local) {
            return Err(reject(format!(
                "{trigger_id}: a tool has the same name; tools and triggers share one namespace"
            )));
        }
        let canonical = canonical_bytes(descriptor)?;
        if canonical.len() as u64 > max_bytes {
            return Err(reject(format!(
                "{trigger_id}: descriptor is over maxDescriptorBytes"
            )));
        }
        let digest = tool_descriptor_digest(descriptor)?;
        if str_at(trigger, "triggerDescriptorDigest")? != digest {
            return Err(reject(format!(
                "{trigger_id}: triggerDescriptorDigest does not match the descriptor"
            )));
        }
        let file = format!("triggers/{local}.json");
        if verified.files.get(&file) != Some(&canonical) {
            return Err(reject(format!(
                "{file} is missing or not the canonical descriptor"
            )));
        }
        trigger_files.insert(file);
        for id in trigger["artifactIds"].as_array().into_iter().flatten() {
            if !id.as_str().is_some_and(|id| artifact_ids.contains(id)) {
                return Err(reject(format!("{trigger_id}: unknown artifact {id}")));
            }
        }
        let runtime = str_at(trigger, "runtimeId")?;
        if !runtimes.contains(runtime) {
            return Err(reject(format!(
                "{trigger_id}: undeclared runtime {runtime:?}"
            )));
        }
        if java.contains(runtime) {
            match trigger["artifactIds"].as_array().map(Vec::as_slice) {
                Some([only]) if only.as_str().is_some_and(|id| jars.contains(id)) => {}
                _ => {
                    return Err(reject(format!(
                        "{trigger_id}: a Java trigger must run from one self-contained jar"
                    )))
                }
            }
        }
        trigger_rules(trigger_id, descriptor)?;
        trigger_digests.push((trigger_id.to_owned(), digest));
    }
    if let Some(extra) = verified
        .files
        .keys()
        .find(|p| p.starts_with("triggers/") && !trigger_files.contains(*p))
    {
        return Err(reject(format!(
            "{extra} matches no trigger in the manifest"
        )));
    }
    let set = export_set_digest(digests.clone(), trigger_digests.clone())
        .map_err(|e| reject(e.to_string()))?;
    if str_at(manifest, "descriptorSetDigest")? != set {
        return Err(reject("descriptorSetDigest does not match the exports"));
    }
    Ok(Report {
        package_digest: verified.package_digest,
        descriptor_set_digest: set,
        tools: digests,
        triggers: trigger_digests,
    })
}

/// Fills a computed value, refusing to overwrite a different one already present.
fn fill(object: &mut Map<String, Value>, key: &str, value: Value, what: &str) -> Result<(), Error> {
    match object.get(key) {
        Some(existing) if *existing != value => Err(Error::Invalid(format!(
            "{what}: {key} is {existing}, but the packaged content gives {value}"
        ))),
        _ => {
            object.insert(key.into(), value);
            Ok(())
        }
    }
}

fn read_regular(dir: &Path, relative: &str) -> Result<Vec<u8>, Error> {
    check_name(relative).map_err(|e| Error::Invalid(e.to_string()))?;
    let path = dir.join(relative);
    let meta = std::fs::symlink_metadata(&path)?;
    if !meta.is_file() {
        return Err(Error::Invalid(format!("{relative} is not a regular file")));
    }
    Ok(std::fs::read(path)?)
}

/// Builds a package from a staging directory holding `toolgate-package.json` and the artifacts it
/// lists. The packager computes `toolDescriptorDigest`, `descriptorSetDigest`, `artifactDigest`
/// and `sizeBytes`, and `triggerDescriptorDigest` per trigger; a value already present must match.
/// It writes `tools/<local name>.json` and `triggers/<local name>.json` with each canonical
/// descriptor, copies the optional marketplace, self-test and SBOM files, and returns the archive
/// bytes after validating them.
pub fn pack(dir: &Path) -> Result<(Vec<u8>, Report), Error> {
    let mut manifest = strict_json(&read_regular(dir, MANIFEST)?)?;
    let mut files = BTreeMap::new();
    let object = manifest
        .as_object_mut()
        .ok_or_else(|| Error::Invalid("toolgate-package.json is not an object".into()))?;
    if let Some(Value::Array(artifacts)) = object.get_mut("artifacts") {
        for artifact in artifacts {
            let entry = artifact
                .as_object_mut()
                .ok_or_else(|| Error::Invalid("artifact is not an object".into()))?;
            let path = entry
                .get("path")
                .and_then(Value::as_str)
                .ok_or_else(|| Error::Invalid("artifact has no path".into()))?
                .to_owned();
            let bytes = read_regular(dir, &path)?;
            fill(
                entry,
                "artifactDigest",
                Value::String(sha256_tagged(&bytes)),
                &path,
            )?;
            fill(entry, "sizeBytes", Value::from(bytes.len() as u64), &path)?;
            files.insert(path, bytes);
        }
    }
    let mut digests = Vec::new();
    if let Some(Value::Array(tools)) = object.get_mut("tools") {
        for tool in tools {
            let descriptor = tool
                .get("descriptor")
                .cloned()
                .ok_or_else(|| Error::Invalid("tool has no descriptor".into()))?;
            let tool_id = descriptor
                .get("toolId")
                .and_then(Value::as_str)
                .ok_or_else(|| Error::Invalid("descriptor has no toolId".into()))?
                .to_owned();
            let digest = tool_descriptor_digest(&descriptor)?;
            let entry = tool
                .as_object_mut()
                .ok_or_else(|| Error::Invalid("tool is not an object".into()))?;
            fill(
                entry,
                "toolDescriptorDigest",
                Value::String(digest.clone()),
                &tool_id,
            )?;
            let local = local_name(&tool_id).map_err(|e| Error::Invalid(e.to_string()))?;
            files.insert(format!("tools/{local}.json"), canonical_bytes(&descriptor)?);
            digests.push((tool_id, digest));
        }
    }
    let mut trigger_digests = Vec::new();
    if let Some(Value::Array(triggers)) = object.get_mut("triggers") {
        for trigger in triggers {
            let descriptor = trigger
                .get("descriptor")
                .cloned()
                .ok_or_else(|| Error::Invalid("trigger has no descriptor".into()))?;
            let trigger_id = descriptor
                .get("triggerId")
                .and_then(Value::as_str)
                .ok_or_else(|| Error::Invalid("trigger descriptor has no triggerId".into()))?
                .to_owned();
            let digest = tool_descriptor_digest(&descriptor)?;
            let entry = trigger
                .as_object_mut()
                .ok_or_else(|| Error::Invalid("trigger is not an object".into()))?;
            fill(
                entry,
                "triggerDescriptorDigest",
                Value::String(digest.clone()),
                &trigger_id,
            )?;
            let local = local_name(&trigger_id).map_err(|e| Error::Invalid(e.to_string()))?;
            files.insert(
                format!("triggers/{local}.json"),
                canonical_bytes(&descriptor)?,
            );
            trigger_digests.push((trigger_id, digest));
        }
    }
    let set = export_set_digest(digests, trigger_digests)?;
    fill(object, "descriptorSetDigest", Value::String(set), MANIFEST)?;
    let mut manifest_bytes = canonical_bytes(&manifest)?;
    manifest_bytes.push(b'\n');
    files.insert(MANIFEST.into(), manifest_bytes);
    for optional in OPTIONAL {
        if dir.join(optional).exists() {
            files.insert(optional.into(), read_regular(dir, optional)?);
        }
    }
    check_collisions(files.keys().map(String::as_str))
        .map_err(|e| Error::Invalid(e.to_string()))?;
    let checksums = render_checksums(&files);
    files.insert(CHECKSUMS.into(), checksums);
    let data = archive::write(&files)?;
    let report = validate(&data)?;
    Ok((data, report))
}
