// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! `toolgate init`: a new tool project from a starter. A starter is a stack of layers under
//! `starters/` (a language's common files, then its build tool's), embedded by `build.rs`; see
//! starters/README.md for the format and how to add a language.

use crate::Error;
use std::path::{Path, PathBuf};

include!(concat!(env!("OUT_DIR"), "/starters.rs"));

/// One starter: a language and build tool, and the layers it is made of, in order.
pub struct Starter {
    pub language: &'static str,
    pub build: &'static str,
    layers: &'static [&'static str],
    /// Printed after the project is written when no SDK repository was given.
    pub local_sdk_note: &'static str,
}

const JAVA_LOCAL_SDK: &str = "The project reads the SDK from your local Maven repository until it is published; from a ToolGate checkout, run ./gradlew publishJavaSdkToMavenLocal first.";

/// Every starter; the first one for a language is its default build tool.
pub const STARTERS: &[Starter] = &[
    Starter {
        language: "java",
        build: "gradle",
        layers: &["java/common", "java/gradle"],
        local_sdk_note: JAVA_LOCAL_SDK,
    },
    Starter {
        language: "java",
        build: "maven",
        layers: &["java/common", "java/maven"],
        local_sdk_note: JAVA_LOCAL_SDK,
    },
    Starter {
        language: "python",
        build: "python",
        layers: &["python/common"],
        local_sdk_note:
            "Install the Python SDK from the checkout: python -m pip install -e tool-sdk/python-sdk",
    },
];

/// What `toolgate init` was asked for.
pub struct Options {
    /// The project name: lower case letters, digits and hyphens, starting with a letter.
    pub name: String,
    pub language: String,
    /// The build tool; the language's default when absent.
    pub build: Option<String>,
    /// The package id, such as `acme.orders`; `example.<name>` when absent.
    pub package_id: Option<String>,
    pub publisher: Option<String>,
    pub sdk_version: String,
    /// A repository holding the SDK (a URL or a directory); the local Maven repository when absent.
    pub sdk_repository: Option<String>,
}

/// Finds the starter for a language and optional build tool.
pub fn starter(language: &str, build: Option<&str>) -> Result<&'static Starter, Error> {
    let mut candidates = STARTERS
        .iter()
        .filter(|s| s.language == language)
        .peekable();
    if candidates.peek().is_none() {
        let languages: Vec<&str> = STARTERS.iter().map(|s| s.language).collect();
        return Err(Error::Invalid(format!(
            "no starter for language {language}; available: {}",
            dedup(languages).join(", ")
        )));
    }
    let candidates: Vec<&Starter> = candidates.collect();
    match build {
        None => Ok(candidates[0]),
        Some(build) => candidates
            .iter()
            .copied()
            .find(|s| s.build == build)
            .ok_or_else(|| {
                let builds: Vec<&str> = candidates.iter().map(|s| s.build).collect();
                Error::Invalid(format!(
                    "no {language} starter for {build}; available: {}",
                    builds.join(", ")
                ))
            }),
    }
}

fn dedup(mut values: Vec<&str>) -> Vec<&str> {
    values.dedup();
    values
}

/// Writes a new project into `dir`, which must be missing or empty, and returns the files written.
pub fn init(dir: &Path, options: &Options) -> Result<Vec<PathBuf>, Error> {
    let starter = starter(&options.language, options.build.as_deref())?;
    let variables = variables(starter, options)?;
    if dir.exists() && std::fs::read_dir(dir)?.next().is_some() {
        return Err(Error::Invalid(format!(
            "{} already exists and is not empty",
            dir.display()
        )));
    }
    let mut written = Vec::new();
    for layer in starter.layers {
        let prefix = format!("{layer}/");
        for (path, bytes, executable) in FILES {
            let Some(relative) = path.strip_prefix(&prefix) else {
                continue;
            };
            let (relative, contents) = match relative.strip_suffix(".tmpl") {
                Some(name) => (
                    name.to_owned(),
                    expand(std::str::from_utf8(bytes).unwrap(), &variables, path)?.into_bytes(),
                ),
                None => (relative.to_owned(), bytes.to_vec()),
            };
            let target = dir.join(expand_path(&relative, &variables));
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)?;
            }
            std::fs::write(&target, contents)?;
            if *executable {
                make_executable(&target)?;
            }
            written.push(target);
        }
    }
    Ok(written)
}

#[cfg(unix)]
fn make_executable(path: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755))
}

#[cfg(not(unix))]
fn make_executable(_: &Path) -> std::io::Result<()> {
    Ok(())
}

/// The values the starter's templates use: `{{name}}` in contents, `__NAME__` in paths.
fn variables(starter: &Starter, options: &Options) -> Result<Vec<(&'static str, String)>, Error> {
    let name = &options.name;
    if !is_name(name) {
        return Err(Error::Invalid(format!(
            "project name {name:?} must be 2 to 31 lower case letters, digits and hyphens, starting with a letter"
        )));
    }
    let package_id = options
        .package_id
        .clone()
        .unwrap_or_else(|| format!("example.{name}"));
    let segments: Vec<&str> = package_id.split('.').collect();
    if !(2..=4).contains(&segments.len()) || !segments.iter().all(|s| is_name(s)) {
        return Err(Error::Invalid(format!(
            "package id {package_id:?} must be 2 to 4 dot-separated segments of lower case letters, digits and hyphens, such as acme.orders"
        )));
    }
    let words: Vec<String> = name.split('-').map(capitalize).collect();
    let mut values = vec![
        ("name", name.clone()),
        ("title", words.join(" ")),
        ("packageId", package_id.clone()),
        (
            "publisher",
            options
                .publisher
                .clone()
                .unwrap_or_else(|| "Example".into()),
        ),
        ("sdkVersion", options.sdk_version.clone()),
    ];
    let repository = options.sdk_repository.as_deref().map(repository_url);
    match starter.language {
        "java" => {
            let java_package = package_id.replace('-', "_");
            values.push(("packagePath", java_package.replace('.', "/")));
            values.push(("className", format!("{}Tools", words.concat())));
            values.push(("group", java_package.clone()));
            values.push(("javaPackage", java_package));
            let (gradle_plugins, gradle) = match &repository {
                Some(url) => (
                    format!("        maven {{ url = uri(\"{url}\") }}\n"),
                    format!("    maven {{ url = uri(\"{url}\") }}\n"),
                ),
                None => ("        mavenLocal()\n".into(), "    mavenLocal()\n".into()),
            };
            values.push(("gradlePluginRepositories", gradle_plugins));
            values.push(("gradleRepositories", gradle));
            values.push((
                "mavenRepositories",
                match &repository {
                    Some(url) => format!(
                        "\n  <repositories>\n    <repository><id>toolgate-sdk</id><url>{url}</url></repository>\n  </repositories>\n  <pluginRepositories>\n    <pluginRepository><id>toolgate-sdk</id><url>{url}</url></pluginRepository>\n  </pluginRepositories>\n"
                    ),
                    None => String::new(),
                },
            ));
            values.push((
                "buildCommands",
                match starter.build {
                    "maven" => "The same with Maven directly: `mvn toolgate:dev`, `mvn test`, and `mvn package`, which writes `target/toolgate/<name>-<version>.tgpkg`.".into(),
                    _ => "The same with Gradle directly: `./gradlew toolgateDev`, `./gradlew check`, and `./gradlew toolgatePackage`, which writes `build/toolgate/<name>-<version>.tgpkg`.".into(),
                },
            ));
        }
        "python" => {
            values.push(("pythonPackage", package_id.replace(['.', '-'], "_")));
        }
        other => unreachable!("starter language {other} has no variables"),
    }
    Ok(values)
}

fn is_name(value: &str) -> bool {
    let bytes = value.as_bytes();
    (2..=31).contains(&bytes.len())
        && bytes[0].is_ascii_lowercase()
        && bytes
            .iter()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || *b == b'-')
}

fn capitalize(word: &str) -> String {
    let mut chars = word.chars();
    chars
        .next()
        .map(|c| c.to_ascii_uppercase().to_string() + chars.as_str())
        .unwrap_or_default()
}

/// A repository URL as written, or a directory as a `file:` URL.
fn repository_url(value: &str) -> String {
    if value.contains("://") {
        return value.to_owned();
    }
    let path = std::path::absolute(value).unwrap_or_else(|_| PathBuf::from(value));
    let path = path.to_string_lossy().replace('\\', "/");
    if path.starts_with('/') {
        format!("file://{path}")
    } else {
        format!("file:///{path}")
    }
}

fn expand(template: &str, variables: &[(&str, String)], source: &str) -> Result<String, Error> {
    let mut out = String::with_capacity(template.len());
    let mut rest = template;
    while let Some(start) = rest.find("{{") {
        out.push_str(&rest[..start]);
        let after = &rest[start + 2..];
        let end = after
            .find("}}")
            .ok_or_else(|| Error::Invalid(format!("starter file {source} has an unclosed {{{{")))?;
        let key = &after[..end];
        let value = variables.iter().find(|(k, _)| *k == key).ok_or_else(|| {
            Error::Invalid(format!("starter file {source} uses unknown {{{{{key}}}}}"))
        })?;
        out.push_str(&value.1);
        rest = &after[end + 2..];
    }
    out.push_str(rest);
    Ok(out)
}

fn expand_path(path: &str, variables: &[(&str, String)]) -> String {
    let lookup = |key: &str| {
        variables
            .iter()
            .find(|(k, _)| *k == key)
            .map(|(_, v)| v.as_str())
    };
    path.replace(
        "__PACKAGE__",
        lookup("packagePath").unwrap_or("__PACKAGE__"),
    )
    .replace("__CLASS__", lookup("className").unwrap_or("__CLASS__"))
    .replace(
        "__PYTHON_PACKAGE__",
        lookup("pythonPackage").unwrap_or("__PYTHON_PACKAGE__"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_starter_file_expands_with_its_starters_variables() {
        for starter in STARTERS {
            let options = Options {
                name: "orders".into(),
                language: starter.language.into(),
                build: Some(starter.build.into()),
                package_id: None,
                publisher: None,
                sdk_version: "1.2.3".into(),
                sdk_repository: Some("https://repo.example/maven".into()),
            };
            let variables = variables(starter, &options).unwrap();
            for layer in starter.layers {
                let files: Vec<_> = FILES
                    .iter()
                    .filter(|(p, _, _)| p.starts_with(&format!("{layer}/")))
                    .collect();
                assert!(!files.is_empty(), "layer {layer} is empty");
                for (path, bytes, _) in files {
                    if path.ends_with(".tmpl") {
                        expand(std::str::from_utf8(bytes).unwrap(), &variables, path).unwrap();
                    }
                }
            }
        }
    }

    #[test]
    fn the_gradle_bootstrap_scripts_match_the_repositorys() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        for file in [
            "gradlew",
            "gradlew.bat",
            "gradle/bootstrap.ps1",
            "gradle/wrapper/gradle-wrapper.properties",
        ] {
            let embedded = FILES
                .iter()
                .find(|(p, _, _)| *p == format!("java/gradle/{file}"))
                .unwrap();
            assert_eq!(
                std::fs::read_to_string(root.join(file))
                    .unwrap()
                    .replace("\r\n", "\n"),
                std::str::from_utf8(embedded.1)
                    .unwrap()
                    .replace("\r\n", "\n"),
                "starters/java/gradle/{file} differs from the repository's; copy it again"
            );
        }
        assert!(FILES
            .iter()
            .any(|(p, _, x)| *p == "java/gradle/gradlew" && *x || cfg!(not(unix))));
    }

    #[test]
    fn names_and_package_ids_are_checked() {
        assert!(is_name("orders") && is_name("my-tools2"));
        assert!(!is_name("Orders") && !is_name("1orders") && !is_name("o") && !is_name("my_tools"));
        assert_eq!("file:///tmp/repo", repository_url("/tmp/repo"));
        assert_eq!("https://x/y", repository_url("https://x/y"));
    }
}
