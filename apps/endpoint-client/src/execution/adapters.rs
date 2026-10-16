// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Fixed runtime vectors. Caller input is never interpolated into a command or option.
use crate::{Failure, Result};
use olo_toolgate_contracts::{LocalRuntimeKind as Kind, LocalToolRegistration};

/// Interpreter discovery is inside a pinned image, never the host PATH.
pub fn executable(kind: &Kind) -> Result<&'static str> {
    match kind {
        Kind::Python => Ok("/usr/local/bin/python3"),
        Kind::Node => Ok("/usr/local/bin/node"),
        Kind::Powershell => Ok("/usr/bin/pwsh"),
        Kind::Shell => Ok("/bin/bash"),
        Kind::JavaJar => Ok("/opt/java/openjdk/bin/java"),
        Kind::Dotnet => Ok("/usr/share/dotnet/dotnet"),
        Kind::Native => Ok("/opt/tool/run"),
        Kind::Batch | Kind::Wasm => Err(Failure::Unsupported),
    }
}

/// Native programs expose --version; future WASM/Windows backends must implement
/// the same capability contract before being enabled, not fall back to the host.
pub fn version_arguments(kind: &Kind) -> Result<Vec<String>> {
    let mut args = vec![executable(kind)?.into()];
    if *kind == Kind::JavaJar {
        args.extend([
            "-XX:ActiveProcessorCount=2".into(),
            "-XX:-UsePerfData".into(),
            "-version".into(),
        ]);
    } else {
        args.push(
            if *kind == Kind::Dotnet {
                "--list-runtimes"
            } else {
                "--version"
            }
            .into(),
        );
    }
    Ok(args)
}

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
        Kind::Native if entry == "/opt/tool/run" => vec![entry.clone()],
        Kind::Native => return Err(Failure::Validation),
        Kind::Python if entry.ends_with(".py") => vec![
            executable(kind)?.into(),
            "-I".into(),
            "-B".into(),
            entry.clone(),
        ],
        Kind::Node if entry.ends_with(".mjs") || entry.ends_with(".js") => vec![
            executable(kind)?.into(),
            "--no-addons".into(),
            "--no-warnings".into(),
            "--".into(),
            entry.clone(),
        ],
        Kind::Powershell if entry.ends_with(".ps1") => vec![
            executable(kind)?.into(),
            "-NoLogo".into(),
            "-NoProfile".into(),
            "-NonInteractive".into(),
            "-File".into(),
            entry.clone(),
        ],
        Kind::Shell if entry.ends_with(".sh") => vec![
            executable(kind)?.into(),
            "--noprofile".into(),
            "--norc".into(),
            entry.clone(),
        ],
        Kind::JavaJar if entry.ends_with(".jar") => vec![
            executable(kind)?.into(),
            format!("-Xmx{}m", tool.limits.memory_mi_b / 2),
            "-XX:ActiveProcessorCount=2".into(),
            "-XX:-UsePerfData".into(),
            "-jar".into(),
            entry.clone(),
        ],
        Kind::Dotnet if entry.ends_with(".dll") => {
            vec![executable(kind)?.into(), "exec".into(), entry.clone()]
        }
        Kind::Batch | Kind::Wasm => return Err(Failure::Unsupported),
        _ => return Err(Failure::Validation),
    };
    // env -i also removes environment baked into a reviewed image. No caller
    // environment, PYTHONPATH, NODE_OPTIONS, proxy or credential is forwarded.
    let mut clean = vec![
        "/usr/bin/env".into(),
        "-i".into(),
        "PATH=/usr/local/bin:/usr/bin:/bin".into(),
        "HOME=/work".into(),
        "TMPDIR=/tmp".into(),
        "LANG=C.UTF-8".into(),
        "TZ=UTC".into(),
        "DOTNET_EnableDiagnostics=0".into(),
        "DOTNET_PROCESSOR_COUNT=2".into(),
        "DOTNET_CLI_TELEMETRY_OPTOUT=1".into(),
        "DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1".into(),
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
