// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The tool schema (`<name>-<version>.tool-schema.json`): everything a model needs to learn to
//! call a package's tools, for generating tool-calling training data. It is derived from the
//! semantic descriptors (name, description, parameters with types, defaults, enums and bounds)
//! plus the training annotations (example prompts, phrasing hints, example values, and the
//! `modelValidation` test prompts a dataset builder keeps out of training). Annotations are
//! declared on the tools themselves (`@Training`, `training(...)`, a `"training"` block) and the SDK
//! build generates `training/annotations.json` from them; a hand-written file is refused, so the
//! hints cannot drift from the tools. It is written beside the `.tgpkg`, never inside it, so
//! packages and their digests do not change.

use crate::{strict_json, Error};
use serde_json::{json, Map, Value};
use std::path::{Path, PathBuf};

pub const FORMAT: &str = "toolgate.tool-schema";
pub const FORMAT_VERSION: u64 = 1;
/// Author annotations, relative to a project or staging directory.
pub const ANNOTATIONS: &str = "training/annotations.json";
/// Marks an annotations file a build generated from the tools' own declarations (`@Training`,
/// `training(...)`, a `"training"` block). Only such files are read; a later build may replace or
/// remove one, but never a file without the mark.
pub const GENERATED_BY: &str = "generatedBy";
/// The value of [`GENERATED_BY`] in the file `pack` writes beside a package.
pub const GENERATOR: &str = "toolgate pack";
/// Input-schema keywords copied into a parameter's `constraints`.
const CONSTRAINTS: [&str; 9] = [
    "minimum",
    "maximum",
    "minLength",
    "maxLength",
    "pattern",
    "format",
    "minItems",
    "maxItems",
    "items",
];

/// Where the tool schema of `package` goes: `x.tgpkg` gives `x.tool-schema.json`.
pub fn path_for(package: &Path) -> PathBuf {
    let name = package
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("package");
    let stem = name.strip_suffix(".tgpkg").unwrap_or(name);
    package.with_file_name(format!("{stem}.tool-schema.json"))
}

/// Reads the generated `dir/training/annotations.json` of a staging directory when present.
pub fn annotations_in(dir: &Path) -> Result<Option<Value>, Error> {
    let path = dir.join(ANNOTATIONS);
    if !path.is_file() {
        return Ok(None);
    }
    read_annotations(&path).map(Some)
}

/// Reads a generated annotations file: `{"generatedBy": "...", "tools": {"<tool local name>": {...}}}`.
/// A file without the mark was written by hand and is refused.
pub fn read_annotations(path: &Path) -> Result<Value, Error> {
    let value = parse(path)?;
    if !is_generated(&value) {
        return Err(hand_written(path));
    }
    Ok(value)
}

/// Refuses a hand-written `training/annotations.json` in a tool project: hints live on the tools.
pub fn refuse_in_project(dir: &Path) -> Result<(), Error> {
    let path = dir.join(ANNOTATIONS);
    if path.is_file() {
        return Err(hand_written(&path));
    }
    Ok(())
}

fn parse(path: &Path) -> Result<Value, Error> {
    strict_json(&std::fs::read(path)?)
        .map_err(|e| Error::Invalid(format!("{}: {e}", path.display())))
}

fn is_generated(value: &Value) -> bool {
    value
        .get(GENERATED_BY)
        .and_then(Value::as_str)
        .is_some_and(|by| !by.is_empty())
}

fn hand_written(path: &Path) -> Error {
    Error::Invalid(format!(
        "{} is written by hand; training annotations are generated from the tool declarations \
         (@Training and @TrainingValues in Java, training(...) and TrainingValues in Python, a \"training\" \
         block in toolgate.json). Move its hints there and delete the file",
        path.display()
    ))
}

/// Where the annotations of a built package go: `training/annotations.json` beside the `.tgpkg`, where the
/// dataset builder looks for them.
pub fn beside(package: &Path) -> PathBuf {
    package
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .join(ANNOTATIONS)
}

/// The generated annotations at `path`, if there is a file and a build generated it.
pub fn generated_at(path: &Path) -> Result<Option<Value>, Error> {
    if !path.is_file() {
        return Ok(None);
    }
    let value = parse(path)?;
    Ok(is_generated(&value).then_some(value))
}

/// Writes the annotations beside `package`, marked as generated, and returns where. With none, removes a file an
/// earlier build generated there. A hand-written file is never touched.
pub fn write_beside(package: &Path, annotations: Option<&Value>) -> Result<Option<PathBuf>, Error> {
    let path = beside(package);
    match annotations {
        Some(value) => {
            if path.is_file() && generated_at(&path)?.is_none() {
                // The source the hints came from, or a file an author keeps there: never overwritten.
                eprintln!(
                    "note: {} was written by hand, so it is left as it is; the tool schema carries the hints",
                    path.display()
                );
                return Ok(None);
            }
            let mut out = Map::new();
            out.insert(GENERATED_BY.into(), json!(GENERATOR));
            out.insert(
                "tools".into(),
                value.get("tools").cloned().unwrap_or_else(|| json!({})),
            );
            if let Some(dir) = path.parent() {
                std::fs::create_dir_all(dir)?;
            }
            let mut bytes = serde_json::to_vec_pretty(&Value::Object(out))
                .map_err(|e| Error::Invalid(e.to_string()))?;
            bytes.push(b'\n');
            std::fs::write(&path, bytes)?;
            Ok(Some(path))
        }
        None => {
            if generated_at(&path)?.is_some() {
                std::fs::remove_file(&path)?;
            }
            Ok(None)
        }
    }
}

fn parameters(input: &Value) -> Vec<Value> {
    let required: Vec<&str> = input["required"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .collect();
    let mut list = Vec::new();
    for (name, schema) in input["properties"].as_object().into_iter().flatten() {
        let mut p = Map::new();
        p.insert("name".into(), json!(name));
        for key in ["type", "description", "default", "enum"] {
            if let Some(value) = schema.get(key) {
                p.insert(key.into(), value.clone());
            }
        }
        p.insert("required".into(), json!(required.contains(&name.as_str())));
        if schema.get("x-toolgate-sensitive") == Some(&json!(true)) {
            p.insert("sensitive".into(), json!(true));
        }
        let constraints: Map<String, Value> = CONSTRAINTS
            .iter()
            .filter_map(|k| schema.get(*k).map(|v| (k.to_string(), v.clone())))
            .collect();
        if !constraints.is_empty() {
            p.insert("constraints".into(), Value::Object(constraints));
        }
        list.push(Value::Object(p));
    }
    list
}

/// Builds the tool schema of a package manifest. Annotations must name only the package's tools.
pub fn build(manifest: &Value, annotations: Option<&Value>) -> Result<Value, Error> {
    let notes = match annotations {
        None => Map::new(),
        Some(a) => match a.get("tools") {
            None => Map::new(),
            Some(Value::Object(tools)) => tools.clone(),
            Some(_) => {
                return Err(Error::Invalid(format!(
                    "{ANNOTATIONS}: tools must be an object"
                )))
            }
        },
    };
    let mut tools = Vec::new();
    let mut names = Vec::new();
    for tool in manifest["tools"].as_array().into_iter().flatten() {
        let d = &tool["descriptor"];
        let tool_id = d["toolId"]
            .as_str()
            .ok_or_else(|| Error::Invalid("descriptor has no toolId".into()))?;
        let name = tool_id
            .split_once('/')
            .map(|(_, n)| n)
            .ok_or_else(|| Error::Invalid(format!("toolId has no local name: {tool_id:?}")))?;
        let mut entry = Map::new();
        entry.insert("toolId".into(), json!(tool_id));
        entry.insert("name".into(), json!(name));
        for key in [
            "mcpName",
            "kind",
            "title",
            "description",
            "useWhen",
            "effect",
        ] {
            if let Some(value) = d.get(key) {
                entry.insert(key.into(), value.clone());
            }
        }
        entry.insert(
            "parameters".into(),
            Value::Array(parameters(&d["inputSchema"])),
        );
        entry.insert("inputSchema".into(), d["inputSchema"].clone());
        if let Some(output) = d.get("outputSchema") {
            entry.insert("outputSchema".into(), output.clone());
        }
        let note = notes.get(name).cloned().unwrap_or_else(|| json!({}));
        if !note.is_object() {
            return Err(Error::Invalid(format!(
                "{ANNOTATIONS}: {name} must be an object"
            )));
        }
        entry.insert("annotations".into(), note);
        names.push(name.to_owned());
        tools.push(Value::Object(entry));
    }
    if let Some(unknown) = notes.keys().find(|k| !names.contains(k)) {
        return Err(Error::Invalid(format!(
            "{ANNOTATIONS}: {unknown:?} is not a tool in this package"
        )));
    }
    Ok(json!({
        "format": FORMAT,
        "formatVersion": FORMAT_VERSION,
        "package": {
            "id": manifest["id"],
            "version": manifest["version"],
            "publisher": manifest["publisher"],
        },
        "tools": tools,
    }))
}

/// Builds the tool schema and writes it to `path` as indented JSON.
pub fn write(path: &Path, manifest: &Value, annotations: Option<&Value>) -> Result<(), Error> {
    let mut bytes = serde_json::to_vec_pretty(&build(manifest, annotations)?)
        .map_err(|e| Error::Invalid(e.to_string()))?;
    bytes.push(b'\n');
    std::fs::write(path, bytes)?;
    Ok(())
}
