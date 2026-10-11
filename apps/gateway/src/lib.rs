//! Stateless runtime authorization; no tool execution or implicit authorization.
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
pub mod application;
pub mod auth;
pub mod config;
mod diagnostics;
pub mod http;
pub mod limits;
mod mcp;
pub mod relay;
pub mod server;
pub mod validation;

use sha2::{Digest, Sha256};

/// Deterministic digest of bounded, normalized data; never a signature.
pub fn digest(bytes: &[u8]) -> String {
    use std::fmt::Write;
    let mut result = String::with_capacity(64);
    for byte in Sha256::digest(bytes) {
        write!(result, "{byte:02x}").expect("string write");
    }
    result
}

/// UNIX time for credential and policy expiry; clock failure denies access.
pub fn unix_ms() -> Option<u64> {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .ok()
        .and_then(|d| u64::try_from(d.as_millis()).ok())
}
