// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Embeds the `toolgate init` starters (starters/README.md): every file under `starters/` except
//! its README.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};

fn main() {
    let manifest = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let starters = manifest.join("starters");
    println!("cargo:rerun-if-changed={}", starters.display());
    let mut files = Vec::new();
    walk(&starters, &starters, &mut files);
    files.sort();
    let mut out = String::from("/// Starter files: (path under starters/, contents, executable).\npub static FILES: &[(&str, &[u8], bool)] = &[\n");
    for relative in &files {
        entry(
            &mut out,
            relative,
            &starters.join(relative).canonicalize().unwrap(),
        );
    }
    out.push_str("];\n");
    std::fs::write(
        PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("starters.rs"),
        out,
    )
    .unwrap();
}

fn entry(out: &mut String, name: &str, source: &Path) {
    writeln!(
        out,
        "    ({name:?}, include_bytes!({:?}), {}),",
        source.display().to_string(),
        executable(source)
    )
    .unwrap();
}

#[cfg(unix)]
fn executable(path: &Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(path).unwrap().permissions().mode() & 0o111 != 0
}

#[cfg(not(unix))]
fn executable(path: &Path) -> bool {
    path.file_name()
        .is_some_and(|n| n == "gradlew" || n == "mvnw")
}

fn walk(base: &Path, dir: &Path, files: &mut Vec<String>) {
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            walk(base, &path, files);
        } else if path
            .file_name()
            .is_some_and(|n| n != "README.md" || path.parent() != Some(base))
        {
            files.push(
                path.strip_prefix(base)
                    .unwrap()
                    .to_str()
                    .unwrap()
                    .replace('\\', "/"),
            );
        }
    }
}
