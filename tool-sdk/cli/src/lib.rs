// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The `toolgate` CLI library: canonical JSON, descriptor digests and equivalence, and the
//! `.tgpkg` packager and verifier (platform-requirements.md §4 and §7). Language SDKs produce descriptors; this
//! crate is the single implementation of canonical bytes, archive rules and digests.

pub mod archive;
pub mod canonical;
pub mod descriptor;
pub mod init;
pub mod package;
pub mod project;
pub mod schemas;
pub mod tool_schema;

use serde_json::Value;
use std::fmt;

/// The community guide to building tools with the SDK (`TOOL-SDK-GUIDE.md` at the repository
/// root), shipped in the binary so `toolgate guide` works offline.
pub const GUIDE: &str = include_str!("../../../TOOL-SDK-GUIDE.md");

#[derive(Debug)]
pub enum Error {
    /// A package or archive that fails a verification rule; nothing is repaired.
    Rejected(String),
    /// Input that cannot be packaged or digested.
    Invalid(String),
    Io(std::io::Error),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Rejected(reason) => write!(f, "rejected: {reason}"),
            Error::Invalid(reason) => write!(f, "invalid: {reason}"),
            Error::Io(error) => write!(f, "{error}"),
        }
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(error: std::io::Error) -> Self {
        Error::Io(error)
    }
}

/// Parses JSON, refusing duplicate object keys at any depth.
pub fn strict_json(bytes: &[u8]) -> Result<Value, Error> {
    use serde::de::{Deserialize, Deserializer, Error as _, MapAccess, SeqAccess, Visitor};
    struct Strict(Value);
    impl<'de> Deserialize<'de> for Strict {
        fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
            struct V;
            impl<'de> Visitor<'de> for V {
                type Value = Strict;
                fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
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
                fn visit_seq<A: SeqAccess<'de>>(self, mut a: A) -> Result<Strict, A::Error> {
                    let mut items = Vec::new();
                    while let Some(Strict(v)) = a.next_element()? {
                        items.push(v);
                    }
                    Ok(Strict(Value::Array(items)))
                }
                fn visit_map<A: MapAccess<'de>>(self, mut a: A) -> Result<Strict, A::Error> {
                    let mut values = serde_json::Map::new();
                    while let Some(key) = a.next_key::<String>()? {
                        let Strict(value) = a.next_value()?;
                        if values.insert(key, value).is_some() {
                            return Err(A::Error::custom("duplicate JSON key"));
                        }
                    }
                    Ok(Strict(Value::Object(values)))
                }
            }
            d.deserialize_any(V)
        }
    }
    let mut deserializer = serde_json::Deserializer::from_slice(bytes);
    let Strict(value) =
        Strict::deserialize(&mut deserializer).map_err(|e| Error::Invalid(format!("JSON: {e}")))?;
    deserializer
        .end()
        .map_err(|e| Error::Invalid(format!("JSON: {e}")))?;
    Ok(value)
}
