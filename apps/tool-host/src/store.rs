// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The content store (README.md, "Content store"): packages, service profiles and globals, each
//! named by the digest the permit pins. Nothing is found by name or version, so a host runs
//! exactly what Control approved or refuses with `DIGEST_NOT_PRESENT`.
//!
//! A package is verified with the archive rules and the packager's checks once, then unpacked
//! under `workDir/packages/<hex>/` for its runtime. A later invocation of the same digest reuses
//! that directory; it is written to a temporary name and renamed, so a half-written one is never
//! used.

use crate::digest;
use olo_toolgate_contracts::v2::{
    PermitTarget, RouteDeclaration, RuntimeDeclaration, ServiceProfile, ToolDefinition,
};
use serde_json::Value;
use std::collections::BTreeMap;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use toolgate_cli::package;

/// Why an invocation's content cannot be prepared.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StoreError {
    /// A pinned digest is not in the store, or what is there does not match it.
    NotPresent(String),
    /// The store could not be read or written.
    Unavailable(String),
    /// The tool's runtime is not one this host runs.
    Unsupported(String),
}

/// Everything one invocation runs, all of it matched against the permit.
#[derive(Debug, Clone)]
pub struct Prepared {
    pub tool: ToolDefinition,
    pub runtime: RuntimeDeclaration,
    /// The tool's one self-contained executable in the unpacked package: for the JVM, a fat jar.
    pub executable: PathBuf,
    pub profile: ServiceProfile,
    pub globals: BTreeMap<String, Value>,
    /// The permit's routes, from the service profile.
    pub routes: Vec<RouteDeclaration>,
}

pub struct Store {
    store_dir: PathBuf,
    work_dir: PathBuf,
}

/// Runtimes this host can launch, by `runtimeId`.
pub const RUNTIMES: [&str; 2] = ["jvm", "python"];

fn not_present(why: impl Into<String>) -> StoreError {
    StoreError::NotPresent(why.into())
}

/// The hex part of a `sha256:` digest, or none for anything else.
pub fn hex(digest: &str) -> Option<&str> {
    let hex = digest.strip_prefix("sha256:")?;
    (hex.len() == 64
        && hex
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)))
    .then_some(hex)
}

fn read(path: &Path, what: &str) -> Result<Vec<u8>, StoreError> {
    std::fs::read(path).map_err(|e| match e.kind() {
        ErrorKind::NotFound => not_present(format!("{what} is not in the store")),
        _ => StoreError::Unavailable(format!("{}: {e}", path.display())),
    })
}

impl Store {
    pub fn new(store_dir: PathBuf, work_dir: PathBuf) -> Store {
        Store {
            store_dir,
            work_dir,
        }
    }

    /// Finds and checks everything `target` pins.
    pub fn prepare(&self, target: &PermitTarget) -> Result<Prepared, StoreError> {
        let package_hex = hex(&target.package_digest)
            .ok_or_else(|| not_present("the package digest is not sha256"))?;
        let (package_dir, manifest) = self.package(package_hex, &target.package_digest)?;

        let tool = manifest["tools"]
            .as_array()
            .into_iter()
            .flatten()
            .find(|t| {
                t.pointer("/descriptor/toolId").and_then(Value::as_str)
                    == Some(target.tool_id.as_str())
            })
            .ok_or_else(|| not_present(format!("the package has no tool {}", target.tool_id)))?;
        if tool.pointer("/descriptor/kind").and_then(Value::as_str) != Some("SERVER") {
            return Err(not_present("a Tool Host can only execute SERVER tools"));
        }
        let tool: ToolDefinition = serde_json::from_value(tool.clone())
            .map_err(|e| not_present(format!("the tool definition is invalid: {e}")))?;
        if tool.tool_descriptor_digest != target.tool_descriptor_digest {
            return Err(not_present(
                "the tool descriptor digest differs from the permit",
            ));
        }

        let runtime_id = tool
            .runtime_id
            .as_deref()
            .ok_or_else(|| not_present("the tool has no runtime"))?;
        let runtime = manifest["runtimes"]
            .as_array()
            .into_iter()
            .flatten()
            .find(|r| r.get("runtimeId").and_then(Value::as_str) == Some(runtime_id))
            .ok_or_else(|| not_present(format!("the package declares no runtime {runtime_id}")))?;
        if digest::canonical(runtime).map_err(not_present)? != target.runtime_digest {
            return Err(not_present("the runtime digest differs from the permit"));
        }
        let runtime: RuntimeDeclaration = serde_json::from_value(runtime.clone())
            .map_err(|e| not_present(format!("the runtime is invalid: {e}")))?;
        if !RUNTIMES.contains(&runtime.runtime_id.as_str()) {
            return Err(StoreError::Unsupported(format!(
                "this host does not run runtime {}",
                runtime.runtime_id
            )));
        }

        // A tool runs from one self-contained artifact, so a device needs nothing but the runtime.
        let [entry] = tool.artifact_ids.as_slice() else {
            return Err(not_present(
                "the tool must run from one self-contained artifact",
            ));
        };
        let artifact = manifest["artifacts"]
            .as_array()
            .into_iter()
            .flatten()
            .find(|a| a.get("artifactId").and_then(Value::as_str) == Some(entry.as_str()))
            .ok_or_else(|| not_present(format!("the package has no artifact {entry}")))?;
        if artifact.get("artifactDigest").and_then(Value::as_str)
            != Some(target.artifact_digest.as_str())
        {
            return Err(not_present(
                "the entry artifact digest differs from the permit",
            ));
        }
        if runtime.runtime_id == "jvm"
            && artifact.get("mediaType").and_then(Value::as_str) != Some("application/java-archive")
        {
            return Err(not_present("a JVM tool must run from one jar"));
        }
        if runtime.runtime_id == "python"
            && (artifact.get("mediaType").and_then(Value::as_str) != Some("application/zip")
                || !artifact
                    .get("path")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .ends_with(".pyz")
                || tool.entry_point.as_deref() != Some("toolgate.runtime"))
        {
            return Err(not_present(
                "a Python tool must run the SDK runtime from one pyz",
            ));
        }
        let executable = package_dir.join(artifact_name(
            artifact
                .get("path")
                .and_then(Value::as_str)
                .unwrap_or_default(),
        ));

        let profile = self.document(
            "profiles",
            &target.service_profile_digest,
            "the service profile",
        )?;
        let profile: ServiceProfile = serde_json::from_value(profile)
            .map_err(|e| not_present(format!("the service profile is invalid: {e}")))?;
        if profile.revision != target.service_profile_revision {
            return Err(not_present(
                "the service profile revision differs from the permit",
            ));
        }
        if manifest.get("id").and_then(Value::as_str) != Some(profile.package_id.as_str()) {
            return Err(not_present("the service profile is for another package"));
        }
        let mut routes = Vec::new();
        for id in &target.routes {
            if !tool.descriptor.routes.iter().any(|r| &r.id == id) {
                return Err(not_present(format!(
                    "the permit grants route {id}, which the tool does not declare"
                )));
            }
            let route = profile.routes.iter().find(|r| &r.id == id).ok_or_else(|| {
                not_present(format!("the service profile does not approve route {id}"))
            })?;
            routes.push(route.clone());
        }

        let globals = match self.document("globals", &target.globals_digest, "the globals")? {
            Value::Object(map) => map.into_iter().collect(),
            _ => return Err(not_present("the globals document is not an object")),
        };
        Ok(Prepared {
            tool,
            runtime,
            executable,
            profile,
            globals,
            routes,
        })
    }

    /// A stored JSON document whose canonical digest is `digest`.
    fn document(&self, kind: &str, digest: &str, what: &str) -> Result<Value, StoreError> {
        let hex = hex(digest).ok_or_else(|| not_present(format!("{what} digest is not sha256")))?;
        let bytes = read(&self.store_dir.join(kind).join(format!("{hex}.json")), what)?;
        let value = toolgate_cli::strict_json(&bytes)
            .map_err(|e| not_present(format!("{what} is not JSON: {e}")))?;
        if digest::canonical(&value).map_err(not_present)? != digest {
            return Err(not_present(format!(
                "{what} in the store does not match its digest"
            )));
        }
        Ok(value)
    }

    /// The unpacked package and its manifest, verifying and unpacking it on first use.
    fn package(&self, hex: &str, digest: &str) -> Result<(PathBuf, Value), StoreError> {
        let packages = self.work_dir.join("packages");
        let dir = packages.join(hex);
        let manifest_path = dir.join(package::MANIFEST);
        if let Ok(bytes) = std::fs::read(&manifest_path) {
            let manifest = serde_json::from_slice(&bytes).map_err(|e| {
                StoreError::Unavailable(format!("{}: {e}", manifest_path.display()))
            })?;
            return Ok((dir, manifest));
        }
        let data = read(
            &self.store_dir.join("packages").join(format!("{hex}.tgpkg")),
            "the package",
        )?;
        let report = package::validate(&data)
            .map_err(|e| not_present(format!("the stored package is {e}")))?;
        if report.package_digest != digest {
            return Err(not_present("the stored package does not match its digest"));
        }
        let verified = package::verify(&data)
            .map_err(|e| not_present(format!("the stored package is {e}")))?;
        let unavailable =
            |e: std::io::Error| StoreError::Unavailable(format!("{}: {e}", packages.display()));
        std::fs::create_dir_all(&packages).map_err(unavailable)?;
        let staging = packages.join(format!(".{hex}.{}", crate::random_hex(8)));
        let result = unpack(&verified, &staging).and_then(|()| {
            match std::fs::rename(&staging, &dir) {
                Ok(()) => Ok(()),
                // Another invocation unpacked the same digest first; its copy is identical.
                Err(_) if manifest_path.is_file() => Ok(()),
                Err(e) => Err(unavailable(e)),
            }
        });
        let _ = std::fs::remove_dir_all(&staging);
        result?;
        Ok((dir, verified.manifest))
    }
}

/// The file name an artifact is unpacked under.
fn artifact_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

/// Lays out a verified package for its runtime: every artifact under its file name. The manifest
/// is written last; it marks the directory complete.
fn unpack(verified: &package::Verified, dir: &Path) -> Result<(), StoreError> {
    let io = |e: std::io::Error| StoreError::Unavailable(format!("{}: {e}", dir.display()));
    std::fs::create_dir_all(dir).map_err(io)?;
    for artifact in verified.manifest["artifacts"]
        .as_array()
        .into_iter()
        .flatten()
    {
        let path = artifact["path"].as_str().unwrap_or_default();
        std::fs::write(dir.join(artifact_name(path)), &verified.files[path]).map_err(io)?;
    }
    let manifest = serde_json::to_vec(&verified.manifest)
        .map_err(|e| StoreError::Unavailable(e.to_string()))?;
    std::fs::write(dir.join(package::MANIFEST), manifest).map_err(io)
}

#[cfg(test)]
mod tests {
    #[test]
    fn only_sha256_hex_names_a_file() {
        let good = format!("sha256:{}", "a".repeat(64));
        assert_eq!(super::hex(&good), Some("a".repeat(64).as_str()));
        assert_eq!(super::hex("sha256:../../etc/passwd"), None);
        assert_eq!(super::hex(&format!("sha256:{}", "A".repeat(64))), None);
        assert_eq!(super::hex(&format!("sha512:{}", "a".repeat(64))), None);
    }
}
