// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Concurrent gateway connections. The focused gateway is `client.json`; every gateway the
//! device switched away from keeps checking in from its parked profile directory.
use crate::{activity::Activity, config::Config, service::ClientService, Result};
use olo_toolgate_contracts::{ClientGatewayConnection, ClientHealth};
use std::{
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

/// Bounded so a status snapshot always fits one IPC frame.
pub const MAX_BACKGROUND: usize = 8;

struct Status {
    health: ClientHealth,
    server_name: Option<String>,
}
/// Lock-free view of one connection, published after each check-in cycle.
pub struct Connection {
    server_url: String,
    focused: bool,
    activity: Arc<Activity>,
    status: Mutex<Status>,
}
impl Connection {
    pub fn new(service: &ClientService, focused: bool) -> Arc<Self> {
        Arc::new(Self {
            server_url: service.server_url().to_owned(),
            focused,
            activity: service.activity.clone(),
            status: Mutex::new(Status {
                health: service.health(),
                server_name: service.server_name(),
            }),
        })
    }
    pub fn publish(&self, service: &ClientService) {
        let mut status = self.status.lock().unwrap_or_else(|e| e.into_inner());
        status.health = service.health();
        status.server_name = service.server_name();
    }
    fn snapshot(&self, activity: bool) -> ClientGatewayConnection {
        let status = self.status.lock().unwrap_or_else(|e| e.into_inner());
        ClientGatewayConnection {
            server_url: self.server_url.clone(),
            server_name: status.server_name.clone(),
            focused: self.focused,
            health: status.health.clone(),
            activity: activity.then(|| self.activity.snapshot()),
        }
    }
}
#[derive(Default)]
pub struct Connections {
    entries: Mutex<Vec<Arc<Connection>>>,
}
impl Connections {
    pub fn add(&self, connection: Arc<Connection>) {
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        entries.push(connection);
        entries.sort_by_key(|c| !c.focused);
    }
    /// Focused connection first. Activity is omitted from health polls to keep them small.
    pub fn snapshot(&self, activity: bool) -> Vec<ClientGatewayConnection> {
        self.entries
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .iter()
            .map(|c| c.snapshot(activity))
            .collect()
    }
}

/// Parked gateway profiles that keep a background connection, in a stable order.
/// Each runs with its own journal and activity log; local runtimes and package
/// deployment stay with the focused gateway only.
pub fn background(primary: &Config) -> Vec<Config> {
    let mut profiles: Vec<PathBuf> = std::fs::read_dir(primary.state_directory.join("profiles"))
        .map(|entries| {
            entries
                .filter_map(|entry| entry.ok().map(|entry| entry.path()))
                .collect()
        })
        .unwrap_or_default();
    profiles.sort();
    profiles
        .into_iter()
        .filter_map(|profile| parked(primary, &profile).ok().flatten())
        .take(MAX_BACKGROUND)
        .collect()
}
fn parked(primary: &Config, profile: &Path) -> Result<Option<Config>> {
    let snapshot = profile.join("client.json");
    if !snapshot.is_file() {
        return Ok(None);
    }
    let saved = Config::load(&snapshot)?;
    if saved.server_url == primary.server_url
        || saved.state_directory != primary.state_directory
        || profile.file_name() != Some(std::ffi::OsStr::new(&profile_name(&saved.server_url)))
    {
        return Ok(None);
    }
    let config = Config {
        deployment: None,
        execution: None,
        state_directory: profile.to_owned(),
        ipc_endpoint: primary.ipc_endpoint.clone(),
        authorized_peers: primary.authorized_peers.clone(),
        ..saved
    };
    config.validate()?;
    Ok(Some(config))
}
/// Same digest the installer uses for `profiles/<name>`.
pub fn profile_name(server: &str) -> String {
    use sha2::Digest;
    let digest = sha2::Sha256::digest(server.as_bytes());
    digest[..16].iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn config(state: &Path, server: &str) -> Config {
        let config = Config {
            deployment: None,
            execution: None,
            gateway: None,
            server_url: server.into(),
            state_directory: state.to_owned(),
            ipc_endpoint: state.join("client.sock").to_string_lossy().into_owned(),
            authorized_peers: vec!["0".into()],
            ca_certificate_path: None,
            request_timeout_seconds: 10,
            local_console_url: None,
        };
        #[cfg(windows)]
        let config = Config {
            ipc_endpoint: crate::install::ipc_endpoint(),
            authorized_peers: vec![crate::platform::windows::current_sid().unwrap()],
            ..config
        };
        config.validate().unwrap();
        config
    }
    #[test]
    fn parked_profiles_become_background_connections() {
        let state = crate::storage::test_directory("olo-connections");
        let primary = config(&state, "https://primary.example");
        for server in ["https://second.example", "https://primary.example"] {
            let profile = state.join("profiles").join(profile_name(server));
            std::fs::create_dir_all(&profile).unwrap();
            std::fs::write(
                profile.join("client.json"),
                serde_json::to_vec(&config(&state, server)).unwrap(),
            )
            .unwrap();
        }
        // A snapshot under the wrong directory name is ignored.
        let stray = state.join("profiles").join("stray");
        std::fs::create_dir_all(&stray).unwrap();
        std::fs::write(
            stray.join("client.json"),
            serde_json::to_vec(&config(&state, "https://third.example")).unwrap(),
        )
        .unwrap();
        let found = background(&primary);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].server_url, "https://second.example");
        assert_eq!(
            found[0].state_directory,
            state
                .join("profiles")
                .join(profile_name("https://second.example"))
        );
        assert_eq!(found[0].ipc_endpoint, primary.ipc_endpoint);
        std::fs::remove_dir_all(state).unwrap();
    }
    #[test]
    fn gateway_names_match_the_contract() {
        assert!(crate::service::valid_server_name("Lab Gateway (EU)"));
        assert!(!crate::service::valid_server_name(""));
        assert!(!crate::service::valid_server_name(" leading"));
        assert!(!crate::service::valid_server_name("bad\nname"));
        assert!(!crate::service::valid_server_name(&"a".repeat(65)));
    }
}
