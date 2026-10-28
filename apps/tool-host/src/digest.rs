// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Digests the permit pins. `argumentsDigest` is `sha256:` over the RFC 8785 form of the arguments,
//! exactly as Control's `Jcs.digest` computes it (no NFC, integers with every digit). Stored
//! documents (service profiles, globals) and runtime declarations use the descriptor canonical
//! form (NFC, then RFC 8785) of the `toolgate` CLI.

use serde_json::Value;

/// `sha256:` over the RFC 8785 bytes of `value`.
pub fn jcs(value: &Value) -> Result<String, String> {
    let mut out = String::new();
    write(value, &mut out)?;
    Ok(toolgate_cli::canonical::sha256_tagged(out.as_bytes()))
}

/// `sha256:` over the descriptor canonical form (NFC, then RFC 8785).
pub fn canonical(value: &Value) -> Result<String, String> {
    toolgate_cli::canonical::digest(value).map_err(|e| e.to_string())
}

fn write(value: &Value, out: &mut String) -> Result<(), String> {
    match value {
        Value::Null => out.push_str("null"),
        Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Value::Number(n) => {
            if let Some(u) = n.as_u64() {
                out.push_str(&u.to_string());
            } else if let Some(i) = n.as_i64() {
                out.push_str(&i.to_string());
            } else {
                match n.as_f64() {
                    Some(f) if f.is_finite() => {
                        out.push_str(ryu_js::Buffer::new().format_finite(f))
                    }
                    _ => return Err("a number is not finite".into()),
                }
            }
        }
        Value::String(s) => string(s, out),
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
            let mut keys: Vec<&String> = map.keys().collect();
            keys.sort_by_key(|k| k.encode_utf16().collect::<Vec<u16>>());
            out.push('{');
            for (i, key) in keys.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                string(key, out);
                out.push(':');
                write(&map[*key], out)?;
            }
            out.push('}');
        }
    }
    Ok(())
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
    use serde_json::json;

    #[test]
    fn arguments_digest_matches_controls_jcs() {
        // Same value and digest as Jcs.digest in the Java protocol module.
        let value = json!({"b": [1.0, 2, "x"], "a": {"z": null, "y": true}});
        let mut out = String::new();
        super::write(&value, &mut out).unwrap();
        assert_eq!(out, r#"{"a":{"y":true,"z":null},"b":[1,2,"x"]}"#);
        assert!(super::jcs(&value).unwrap().starts_with("sha256:"));
    }
}
