// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Owner power switch. Only the authenticated local IPC peer changes it; no gateway, console or
//! policy reply can, so a device the owner switched off stays off until the owner switches it on.
use crate::{storage::ProtectedStore, Result};
use olo_toolgate_contracts::DevicePower;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Saved {
    on: bool,
}
/// On after installation. The owner's choice lives in the device root, beside the device key,
/// so it survives restarts, upgrades and gateway switches; a purge returns it to on.
pub struct Power {
    store: Option<ProtectedStore>,
    on: tokio::sync::watch::Sender<bool>,
}
impl Power {
    pub fn open(directory: std::path::PathBuf) -> Result<Arc<Self>> {
        let store = ProtectedStore::open(directory)?;
        // An unreadable choice fails closed: the owner can always switch on again locally.
        let on = match store.read("power.json") {
            Ok(None) => true,
            Ok(Some(bytes)) => serde_json::from_slice::<Saved>(&bytes).is_ok_and(|s| s.on),
            Err(failure) => {
                tracing::warn!(event="device_power",result="unreadable",error=?failure);
                false
            }
        };
        Ok(Arc::new(Self {
            store: Some(store),
            on: tokio::sync::watch::channel(on).0,
        }))
    }
    /// Unsaved and on, for a service opened outside the protected runtime.
    pub fn volatile() -> Arc<Self> {
        Arc::new(Self {
            store: None,
            on: tokio::sync::watch::channel(true).0,
        })
    }
    pub fn is_on(&self) -> bool {
        *self.on.borrow()
    }
    pub fn state(&self) -> DevicePower {
        if self.is_on() {
            DevicePower::On
        } else {
            DevicePower::Off
        }
    }
    /// Wakes every gateway connection when the owner switches.
    pub fn subscribe(&self) -> tokio::sync::watch::Receiver<bool> {
        self.on.subscribe()
    }
    /// Saved before it takes effect, so a crash never resumes check-ins the owner switched off.
    pub fn set(&self, on: bool) -> Result<DevicePower> {
        if let Some(store) = &self.store {
            store.write(
                "power.json",
                &serde_json::to_vec(&Saved { on }).map_err(|_| crate::Failure::Validation)?,
            )?;
        }
        self.on
            .send_if_modified(|value| std::mem::replace(value, on) != on);
        Ok(self.state())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn on_by_default_and_the_owners_choice_survives_a_restart() {
        let state = crate::storage::test_directory("olo-power");
        let power = Power::open(state.clone()).unwrap();
        assert_eq!(power.state(), DevicePower::On);
        let mut watch = power.subscribe();
        assert_eq!(power.set(false).unwrap(), DevicePower::Off);
        assert!(watch.has_changed().unwrap());
        watch.borrow_and_update();
        // Switching to the current state wakes no connection.
        power.set(false).unwrap();
        assert!(!watch.has_changed().unwrap());
        drop(power);
        assert!(!Power::open(state.clone()).unwrap().is_on());
        Power::open(state.clone()).unwrap().set(true).unwrap();
        assert!(Power::open(state.clone()).unwrap().is_on());
        // A damaged choice fails closed.
        ProtectedStore::open(state.clone())
            .unwrap()
            .write("power.json", b"{\"on\":\"yes\"}")
            .unwrap();
        assert!(!Power::open(state.clone()).unwrap().is_on());
        std::fs::remove_dir_all(state).unwrap();
    }
}
