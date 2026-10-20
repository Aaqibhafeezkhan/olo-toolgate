// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Immutable administrator configuration. Unknown settings are errors.
use crate::validation::Contracts;
use serde::{Deserialize, Serialize};
use std::{io::Read, net::SocketAddr, path::Path};

/// Capacity protection is per replica, independent of authorization correctness.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Limits {
    pub max_body_bytes: usize,
    pub max_header_bytes: usize,
    pub max_concurrent_requests: usize,
    pub max_connections: usize,
    pub requests_per_second: u32,
    pub request_timeout_ms: u64,
    pub connection_timeout_ms: u64,
    pub shutdown_timeout_ms: u64,
    pub audit_queue_capacity: usize,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_body_bytes: 65536,
            max_header_bytes: 8192,
            max_concurrent_requests: 128,
            max_connections: 256,
            requests_per_second: 1000,
            request_timeout_ms: 20000,
            connection_timeout_ms: 35000,
            shutdown_timeout_ms: 35000,
            audit_queue_capacity: 256,
        }
    }
}

/// Trusted config file has precedence over compiled defaults. Environment only
/// selects its path and the separate credential file; no policy overrides exist.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Config {
    pub tenant_hosts: Vec<String>,
    pub control: crate::relay::RelayConfig,
    pub listen: SocketAddr,
    pub management_listen: SocketAddr,
    pub trusted_tls_proxy: bool,
    pub allowed_origins: Vec<String>,
    #[serde(default)]
    pub limits: Limits,
    /// Tool Hosts this Gateway dispatches to; any other device's calls go through the relay.
    #[serde(default)]
    pub tool_hosts: Vec<crate::toolhost::ToolHostRoute>,
}

impl Config {
    /// Invalid or unbounded configuration prevents startup.
    pub fn validate(&self, _contracts: &Contracts, _now: u64) -> Result<(), &'static str> {
        self.control.validate()?;
        if self.tenant_hosts.is_empty() || self.tenant_hosts.len() > 16 {
            return Err("explicit tenant authorities required");
        }
        let mut authorities = std::collections::BTreeSet::new();
        for host in &self.tenant_hosts {
            if !authorities
                .insert(crate::tenancy::authority(host).map_err(|_| "invalid tenant authority")?)
            {
                return Err("duplicate tenant authority");
            }
        }
        if self.tool_hosts.len() > 64 {
            return Err("at most 64 Tool Hosts");
        }
        let mut hosts = std::collections::BTreeSet::new();
        for route in &self.tool_hosts {
            route.validate()?;
            if !hosts.insert(&route.host_id) {
                return Err("each Tool Host id is listed once");
            }
        }
        if self.limits.request_timeout_ms < 10000 {
            return Err("online authority requires a ten-second request budget");
        }
        if self.listen.port() == 0
            || self.management_listen.port() == 0
            || self.listen == self.management_listen
            || (!self.listen.ip().is_loopback() && !self.trusted_tls_proxy)
        {
            return Err("separate listeners and trusted TLS termination required");
        }
        let l = &self.limits;
        if !(256..=1_048_576).contains(&l.max_body_bytes)
            || !(1024..=32768).contains(&l.max_header_bytes)
            || !(1..=1024).contains(&l.max_concurrent_requests)
            || !(1..=4096).contains(&l.max_connections)
            || l.max_connections < l.max_concurrent_requests
            || !(1..=100_000).contains(&l.requests_per_second)
            || !(10..=30000).contains(&l.request_timeout_ms)
            || !(100..=60000).contains(&l.connection_timeout_ms)
            || l.connection_timeout_ms < l.request_timeout_ms
            || !(100..=120000).contains(&l.shutdown_timeout_ms)
            || l.shutdown_timeout_ms < l.request_timeout_ms
            || !(1..=4096).contains(&l.audit_queue_capacity)
        {
            return Err("invalid capacity limits");
        }
        if self.allowed_origins.len() > 32
            || self.allowed_origins.iter().any(|o| {
                o.len() > 256
                    || !o.starts_with("https://")
                    || o.contains(['\r', '\n', '*'])
                    || o.ends_with('/')
                    || o.parse::<axum::http::Uri>().ok().is_none_or(|u| {
                        u.scheme_str() != Some("https")
                            || u.authority().is_none_or(|a| a.as_str().contains('@'))
                            || u.path() != "/"
                            || u.query().is_some()
                    })
            })
        {
            return Err("origins must be exact HTTPS origins without a trailing slash");
        }
        Ok(())
    }
}

/// Bound startup file reads, including files whose metadata size is misleading.
pub fn read_json<T: serde::de::DeserializeOwned>(path: &Path) -> Result<T, &'static str> {
    serde_json::from_slice(&read_bytes(path)?).map_err(|_| "invalid configuration JSON")
}

/// Bounded read of a mounted file; the credential reload uses the same bound as startup.
pub fn read_bytes(path: &Path) -> Result<Vec<u8>, &'static str> {
    let file = std::fs::File::open(path).map_err(|_| "configuration file unavailable")?;
    let mut bytes = Vec::new();
    file.take(1_048_577)
        .read_to_end(&mut bytes)
        .map_err(|_| "configuration read failed")?;
    if bytes.len() > 1_048_576 {
        return Err("configuration file too large");
    }
    Ok(bytes)
}
