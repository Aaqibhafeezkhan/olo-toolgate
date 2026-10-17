// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Fixed runtime vectors. Caller input is never interpolated into a command or option.
use crate::{Failure, Result};
use olo_toolgate_contracts::{LocalRuntimeKind as Kind, LocalToolRegistration};

/// The Java SDK runtime's entry point for governed invocations over tool protocol v2.
pub const JAVA_SDK_MAIN: &str = "io.ololabs.toolgate.runtime.Main";

/// The runtime's executable inside the pinned image, never the host PATH.
pub fn executable(kind: &Kind) -> Result<&'static str> {
    match kind {
        Kind::JavaSdk => Ok("/opt/java/openjdk/bin/java"),
        Kind::PythonSdk => Ok("/usr/local/bin/python3"),
    }
}

/// The version probe; the reported version must equal the managed runtime's exactly.
pub fn version_arguments(kind: &Kind) -> Result<Vec<String>> {
    if matches!(kind, Kind::PythonSdk) {
        return Ok(vec![
            executable(kind)?.into(),
            "-I".into(),
            "--version".into(),
        ]);
    }
    Ok(vec![
        executable(kind)?.into(),
        "-XX:ActiveProcessorCount=2".into(),
        "-XX:-UsePerfData".into(),
        "-version".into(),
    ])
}

/// The launch vector for a registration: fixed per runtime, never interpolated from input.
pub fn invocation_arguments(kind: &Kind, tool: &LocalToolRegistration) -> Result<Vec<String>> {
    let entry = &tool.entry_point;
    if !entry.starts_with("/opt/tool/")
        || entry.len() > 256
        || entry
            .split('/')
            .skip(3)
            .any(|s| s.is_empty() || s == "." || s == ".." || s.starts_with('.'))
        || !entry
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"/._-".contains(&b))
    {
        return Err(Failure::Validation);
    }
    let mut args: Vec<String> = match kind {
        // A Java SDK package artifact runs the SDK runtime's governed mode (ADR 026); the class is fixed, never the caller's.
        Kind::JavaSdk if entry.ends_with(".jar") => vec![
            executable(kind)?.into(),
            format!("-Xmx{}m", tool.limits.memory_mi_b / 2),
            "-XX:ActiveProcessorCount=2".into(),
            "-XX:-UsePerfData".into(),
            "-XX:+UseSerialGC".into(),
            "-cp".into(),
            entry.clone(),
            JAVA_SDK_MAIN.into(),
        ],
        Kind::JavaSdk => return Err(Failure::Validation),
        Kind::PythonSdk if entry.ends_with(".pyz") => {
            vec![executable(kind)?.into(), "-I".into(), entry.clone()]
        }
        Kind::PythonSdk => return Err(Failure::Validation),
    };
    // env -i also removes environment baked into a reviewed image. Nothing of the caller's environment is forwarded.
    let mut clean = vec![
        "/usr/bin/env".into(),
        "-i".into(),
        "PATH=/usr/local/bin:/usr/bin:/bin".into(),
        "HOME=/work".into(),
        "TMPDIR=/tmp".into(),
        "LANG=C.UTF-8".into(),
        "TZ=UTC".into(),
        "TOOLGATE_PROTOCOL=2".into(),
        "TOOLGATE_EXECUTION_TARGET=CLIENT".into(),
    ];
    clean.append(&mut args);
    Ok(clean)
}

/// Version output is redacted; only an exact semantic version on the first
/// nonempty line is accepted, not substring matching (3.1 cannot match 3.10).
pub fn verify_version(bytes: &[u8], expected: &str) -> Result<()> {
    let expected = semver::Version::parse(expected).map_err(|_| Failure::Validation)?;
    let line = std::str::from_utf8(bytes)
        .map_err(|_| Failure::Validation)?
        .lines()
        .find(|l| !l.trim().is_empty())
        .ok_or(Failure::Unavailable)?;
    let matched = line
        .split_whitespace()
        .filter_map(|v| {
            let token = v
                .trim_matches(['"', '\'', ',', '(', ')'])
                .trim_start_matches('v')
                .split('(')
                .next()
                .unwrap_or("");
            // Java's fourth numeric component is preserved as SemVer build metadata.
            let parts: Vec<_> = token.split('.').collect();
            let normalized = if parts.len() == 4
                && parts
                    .iter()
                    .all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))
            {
                format!("{}.{}.{}+{}", parts[0], parts[1], parts[2], parts[3])
            } else {
                token.to_owned()
            };
            semver::Version::parse(&normalized).ok()
        })
        .any(|v| v == expected);
    if !matched {
        return Err(Failure::Unsupported);
    }
    Ok(())
}
