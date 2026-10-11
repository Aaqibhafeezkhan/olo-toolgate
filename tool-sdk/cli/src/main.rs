// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! `toolgate`: validate, digest and pack ToolGate tool packages offline (plan.md §4).

use std::path::{Path, PathBuf};
use std::process::ExitCode;
use toolgate_cli::{canonical, descriptor, package, strict_json, Error};

const USAGE: &str = "toolgate: validate, digest and pack ToolGate tool packages offline

Usage:
  toolgate pack <staging-dir> [-o <file.tgpkg>]   Build a package and print its digests
  toolgate validate <file.tgpkg>                  Archive rules plus manifest, descriptor and digest checks
  toolgate verify <file.tgpkg>                    Archive rules only, as every hop runs them
  toolgate digest <descriptor.json>...            toolDescriptorDigest of each, and the set digest
  toolgate canonical <descriptor.json>            Print the canonical (NFC + RFC 8785) bytes
  toolgate equivalent <a.json> <b.json>           Compare two descriptors under rewrite list 1
  toolgate --version";

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

fn run(args: &[String]) -> Result<(), Failure> {
    let args: Vec<&str> = args.iter().map(String::as_str).collect();
    match args.as_slice() {
        ["--version"] | ["version"] => println!("toolgate {}", env!("CARGO_PKG_VERSION")),
        ["--help"] | ["help"] => println!("{USAGE}"),
        ["pack", dir, rest @ ..] => {
            let output = match rest {
                [] => None,
                ["-o", file] | ["--output", file] => Some(PathBuf::from(file)),
                _ => return Err(Failure::Usage),
            };
            let (data, report) = package::pack(Path::new(dir))?;
            let output = match output {
                Some(file) => file,
                None => {
                    let manifest =
                        strict_json(&std::fs::read(Path::new(dir).join(package::MANIFEST))?)?;
                    let name = format!(
                        "{}-{}.tgpkg",
                        manifest["id"].as_str().unwrap_or("package"),
                        manifest["version"].as_str().unwrap_or("0.0.0")
                    );
                    PathBuf::from(name)
                }
            };
            std::fs::write(&output, data)?;
            println!("wrote {}", output.display());
            print_report(&report);
        }
        ["validate", file] => print_report(&package::validate(&std::fs::read(file)?)?),
        ["verify", file] => {
            println!(
                "packageDigest {}",
                package::verify(&std::fs::read(file)?)?.package_digest
            )
        }
        ["digest", files @ ..] if !files.is_empty() => {
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
        ["canonical", file] => {
            use std::io::Write;
            std::io::stdout().write_all(&canonical::canonical_bytes(&json_file(file)?)?)?;
        }
        ["equivalent", a, b] => {
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

fn print_report(report: &package::Report) {
    println!("packageDigest {}", report.package_digest);
    println!("descriptorSetDigest {}", report.descriptor_set_digest);
    for (tool_id, digest) in &report.tools {
        println!("{digest}  {tool_id}");
    }
}
