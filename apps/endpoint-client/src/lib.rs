// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Protected endpoint identity, enrollment and batched health reporting.
pub mod activity;
pub mod browser;
pub mod config;
pub mod connections;
pub mod contracts;
pub mod deployment;
pub mod diagnostics;
pub mod execution;
pub mod files;
pub mod identity;
pub mod install;
pub mod ipc;
mod json;

pub mod authorization_profile;
pub mod platform;
pub mod power;
mod remote;
pub mod runtime;
pub mod service;
mod socket;
pub mod storage;
pub mod tool_gateway;
pub mod transport;

/// Fixed, redacted failures; underlying TLS/key/OS error strings never cross IPC or logs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Failure {
    Validation,
    Unauthorized,
    Conflict,
    Unavailable,
    Revoked,
    EnrollmentRequired,
    Expired,
    Unsupported,
}
pub type Result<T> = std::result::Result<T, Failure>;
pub fn digest(bytes: &[u8]) -> String {
    use sha2::Digest;
    sha2::Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}
pub fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
