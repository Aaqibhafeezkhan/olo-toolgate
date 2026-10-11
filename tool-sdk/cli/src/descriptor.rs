// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Descriptor digests and equivalence (tool-sdk/spec/descriptor/README.md §3 and §4).

use crate::canonical::{canonical_bytes, digest, utf16_key};
use crate::Error;
use serde_json::{json, Map, Value};

/// `toolDescriptorDigest` of one semantic descriptor.
pub fn tool_descriptor_digest(descriptor: &Value) -> Result<String, Error> {
    digest(descriptor)
}

/// `descriptorSetDigest`: the digest of `[{toolId, toolDescriptorDigest}]` sorted by `toolId`.
pub fn descriptor_set_digest(descriptors: &[Value]) -> Result<String, Error> {
    let mut entries = Vec::with_capacity(descriptors.len());
    for descriptor in descriptors {
        let tool_id = descriptor
            .get("toolId")
            .and_then(Value::as_str)
            .ok_or_else(|| Error::Invalid("descriptor has no toolId".into()))?;
        entries.push((tool_id.to_owned(), tool_descriptor_digest(descriptor)?));
    }
    set_digest_of(entries)
}

/// The set digest from `(toolId, toolDescriptorDigest)` pairs already computed.
pub fn set_digest_of(mut entries: Vec<(String, String)>) -> Result<String, Error> {
    entries.sort_by(|a, b| utf16_key(&a.0).cmp(&utf16_key(&b.0)));
    if entries.windows(2).any(|w| w[0].0 == w[1].0) {
        return Err(Error::Invalid("two tools share a toolId".into()));
    }
    let list: Vec<Value> = entries
        .into_iter()
        .map(|(tool_id, d)| json!({"toolId": tool_id, "toolDescriptorDigest": d}))
        .collect();
    digest(&Value::Array(list))
}

/// Equivalence under the closed rewrite list, version 1 (§4).
pub fn equivalent(a: &Value, b: &Value) -> Result<bool, Error> {
    Ok(canonical_bytes(&semantic_form(a))? == canonical_bytes(&semantic_form(b))?)
}

fn semantic_form(descriptor: &Value) -> Value {
    let mut form = descriptor.clone();
    if let Value::Object(map) = &mut form {
        for key in ["inputSchema", "outputSchema"] {
            if let Some(schema) = map.get(key) {
                let defs = schema
                    .get("$defs")
                    .cloned()
                    .unwrap_or(Value::Object(Map::new()));
                let rewritten = rewrite(schema, &defs, &mut Vec::new());
                map.insert(key.into(), rewritten);
            }
        }
    }
    form
}

/// Keywords removed when they hold the JSON Schema default (rule 4).
fn default_valued(key: &str, value: &Value) -> bool {
    matches!(
        (key, value),
        ("additionalProperties", Value::Bool(true))
            | ("uniqueItems", Value::Bool(false))
            | ("deprecated", Value::Bool(false))
            | ("readOnly", Value::Bool(false))
            | ("writeOnly", Value::Bool(false))
    ) || (matches!(key, "minItems" | "minLength") && value.as_u64() == Some(0))
}

fn rewrite(schema: &Value, defs: &Value, stack: &mut Vec<String>) -> Value {
    match schema {
        Value::Array(items) => {
            Value::Array(items.iter().map(|s| rewrite(s, defs, stack)).collect())
        }
        Value::Object(map) => {
            // Rule 1: inline a non-recursive local reference.
            if let (1, Some(Value::String(reference))) = (map.len(), map.get("$ref")) {
                if let Some(name) = reference.strip_prefix("#/$defs/") {
                    if let Some(target) =
                        defs.get(name).filter(|_| !stack.iter().any(|s| s == name))
                    {
                        stack.push(name.to_owned());
                        let inlined = rewrite(target, defs, stack);
                        stack.pop();
                        return inlined;
                    }
                }
            }
            let mut out = Map::new();
            for (key, value) in map {
                if default_valued(key, value) {
                    continue;
                }
                let value = match (key.as_str(), value) {
                    // Rule 2: a single-element type list.
                    ("type", Value::Array(types)) if types.len() == 1 => types[0].clone(),
                    // Rule 3: sorted required.
                    ("required", Value::Array(names)) => {
                        let mut names = names.clone();
                        names.sort_by(|a, b| {
                            utf16_key(a.as_str().unwrap_or_default())
                                .cmp(&utf16_key(b.as_str().unwrap_or_default()))
                        });
                        Value::Array(names)
                    }
                    _ => rewrite(value, defs, stack),
                };
                out.insert(key.clone(), value);
            }
            // Drop $defs entries that inlining left unreferenced.
            if let Some(Value::Object(local)) = out.get("$defs").cloned() {
                let rest: Map<String, Value> = out
                    .iter()
                    .filter(|(k, _)| k.as_str() != "$defs")
                    .map(|(k, v)| (k.clone(), v.clone()))
                    .collect();
                let used =
                    String::from_utf8(canonical_bytes(&Value::Object(rest)).unwrap_or_default())
                        .unwrap_or_default();
                let kept: Map<String, Value> = local
                    .into_iter()
                    .filter(|(name, _)| used.contains(&format!("\"#/$defs/{name}\"")))
                    .collect();
                if kept.is_empty() {
                    out.remove("$defs");
                } else {
                    out.insert("$defs".into(), Value::Object(kept));
                }
            }
            Value::Object(out)
        }
        other => other.clone(),
    }
}
