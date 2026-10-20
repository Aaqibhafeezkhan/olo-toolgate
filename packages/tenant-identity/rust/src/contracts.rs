// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
use crate::{Failure, Result};
use serde::{de::DeserializeOwned, Serialize};
use std::collections::BTreeMap;
pub struct Contracts {
    validators: BTreeMap<String, jsonschema::Validator>,
}
impl Contracts {
    pub fn shared() -> Result<&'static Self> {
        static VALUE: std::sync::OnceLock<Result<Contracts>> = std::sync::OnceLock::new();
        VALUE
            .get_or_init(Self::new)
            .as_ref()
            .map_err(|error| *error)
    }
    fn new() -> Result<Self> {
        let mut registry = jsonschema::Registry::new();
        let mut definitions = Vec::new();
        for (uri, bytes) in olo_toolgate_contracts::CANONICAL_SCHEMAS {
            let value: serde_json::Value =
                serde_json::from_str(bytes).map_err(|_| Failure::Validation)?;
            if uri.ends_with("/v2/discovery.schema.json") {
                for name in value["$defs"]
                    .as_object()
                    .ok_or(Failure::Validation)?
                    .keys()
                {
                    definitions.push((name.clone(), uri.to_string()));
                }
            }
            registry = registry.add(*uri, value).map_err(|_| Failure::Validation)?;
        }
        let registry = registry.prepare().map_err(|_| Failure::Validation)?;
        let mut validators = BTreeMap::new();
        for (name, uri) in definitions {
            let schema = serde_json::json!({"$schema":"https://json-schema.org/draft/2020-12/schema","$ref":format!("{uri}#/$defs/{name}")});
            validators.insert(
                name,
                jsonschema::options()
                    .with_registry(&registry)
                    .build(&schema)
                    .map_err(|_| Failure::Validation)?,
            );
        }
        Ok(Self { validators })
    }
    pub fn decode<T: DeserializeOwned>(&self, name: &str, bytes: &[u8]) -> Result<T> {
        if bytes.len() > 131072 {
            return Err(Failure::Validation);
        }
        let value = crate::json::strict_json(bytes).map_err(|_| Failure::Validation)?;
        if !self
            .validators
            .get(name)
            .is_some_and(|validator| validator.is_valid(&value))
        {
            return Err(Failure::Validation);
        }
        serde_json::from_slice(bytes).map_err(|_| Failure::Validation)
    }
    pub fn encode<T: Serialize>(&self, name: &str, value: &T) -> Result<Vec<u8>> {
        let bytes = serde_json::to_vec(value).map_err(|_| Failure::Validation)?;
        let _: serde_json::Value = self.decode(name, &bytes)?;
        Ok(bytes)
    }
}
