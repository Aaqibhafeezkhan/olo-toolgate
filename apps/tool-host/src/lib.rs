// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The ToolGate Tool Host (platform-requirements.md §11, §13.1): admits a dispatch only with a Control-signed
//! invocation permit, claims it with Control, and runs the tool from the exact package, service
//! profile and globals the permit pins, with every route call going through its broker.

pub mod broker;
mod broker_authority;
pub mod broker_process;
pub mod config;
pub mod control;
pub mod device;
pub mod digest;
pub mod egress;
pub mod events;
pub mod lease;
pub mod mcp;
pub mod mcp_call;
pub mod mcp_scan;
pub mod outbox;
pub mod permit;
mod request_binding;
pub mod runner;
pub mod sandbox;
pub mod server;
pub mod store;
pub mod stream;
pub mod trace;
mod worker_protocol;
mod workload_identity;

/// Milliseconds since the Unix epoch.
pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// `bytes` random bytes as lowercase hex.
pub fn random_hex(bytes: usize) -> String {
    use ring::rand::SecureRandom;
    let mut buffer = vec![0u8; bytes];
    ring::rand::SystemRandom::new()
        .fill(&mut buffer)
        .expect("the system random source failed");
    buffer.iter().map(|b| format!("{b:02x}")).collect()
}
