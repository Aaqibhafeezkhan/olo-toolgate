// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Offline validation against the contract set v2 schemas embedded in the contracts crate.

use crate::Error;
use jsonschema::Validator;
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::sync::OnceLock;

struct Schemas {
    validators: BTreeMap<String, Validator>,
}

fn schemas() -> Result<&'static Schemas, Error> {
    static SCHEMAS: OnceLock<Result<Schemas, String>> = OnceLock::new();
    SCHEMAS
        .get_or_init(build)
        .as_ref()
        .map_err(|e| Error::Invalid(e.clone()))
}

fn build() -> Result<Schemas, String> {
    let mut registry = jsonschema::Registry::new();
    let mut definitions = Vec::new();
    for (uri, content) in olo_toolgate_contracts::v2::CANONICAL_SCHEMAS {
        let schema: Value = serde_json::from_str(content).map_err(|_| "invalid embedded schema")?;
        for name in schema["$defs"]
            .as_object()
            .into_iter()
            .flatten()
            .map(|(n, _)| n)
        {
            definitions.push((name.clone(), uri.to_string()));
        }
        registry = registry
            .add(*uri, schema)
            .map_err(|_| "invalid embedded schema")?;
    }
    let registry = registry
        .prepare()
        .map_err(|_| "invalid embedded schema references")?;
    let mut validators = BTreeMap::new();
    for (name, uri) in definitions {
        let wrapper = json!({"$schema": "https://json-schema.org/draft/2020-12/schema", "$ref": format!("{uri}#/$defs/{name}")});
        let validator = jsonschema::options()
            .with_registry(&registry)
            .build(&wrapper)
            .map_err(|_| format!("cannot compile {name}"))?;
        validators.insert(name, validator);
    }
    Ok(Schemas { validators })
}

/// The first validation errors of `value` against the v2 definition `name`; empty when valid.
pub fn errors(name: &str, value: &Value) -> Result<Vec<String>, Error> {
    let validator = schemas()?
        .validators
        .get(name)
        .ok_or_else(|| Error::Invalid(format!("no v2 definition named {name}")))?;
    Ok(validator
        .iter_errors(value)
        .take(5)
        .map(|e| format!("{e} at {}", e.instance_path()))
        .collect())
}
