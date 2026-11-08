// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The canonical form of trigger descriptors (tool-sdk/spec/descriptor/README.md section 3): Unicode NFC, then
//! RFC 8785 (JCS), exactly as `toolgate pack` computes `triggerDescriptorDigest`. An executor checks a descriptor it
//! fetches against the digest its signed documents name.

use serde_json::Value;
use sha2::{Digest, Sha256};
use unicode_normalization::UnicodeNormalization;

/// A value with no canonical form: a non-finite number, an unsafe integer, or keys equal after NFC.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CanonicalError(pub String);

impl std::fmt::Display for CanonicalError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for CanonicalError {}

/// Largest integer JCS can represent exactly (IEEE 754 safe range).
const SAFE_INTEGER: u64 = 9_007_199_254_740_991;

/// The canonical bytes of a JSON value: every string and key in NFC, serialised with RFC 8785.
pub fn canonical_bytes(value: &Value) -> Result<Vec<u8>, CanonicalError> {
    let mut out = String::new();
    write(value, &mut out)?;
    Ok(out.into_bytes())
}

/// `"sha256:" + lowercase hex` of the canonical bytes.
pub fn digest(value: &Value) -> Result<String, CanonicalError> {
    let hash = Sha256::digest(canonical_bytes(value)?);
    Ok(format!(
        "sha256:{}",
        hash.iter().map(|b| format!("{b:02x}")).collect::<String>()
    ))
}

/// Sort key for RFC 8785: UTF-16 code units.
fn utf16_key(s: &str) -> Vec<u16> {
    s.encode_utf16().collect()
}

fn write(value: &Value, out: &mut String) -> Result<(), CanonicalError> {
    match value {
        Value::Null => out.push_str("null"),
        Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Value::Number(n) => out.push_str(&number(n)?),
        Value::String(s) => string(&s.nfc().collect::<String>(), out),
        Value::Array(items) => {
            out.push('[');
            for (i, item) in items.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                write(item, out)?;
            }
            out.push(']');
        }
        Value::Object(map) => {
            let mut entries: Vec<(String, &Value)> = map
                .iter()
                .map(|(k, v)| (k.nfc().collect::<String>(), v))
                .collect();
            entries.sort_by(|a, b| utf16_key(&a.0).cmp(&utf16_key(&b.0)));
            if entries.windows(2).any(|w| w[0].0 == w[1].0) {
                return Err(CanonicalError(
                    "two object keys are equal after NFC normalisation".into(),
                ));
            }
            out.push('{');
            for (i, (key, item)) in entries.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                string(key, out);
                out.push(':');
                write(item, out)?;
            }
            out.push('}');
        }
    }
    Ok(())
}

/// ECMAScript Number serialisation; integers outside the safe range and non-finite values are refused.
fn number(n: &serde_json::Number) -> Result<String, CanonicalError> {
    if let Some(u) = n.as_u64() {
        if u > SAFE_INTEGER {
            return Err(CanonicalError(
                "integer outside the IEEE 754 safe range".into(),
            ));
        }
        return Ok(u.to_string());
    }
    if let Some(i) = n.as_i64() {
        if i.unsigned_abs() > SAFE_INTEGER {
            return Err(CanonicalError(
                "integer outside the IEEE 754 safe range".into(),
            ));
        }
        return Ok(i.to_string());
    }
    match n.as_f64() {
        Some(f) if f.is_finite() => Ok(ryu_js::Buffer::new().format_finite(f).to_owned()),
        _ => Err(CanonicalError("number is not a finite double".into())),
    }
}

fn string(s: &str, out: &mut String) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{8}' => out.push_str("\\b"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\u{c}' => out.push_str("\\f"),
            '\r' => out.push_str("\\r"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn rfc8785_ordering_numbers_and_escapes() {
        let value = json!({"b": [1.0, -0.0, 1e21, 0.000001, 1e-7], "a": "\u{1f}\"\\\u{7f}", "\u{ff21}": 1, "\u{1f600}": 2});
        let text = String::from_utf8(canonical_bytes(&value).unwrap()).unwrap();
        assert_eq!(
            text,
            "{\"a\":\"\\u001f\\\"\\\\\u{7f}\",\"b\":[1,0,1e+21,0.000001,1e-7],\"\u{1f600}\":2,\"\u{ff21}\":1}"
        );
    }

    #[test]
    fn keys_and_strings_are_nfc_and_unsafe_integers_are_refused() {
        assert_eq!(
            canonical_bytes(&json!({"e\u{301}": 1})).unwrap(),
            canonical_bytes(&json!({"\u{e9}": 1})).unwrap()
        );
        assert!(canonical_bytes(&json!({"e\u{301}": 1, "\u{e9}": 2})).is_err());
        assert!(canonical_bytes(&json!(9_007_199_254_740_992u64)).is_err());
    }
}
