// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Offline shared-schema boundary validation; never fetch references.
use crate::{Failure, Result};
use serde::{de::DeserializeOwned, Serialize};
use std::collections::BTreeMap;
pub struct Contracts {
    validators: BTreeMap<String, jsonschema::Validator>,
}
impl Contracts {
    pub fn shared() -> Result<&'static Self> {
        static CONTRACTS: std::sync::OnceLock<Result<Contracts>> = std::sync::OnceLock::new();
        CONTRACTS
            .get_or_init(Self::new)
            .as_ref()
            .map_err(|error| *error)
    }
    pub fn new() -> Result<Self> {
        let mut registry = jsonschema::Registry::new();
        let mut definitions = Vec::new();
        for (uri, bytes) in olo_toolgate_contracts::CANONICAL_SCHEMAS {
            let value: serde_json::Value =
                serde_json::from_str(bytes).map_err(|_| Failure::Validation)?;
            if [
                "/endpoint.schema.json",
                "/error.schema.json",
                "/control.schema.json",
                "/client.schema.json",
                "/local-tools.schema.json",
                "/execution.schema.json",
                "/fleet.schema.json",
                "/builder.schema.json",
                "/runtime.schema.json",
                "/enterprise.schema.json",
                "/v2/discovery.schema.json",
            ]
            .iter()
            .any(|suffix| uri.ends_with(suffix))
            {
                for name in value["$defs"]
                    .as_object()
                    .ok_or(Failure::Validation)?
                    .keys()
                {
                    definitions.push((name.clone(), uri.to_string()));
                }
            } else if uri.ends_with("/v2/protocol.schema.json") {
                // Devices forward run-response frames like Tool Hosts; only these two v2 messages cross this boundary.
                for name in ["RunEventBatch", "RunEventAck"] {
                    definitions.push((name.to_string(), uri.to_string()));
                }
            }
            registry = registry.add(*uri, value).map_err(|error| {tracing::error!(event="contract_initialization",stage="registry",schema=%uri,error=%error);Failure::Validation})?;
        }
        let registry = registry.prepare().map_err(|error| {
            tracing::error!(event="contract_initialization",stage="references",error=%error);
            Failure::Validation
        })?;
        let mut validators = BTreeMap::new();
        for (name, uri) in definitions {
            let schema = serde_json::json!({"$schema":"https://json-schema.org/draft/2020-12/schema","$ref":format!("{uri}#/$defs/{name}")});
            validators.insert(
                name.clone(),
                jsonschema::options()
                    .with_registry(&registry)
                    .build(&schema)
                    .map_err(|error| {
                        tracing::error!(event="contract_initialization",schema=%name,error=%error);
                        Failure::Validation
                    })?,
            );
        }
        Ok(Self { validators })
    }
    pub fn decode<T: DeserializeOwned>(&self, name: &str, bytes: &[u8]) -> Result<T> {
        if bytes.len() > 131072 {
            return Err(Failure::Validation);
        }
        let value: serde_json::Value =
            crate::json::strict_json(bytes).map_err(|_| Failure::Validation)?;
        if !self
            .validators
            .get(name)
            .is_some_and(|v| v.is_valid(&value))
        {
            return Err(Failure::Validation);
        }
        serde_json::from_slice(bytes).map_err(|_| Failure::Validation)
    }
    pub fn encode<T: Serialize>(&self, name: &str, model: &T) -> Result<Vec<u8>> {
        let bytes = serde_json::to_vec(model).map_err(|_| Failure::Validation)?;
        let _: serde_json::Value = self.decode(name, &bytes)?;
        Ok(bytes)
    }
}

#[cfg(test)]
mod validation_tests {
    #[test]
    fn all_runtime_contracts_compile_without_external_references() {
        let _ = tracing_subscriber::fmt().with_test_writer().try_init();
        super::Contracts::new().unwrap();
    }
}
