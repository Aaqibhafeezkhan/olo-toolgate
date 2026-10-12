// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Compile embedded canonical schemas once, without remote retrieval.
use jsonschema::Validator;
use olo_toolgate_contracts::CANONICAL_SCHEMAS;
use serde_json::{json, Value};
use std::collections::BTreeMap;

/// Offline canonical validation at shared contract boundaries.
pub struct Contracts {
    validators: BTreeMap<&'static str, Validator>,
}

impl Contracts {
    /// All references resolve from the shared package; no network or file I/O.
    pub fn new() -> Result<Self, &'static str> {
        let mut validators = BTreeMap::new();
        let mut registry = jsonschema::Registry::new();
        for (uri, content) in CANONICAL_SCHEMAS {
            let schema: Value =
                serde_json::from_str(content).map_err(|_| "invalid embedded schema")?;
            registry = registry
                .add(*uri, schema)
                .map_err(|_| "invalid embedded resource")?;
        }
        let registry = registry
            .prepare()
            .map_err(|_| "invalid embedded registry")?;
        for (name, file) in [
            ("AuthorizationRequest", "v1/runtime"),
            ("RequestContext", "v2/common"),
            ("EnterpriseInvocationRequest", "v1/enterprise"),
            ("EnterpriseInvocation", "v1/enterprise"),
            ("EnterpriseReservationRequest", "v1/enterprise"),
            ("EnterpriseReservation", "v1/enterprise"),
            ("EnterpriseAuthorizationOutcome", "v1/enterprise"),
            ("LocalToolCatalog", "v1/endpoint"),
            ("RemoteToolSubmission", "v1/endpoint"),
            ("RemoteToolResponse", "v1/endpoint"),
            ("PermitReservationRequest", "v2/permit"),
            ("SignedInvocationPermit", "v2/permit"),
            ("AdmissionResponse", "v2/protocol"),
            ("ResultMessage", "v2/protocol"),
            ("ToolHostDispatchStatus", "v2/protocol"),
        ] {
            let options = jsonschema::options().with_registry(&registry);
            let schema = json!({"$schema":"https://json-schema.org/draft/2020-12/schema", "$ref":format!("https://schemas.ololabs.io/toolgate/{file}.schema.json#/$defs/{name}")});
            validators.insert(
                name,
                options
                    .build(&schema)
                    .map_err(|_| "invalid contract references")?,
            );
        }
        Ok(Self { validators })
    }

    /// Errors disclose no instance data or schema exception text.
    pub fn valid(&self, name: &str, value: &Value) -> bool {
        self.validators.get(name).is_some_and(|v| v.is_valid(value))
    }
}

/// Reject duplicate keys recursively and sort keys for deterministic digests.
pub fn strict_json(bytes: &[u8]) -> Result<Value, serde_json::Error> {
    use serde::Deserialize;
    struct Strict(Value);
    impl<'de> Deserialize<'de> for Strict {
        fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
            struct Visitor;
            impl<'de> serde::de::Visitor<'de> for Visitor {
                type Value = Strict;
                fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                    f.write_str("JSON without duplicate keys")
                }
                fn visit_bool<E: serde::de::Error>(self, v: bool) -> Result<Strict, E> {
                    Ok(Strict(Value::Bool(v)))
                }
                fn visit_i64<E: serde::de::Error>(self, v: i64) -> Result<Strict, E> {
                    Ok(Strict(v.into()))
                }
                fn visit_u64<E: serde::de::Error>(self, v: u64) -> Result<Strict, E> {
                    Ok(Strict(v.into()))
                }
                fn visit_f64<E: serde::de::Error>(self, v: f64) -> Result<Strict, E> {
                    serde_json::Number::from_f64(v)
                        .map(|n| Strict(Value::Number(n)))
                        .ok_or_else(|| E::custom("invalid number"))
                }
                fn visit_str<E: serde::de::Error>(self, v: &str) -> Result<Strict, E> {
                    Ok(Strict(Value::String(v.into())))
                }
                fn visit_string<E: serde::de::Error>(self, v: String) -> Result<Strict, E> {
                    Ok(Strict(Value::String(v)))
                }
                fn visit_unit<E: serde::de::Error>(self) -> Result<Strict, E> {
                    Ok(Strict(Value::Null))
                }
                fn visit_seq<A: serde::de::SeqAccess<'de>>(
                    self,
                    mut a: A,
                ) -> Result<Strict, A::Error> {
                    let mut items = Vec::new();
                    while let Some(Strict(v)) = a.next_element()? {
                        items.push(v);
                    }
                    Ok(Strict(Value::Array(items)))
                }
                fn visit_map<A: serde::de::MapAccess<'de>>(
                    self,
                    mut a: A,
                ) -> Result<Strict, A::Error> {
                    use serde::de::Error;
                    let mut values = BTreeMap::new();
                    while let Some(key) = a.next_key::<String>()? {
                        let Strict(value) = a.next_value()?;
                        if values.insert(key, value).is_some() {
                            return Err(A::Error::custom("duplicate JSON key"));
                        }
                    }
                    Ok(Strict(Value::Object(values.into_iter().collect())))
                }
            }
            d.deserialize_any(Visitor)
        }
    }
    serde_json::from_slice::<Strict>(bytes).map(|v| v.0)
}
