// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The tool schema (`<name>-<version>.tool-schema.json`): everything a model needs to learn to
//! call a package's tools, for generating tool-calling training data. It is derived from the
//! semantic descriptors (name, description, parameters with types, defaults, enums and bounds)
//! plus optional author annotations from `training/annotations.json` (example prompts, phrasing
//! hints, example values). It is written beside the `.tgpkg`, never inside it, so packages and
//! their digests do not change.

use crate::{strict_json, Error};
use serde_json::{json, Map, Value};
use std::path::{Path, PathBuf};

pub const FORMAT: &str = "toolgate.tool-schema";
pub const FORMAT_VERSION: u64 = 1;
/// Author annotations, relative to a project or staging directory.
pub const ANNOTATIONS: &str = "training/annotations.json";
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

/// Reads `dir/training/annotations.json` when present.
pub fn annotations_in(dir: &Path) -> Result<Option<Value>, Error> {
    let path = dir.join(ANNOTATIONS);
    if !path.is_file() {
        return Ok(None);
    }
    read_annotations(&path).map(Some)
}

/// Reads an annotations file: `{"tools": {"<tool local name>": {...}}}`.
pub fn read_annotations(path: &Path) -> Result<Value, Error> {
    strict_json(&std::fs::read(path)?)
        .map_err(|e| Error::Invalid(format!("{}: {e}", path.display())))
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
