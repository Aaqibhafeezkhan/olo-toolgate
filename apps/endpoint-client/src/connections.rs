// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The single installed tenant connection, exposed as a bounded local status view.
use crate::{activity::Activity, service::ClientService};
use olo_toolgate_contracts::{ClientGatewayConnection, ClientHealth};
use std::sync::{Arc, Mutex};

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

#[cfg(test)]
mod tests {
    #[test]
    fn gateway_names_match_the_contract() {
        assert!(crate::service::valid_server_name("Lab Gateway (EU)"));
        assert!(!crate::service::valid_server_name(""));
        assert!(!crate::service::valid_server_name(" leading"));
        assert!(!crate::service::valid_server_name("bad\nname"));
        assert!(!crate::service::valid_server_name(&"a".repeat(65)));
    }
}
