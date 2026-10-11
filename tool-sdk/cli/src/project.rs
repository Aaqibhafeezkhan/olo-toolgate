// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Tool projects: how `toolgate dev`, `check` and `pack` drive a project's own build. Each
//! language and build tool is one [`Adapter`] (a marker file and three build commands), so the
//! CLI never compiles code itself and a new language only adds an entry here.

use crate::{package, strict_json, Error};
use serde_json::Value;
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

/// One language and build tool.
pub struct Adapter {
    /// `<language>-<build>`, such as `java-gradle`.
    pub id: &'static str,
    /// The build tool, as `--build` names it.
    pub build: &'static str,
    /// Any of these files in the project directory selects this adapter.
    markers: &'static [&'static str],
    /// The build tool's command, run in the project directory.
    launcher: fn(&Path) -> Command,
    /// Builds the tools and serves them over stdio in development mode.
    dev: &'static [&'static str],
    /// Compiles, runs the tests and every example in test mode.
    check: &'static [&'static str],
    /// Builds the package into [`Adapter::output`].
    package: &'static [&'static str],
    /// Where the package build writes the `.tgpkg`, relative to the project.
    pub output: &'static str,
}

pub const ADAPTERS: &[Adapter] = &[
    Adapter {
        id: "java-gradle",
        build: "gradle",
        markers: &[
            "settings.gradle.kts",
            "settings.gradle",
            "build.gradle.kts",
            "build.gradle",
        ],
        launcher: gradle,
        dev: &["-q", "--console=plain", "toolgateDev"],
        check: &["--console=plain", "check"],
        package: &["-q", "--console=plain", "toolgatePackage"],
        output: "build/toolgate",
    },
    Adapter {
        id: "java-maven",
        build: "maven",
        markers: &["pom.xml"],
        launcher: maven,
        dev: &["-q", "-B", "toolgate:dev"],
        check: &["-B", "test"],
        package: &["-q", "-B", "package"],
        output: "target/toolgate",
    },
];

fn gradle(dir: &Path) -> Command {
    let wrapper = dir.join(if cfg!(windows) {
        "gradlew.bat"
    } else {
        "gradlew"
    });
    let mut command = Command::new(if wrapper.is_file() {
        wrapper.into_os_string()
    } else {
        "gradle".into()
    });
    command.arg("-p").arg(dir);
    command
}

fn maven(dir: &Path) -> Command {
    let wrapper = dir.join(if cfg!(windows) { "mvnw.cmd" } else { "mvnw" });
    let mut command = Command::new(if wrapper.is_file() {
        wrapper.into_os_string()
    } else if cfg!(windows) {
        "mvn.cmd".into()
    } else {
        "mvn".into()
    });
    command.arg("-f").arg(dir.join("pom.xml"));
    command
}

/// The adapter for the project in `dir`; `build` chooses when markers of several are present.
pub fn detect(dir: &Path, build: Option<&str>) -> Result<&'static Adapter, Error> {
    let found: Vec<&Adapter> = ADAPTERS
        .iter()
        .filter(|a| a.markers.iter().any(|m| dir.join(m).is_file()))
        .filter(|a| build.is_none_or(|b| a.build == b))
        .collect();
    match found.as_slice() {
        [adapter] => Ok(adapter),
        [] => Err(Error::Invalid(match build {
            Some(build) => format!("{} has no {build} tool project", dir.display()),
            None => format!(
                "{} is not a tool project: no {}",
                dir.display(),
                ADAPTERS
                    .iter()
                    .flat_map(|a| a.markers.iter().copied())
                    .collect::<Vec<_>>()
                    .join(", ")
            ),
        })),
        several => Err(Error::Invalid(format!(
            "{} has projects for {}; choose one with --build",
            dir.display(),
            several
                .iter()
                .map(|a| a.build)
                .collect::<Vec<_>>()
                .join(" and ")
        ))),
    }
}

/// How a build command is run.
pub struct Run<'a> {
    pub dir: &'a Path,
    pub adapter: &'a Adapter,
    /// Passed to the build tool after its own arguments.
    pub extra: &'a [String],
    pub env: Vec<(&'static str, String)>,
}

impl Run<'_> {
    fn command(&self, args: &[&str]) -> Command {
        let mut command = (self.adapter.launcher)(self.dir);
        command.args(args).args(self.extra).current_dir(self.dir);
        // A build that packs calls back into this binary.
        if let Ok(me) = std::env::current_exe() {
            command.env("TOOLGATE_CLI", me);
        }
        for (key, value) in &self.env {
            command.env(key, value);
        }
        command
    }

    fn status(&self, args: &[&str], what: &str) -> Result<(), Error> {
        let status = self
            .command(args)
            .status()
            .map_err(|e| self.launch_error(e))?;
        if status.success() {
            Ok(())
        } else {
            Err(Error::Rejected(format!("{what} failed ({status})")))
        }
    }

    fn launch_error(&self, error: std::io::Error) -> Error {
        Error::Invalid(format!(
            "cannot run the {} build: {error}",
            self.adapter.build
        ))
    }

    /// Serves the tools until the server exits.
    pub fn dev(&self) -> Result<(), Error> {
        self.status(self.adapter.dev, "the development server")
    }

    /// Compiles, runs the tests and every example.
    pub fn check(&self) -> Result<(), Error> {
        self.status(self.adapter.check, "the check")
    }

    /// Builds the package and returns the newest `.tgpkg` in the adapter's output directory.
    pub fn package(&self) -> Result<PathBuf, Error> {
        self.status(self.adapter.package, "the package build")?;
        let output = self.dir.join(self.adapter.output);
        let mut packages: Vec<(std::time::SystemTime, PathBuf)> = std::fs::read_dir(&output)
            .map_err(|e| Error::Invalid(format!("{}: {e}", output.display())))?
            .filter_map(Result::ok)
            .map(|e| e.path())
            .filter(|p| p.extension().is_some_and(|x| x == "tgpkg"))
            .filter_map(|p| Some((p.metadata().ok()?.modified().ok()?, p)))
            .collect();
        packages.sort();
        packages.pop().map(|(_, p)| p).ok_or_else(|| {
            Error::Invalid(format!("the build wrote no .tgpkg to {}", output.display()))
        })
    }

    /// Starts the development server, asks it `server/discover` and returns each tool's
    /// `toolDescriptorDigest` by tool id, then the `descriptorSetDigest`.
    pub fn digests(&self) -> Result<(Vec<(String, String)>, String), Error> {
        let mut child = self
            .command(self.adapter.dev)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(|e| self.launch_error(e))?;
        let request = serde_json::json!({
            "jsonrpc": "2.0", "id": 1, "method": "server/discover",
            "params": {"_meta": {
                "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities": {}
            }}
        });
        {
            let mut stdin = child.stdin.take().unwrap();
            writeln!(stdin, "{request}")?;
        }
        let mut response = None;
        for line in BufReader::new(child.stdout.take().unwrap()).lines() {
            let line = line?;
            if let Ok(value) = strict_json(line.as_bytes()) {
                if value["id"] == 1 {
                    response = Some(value);
                }
            }
        }
        let status = child.wait()?;
        let response = response.ok_or_else(|| {
            Error::Rejected(format!(
                "the development server did not answer server/discover ({status})"
            ))
        })?;
        let meta = &response["result"]["_meta"];
        let digests = match &meta["io.ololabs.toolgate/descriptorDigests"] {
            Value::Object(map) => map
                .iter()
                .map(|(tool, d)| (tool.clone(), d.as_str().unwrap_or_default().to_owned()))
                .collect(),
            _ => {
                return Err(Error::Rejected(format!(
                    "server/discover returned no descriptor digests: {response}"
                )))
            }
        };
        let set = meta["io.ololabs.toolgate/descriptorSetDigest"]
            .as_str()
            .unwrap_or_default()
            .to_owned();
        Ok((digests, set))
    }
}

/// Validates a built package and returns its report.
pub fn validate_package(path: &Path) -> Result<package::Report, Error> {
    package::validate(&std::fs::read(path)?)
}
