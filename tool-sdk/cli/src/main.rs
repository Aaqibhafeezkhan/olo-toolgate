// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! `toolgate`: create, run, check and pack tool projects, and validate, digest and pack ToolGate
//! tool packages offline (platform-requirements.md §4, §5.5).

use std::path::{Path, PathBuf};
use std::process::ExitCode;
use toolgate_cli::{
    canonical, descriptor, init, package, project, strict_json, tool_schema, Error,
};

const USAGE: &str = "toolgate: create, run, check and pack ToolGate tools

Project commands (run in a tool project, or name its directory):
  toolgate init <name> [--lang java|python] [--build gradle|maven|python] [--dir <dir>] [--id <package-id>]
                [--publisher <name>] [--sdk-version <version>] [--sdk-repo <url-or-dir>]
                                                  Create a tool project from a starter
  toolgate dev [<dir>] [--http <host:port>] [--digests]
                                                  Build and serve the tools as an MCP server (stdio by default)
  toolgate check [<dir>]                          Compile, run the tests and every example
  toolgate pack [<dir>] [-o <file.tgpkg>]         Build the package and validate it; also writes
                                                  <file>.tool-schema.json for training tool-calling models
  toolgate guide                                  Print the guide to building tools with the SDK

Package commands:
  toolgate pack <staging-dir> [-o <file.tgpkg>]   Build a package from a staging directory
  toolgate validate <file.tgpkg | dir>            Archive rules plus manifest, descriptor and digest checks;
                                                  a project directory runs check
  toolgate verify <file.tgpkg>                    Archive rules only, as every hop runs them
  toolgate schema <file.tgpkg | staging-dir> [--annotations <file>] [-o <file>]
                                                  Write the tool schema used to train tool-calling models
  toolgate digest <descriptor.json>...            toolDescriptorDigest of each, and the set digest
  toolgate canonical <descriptor.json>            Print the canonical (NFC + RFC 8785) bytes
  toolgate equivalent <a.json> <b.json>           Compare two descriptors under rewrite list 1
  toolgate --version

Project commands take --build <gradle|maven> when a directory has more than one build, and pass
anything after -- to the build tool.";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(Failure::Usage) => {
            eprintln!("{USAGE}");
            ExitCode::from(2)
        }
        Err(Failure::Error(error)) => {
            eprintln!("toolgate: {error}");
            ExitCode::from(1)
        }
        Err(Failure::NotEquivalent) => ExitCode::from(1),
    }
}

enum Failure {
    Usage,
    Error(Error),
    NotEquivalent,
}

impl From<Error> for Failure {
    fn from(error: Error) -> Self {
        Failure::Error(error)
    }
}

impl From<std::io::Error> for Failure {
    fn from(error: std::io::Error) -> Self {
        Failure::Error(Error::Io(error))
    }
}

fn json_file(path: &str) -> Result<serde_json::Value, Failure> {
    Ok(strict_json(&std::fs::read(path)?)?)
}

/// Arguments after the command: positionals, `--flag value` options, bare switches, and anything after `--`.
struct Parsed<'a> {
    positionals: Vec<&'a str>,
    options: Vec<(&'a str, &'a str)>,
    switches: Vec<&'a str>,
    extra: Vec<String>,
}

impl<'a> Parsed<'a> {
    fn new(args: &'a [&'a str], with_value: &[&str], switches: &[&str]) -> Result<Self, Failure> {
        let (args, extra) = match args.iter().position(|a| *a == "--") {
            Some(i) => (
                &args[..i],
                args[i + 1..].iter().map(|a| a.to_string()).collect(),
            ),
            None => (args, Vec::new()),
        };
        let mut parsed = Parsed {
            positionals: Vec::new(),
            options: Vec::new(),
            switches: Vec::new(),
            extra,
        };
        let mut i = 0;
        while i < args.len() {
            let arg = args[i];
            if with_value.contains(&arg) {
                let value = args.get(i + 1).ok_or(Failure::Usage)?;
                parsed.options.push((arg, value));
                i += 2;
                continue;
            }
            if switches.contains(&arg) {
                parsed.switches.push(arg);
            } else if arg.starts_with('-') {
                return Err(Failure::Usage);
            } else {
                parsed.positionals.push(arg);
            }
            i += 1;
        }
        Ok(parsed)
    }

    fn option(&self, names: &[&str]) -> Option<&'a str> {
        self.options
            .iter()
            .rev()
            .find(|(n, _)| names.contains(n))
            .map(|(_, v)| *v)
    }

    fn switch(&self, name: &str) -> bool {
        self.switches.contains(&name)
    }

    /// The single directory argument, `.` when absent.
    fn directory(&self) -> Result<&'a Path, Failure> {
        match self.positionals.as_slice() {
            [] => Ok(Path::new(".")),
            [dir] => Ok(Path::new(*dir)),
            _ => Err(Failure::Usage),
        }
    }
}

fn project_run<'a>(dir: &'a Path, parsed: &'a Parsed) -> Result<project::Run<'a>, Failure> {
    let adapter = project::detect(dir, parsed.option(&["--build"]))?;
    Ok(project::Run {
        dir,
        adapter,
        extra: &parsed.extra,
        env: Vec::new(),
    })
}

fn run(args: &[String]) -> Result<(), Failure> {
    let args: Vec<&str> = args.iter().map(String::as_str).collect();
    let (command, rest) = match args.split_first() {
        Some((command, rest)) => (*command, rest),
        None => return Err(Failure::Usage),
    };
    match (command, rest) {
        ("--version" | "version", []) => println!("toolgate {}", env!("CARGO_PKG_VERSION")),
        ("--help" | "help", []) => println!("{USAGE}"),
        ("guide", []) => print!("{}", toolgate_cli::GUIDE),
        ("init", rest) => {
            let p = Parsed::new(
                rest,
                &[
                    "--lang",
                    "--build",
                    "--dir",
                    "--id",
                    "--publisher",
                    "--sdk-version",
                    "--sdk-repo",
                ],
                &[],
            )?;
            let [name] = p.positionals.as_slice() else {
                return Err(Failure::Usage);
            };
            if !p.extra.is_empty() {
                return Err(Failure::Usage);
            }
            let options = init::Options {
                name: name.to_string(),
                language: p.option(&["--lang"]).unwrap_or("java").to_owned(),
                build: p.option(&["--build"]).map(str::to_owned),
                package_id: p.option(&["--id"]).map(str::to_owned),
                publisher: p.option(&["--publisher"]).map(str::to_owned),
                sdk_version: p
                    .option(&["--sdk-version"])
                    .unwrap_or(env!("CARGO_PKG_VERSION"))
                    .to_owned(),
                sdk_repository: p.option(&["--sdk-repo"]).map(str::to_owned),
            };
            let dir = PathBuf::from(p.option(&["--dir"]).unwrap_or(name));
            let starter = init::starter(&options.language, options.build.as_deref())?;
            let written = init::init(&dir, &options)?;
            println!(
                "created {name} ({} with {}, {} files) in {}",
                starter.language,
                starter.build,
                written.len(),
                dir.display()
            );
            println!("next:\n  cd {}\n  toolgate dev      serve the tools over stdio\n  toolgate check    run the tests and every example\n  toolgate pack     build the .tgpkg", dir.display());
            if options.sdk_repository.is_none() {
                println!("{}", starter.local_sdk_note);
            }
        }
        ("dev", rest) => {
            let p = Parsed::new(rest, &["--build", "--http"], &["--digests"])?;
            let dir = p.directory()?;
            let mut run = project_run(dir, &p)?;
            run.env.push(("TOOLGATE_MODE", "development".into()));
            if let Some(address) = p.option(&["--http"]) {
                run.env.push(("TOOLGATE_HTTP_ADDRESS", address.to_owned()));
            }
            if p.switch("--digests") {
                let (digests, set) = run.digests()?;
                for (tool, digest) in digests {
                    println!("{digest}  {tool}");
                }
                println!("descriptorSetDigest {set}");
            } else {
                run.dev()?;
            }
        }
        ("check", rest) => {
            let p = Parsed::new(rest, &["--build"], &[])?;
            project_run(p.directory()?, &p)?.check()?;
            println!("check passed");
        }
        ("validate", rest) => {
            let p = Parsed::new(rest, &["--build"], &[])?;
            let [target] = p.positionals.as_slice() else {
                return Err(Failure::Usage);
            };
            if Path::new(target).is_dir() {
                project_run(Path::new(target), &p)?.check()?;
                println!("check passed");
            } else {
                print_report(&package::validate(&std::fs::read(target)?)?);
            }
        }
        ("pack", rest) => {
            let p = Parsed::new(rest, &["-o", "--output", "--build"], &[])?;
            let dir = p.directory()?;
            let output = p.option(&["-o", "--output"]).map(PathBuf::from);
            if dir.join(package::MANIFEST).is_file() {
                if !p.extra.is_empty() || p.option(&["--build"]).is_some() {
                    return Err(Failure::Usage);
                }
                let (data, report) = package::pack(dir)?;
                let output = match output {
                    Some(file) => file,
                    None => {
                        let manifest = strict_json(&std::fs::read(dir.join(package::MANIFEST))?)?;
                        PathBuf::from(format!(
                            "{}-{}.tgpkg",
                            manifest["id"].as_str().unwrap_or("package"),
                            manifest["version"].as_str().unwrap_or("0.0.0")
                        ))
                    }
                };
                std::fs::write(&output, &data)?;
                println!("wrote {}", output.display());
                write_tool_schema(&output, &data, tool_schema::annotations_in(dir)?)?;
                print_report(&report);
            } else {
                let built = project_run(dir, &p)?.package()?;
                let report = project::validate_package(&built)?;
                let written = match output {
                    Some(file) => {
                        std::fs::copy(&built, &file)?;
                        file
                    }
                    None => built,
                };
                println!("wrote {}", written.display());
                let data = std::fs::read(&written)?;
                write_tool_schema(&written, &data, tool_schema::annotations_in(dir)?)?;
                print_report(&report);
            }
        }
        ("schema", rest) => {
            let p = Parsed::new(rest, &["-o", "--output", "--annotations"], &[])?;
            let [target] = p.positionals.as_slice() else {
                return Err(Failure::Usage);
            };
            let target = Path::new(target);
            let (manifest, default_output, annotations) = if target.is_dir() {
                let manifest = strict_json(&std::fs::read(target.join(package::MANIFEST))?)?;
                let annotations = tool_schema::annotations_in(target)?;
                (manifest, target.join("tool-schema.json"), annotations)
            } else {
                let manifest = package::verify(&std::fs::read(target)?)?.manifest;
                (manifest, tool_schema::path_for(target), None)
            };
            let annotations = match p.option(&["--annotations"]) {
                Some(file) => Some(tool_schema::read_annotations(Path::new(file))?),
                None => annotations,
            };
            let output = p
                .option(&["-o", "--output"])
                .map(PathBuf::from)
                .unwrap_or(default_output);
            tool_schema::write(&output, &manifest, annotations.as_ref())?;
            println!("wrote {}", output.display());
        }
        ("verify", [file]) => {
            println!(
                "packageDigest {}",
                package::verify(&std::fs::read(file)?)?.package_digest
            )
        }
        ("digest", files) if !files.is_empty() => {
            let mut descriptors = Vec::new();
            for file in files {
                let value = json_file(file)?;
                println!("{}  {file}", descriptor::tool_descriptor_digest(&value)?);
                descriptors.push(value);
            }
            println!(
                "descriptorSetDigest {}",
                descriptor::descriptor_set_digest(&descriptors)?
            );
        }
        ("canonical", [file]) => {
            use std::io::Write;
            std::io::stdout().write_all(&canonical::canonical_bytes(&json_file(file)?)?)?;
        }
        ("equivalent", [a, b]) => {
            if descriptor::equivalent(&json_file(a)?, &json_file(b)?)? {
                println!("equivalent");
            } else {
                println!("changed");
                return Err(Failure::NotEquivalent);
            }
        }
        _ => return Err(Failure::Usage),
    }
    Ok(())
}

/// Writes `<package>.tool-schema.json` beside a package that was just built.
fn write_tool_schema(
    package: &Path,
    data: &[u8],
    annotations: Option<serde_json::Value>,
) -> Result<(), Failure> {
    let manifest = package::verify(data)?.manifest;
    let path = tool_schema::path_for(package);
    tool_schema::write(&path, &manifest, annotations.as_ref())?;
    println!("wrote {}", path.display());
    Ok(())
}

fn print_report(report: &package::Report) {
    println!("packageDigest {}", report.package_digest);
    println!("descriptorSetDigest {}", report.descriptor_set_digest);
    for (tool_id, digest) in &report.tools {
        println!("{digest}  {tool_id}");
    }
}
