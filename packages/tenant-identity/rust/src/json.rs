// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Strict bounded-wire JSON parsing rejects ambiguous duplicate object keys.
use serde_json::Value;
use std::collections::BTreeMap;
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
