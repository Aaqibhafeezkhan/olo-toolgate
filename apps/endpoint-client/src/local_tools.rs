// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Keeps the device administrator's built-in tool selection working without intervention.
//!
//! Installed profiles describe this executable and carry no grants; the Gateway still
//! authorizes every call. Two events used to silently disable the selected tools until an
//! administrator re-ran the preparation step:
//! - a client update or reinstall changes the executable, so its profiles no longer match;
//! - switching or repairing a gateway clears the gateway's tool settings.
//!
//! At service start the selection is refreshed for this build and remembered in the
//! device state root, and a gateway without tool settings gets it back. Site-specific
//! values are never carried over: the restored settings point at the current gateway and
//! its CA, use a fresh inert local token, and leave web search unconfigured. Deleting
//! `local-tools.json` with the tool settings disables the selection.
use crate::{builtins::Settings, config::Config, hotfolder, storage::ProtectedStore, Result};
use olo_toolgate_contracts::InstalledAuthorizationProfile;
use serde::{Deserialize, Serialize};
use std::path::Path;

const SELECTION: &str = "local-tools.json";
const TOKEN: &str = "local-tools-token";

#[derive(Clone, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Selection {
    hotfolder: hotfolder::Settings,
    tool_ids: Vec<String>,
}

/// What `heal` changed, for the service log and activity.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Healed {
    pub refreshed: usize,
    pub restored: bool,
}

/// Heals `config` in place. `device_root` is the primary state directory, shared by every
/// gateway this device is connected to.
pub fn heal(config: &mut Config, device_root: &Path) -> Result<Healed> {
    heal_with(
        config,
        device_root,
        &crate::authorization_profile::builtin_profiles()?,
    )
}

fn heal_with(
    config: &mut Config,
    device_root: &Path,
    current: &[InstalledAuthorizationProfile],
) -> Result<Healed> {
    let store = ProtectedStore::open(device_root.to_owned())?;
    let mut healed = Healed::default();
    match &mut config.tools {
        Some(settings) => {
            healed.refreshed = refresh(settings, current);
            let selection = Selection {
                hotfolder: settings.hotfolder.clone(),
                tool_ids: settings
                    .authorization_profiles
                    .iter()
                    .filter(|p| current.iter().any(|c| c.tool.id == p.tool.id))
                    .map(|p| p.tool.id.clone())
                    .collect(),
            };
            if !selection.tool_ids.is_empty() && remembered(&store)?.as_ref() != Some(&selection) {
                store.write(
                    SELECTION,
                    &serde_json::to_vec(&selection).map_err(|_| crate::Failure::Validation)?,
                )?;
            }
        }
        None => {
            let Some(selection) = remembered(&store)?.or_else(|| parked(device_root, current))
            else {
                return Ok(healed);
            };
            // A missing folder means the administrator removed it; never recreate it here.
            if !selection.hotfolder.root.is_dir() {
                return Ok(healed);
            }
            if store.read(TOKEN)?.is_none() {
                // An inert local IPC credential, as the preparation step creates. Remote
                // operations use the enrolled device's mTLS identity.
                let token = format!("{}{}", crate::identity::nonce()?, crate::identity::nonce()?);
                store.write(TOKEN, token.as_bytes())?;
            }
            let settings = Settings {
                hotfolder: selection.hotfolder.clone(),
                gateway_url: config.server_url.clone(),
                gateway_token_path: device_root.join(TOKEN),
                gateway_ca_path: config.ca_certificate_path.clone(),
                device_id: "remote".into(),
                authorization_profiles: current
                    .iter()
                    .filter(|c| selection.tool_ids.contains(&c.tool.id))
                    .cloned()
                    .collect(),
                web_search_token_path: None,
                web_search_allowed_domains: vec![],
            };
            settings.validate()?;
            config.tools = Some(settings);
            healed.restored = true;
        }
    }
    Ok(healed)
}

/// Replaces built-in profiles exported by another build with this build's own.
fn refresh(settings: &mut Settings, current: &[InstalledAuthorizationProfile]) -> usize {
    let mut refreshed = 0;
    for profile in &mut settings.authorization_profiles {
        let Some(own) = current.iter().find(|c| c.tool.id == profile.tool.id) else {
            continue;
        };
        if profile.tool.version != own.tool.version
            || profile.tool.package_digest != own.tool.package_digest
            || profile.tool.definition.input_schema != own.tool.definition.input_schema
        {
            *profile = own.clone();
            refreshed += 1;
        }
    }
    refreshed
}

/// A selection made on another gateway before it was remembered (an older client build).
fn parked(device_root: &Path, current: &[InstalledAuthorizationProfile]) -> Option<Selection> {
    let mut profiles: Vec<_> = std::fs::read_dir(device_root.join("profiles"))
        .ok()?
        .filter_map(|entry| entry.ok().map(|entry| entry.path().join("client.json")))
        .collect();
    profiles.sort();
    profiles.into_iter().find_map(|path| {
        let tools = Config::load(&path).ok()?.tools?;
        let tool_ids: Vec<_> = tools
            .authorization_profiles
            .iter()
            .filter(|p| current.iter().any(|c| c.tool.id == p.tool.id))
            .map(|p| p.tool.id.clone())
            .collect();
        (!tool_ids.is_empty()).then_some(Selection {
            hotfolder: tools.hotfolder,
            tool_ids,
        })
    })
}

fn remembered(store: &ProtectedStore) -> Result<Option<Selection>> {
    // An unreadable selection is ignored rather than stopping the service.
    Ok(store
        .read(SELECTION)?
        .and_then(|bytes| serde_json::from_slice(&bytes).ok()))
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Deny;
    impl crate::builtins::AuthorizationPort for Deny {
        fn authorize(
            &self,
            _request: olo_toolgate_contracts::AuthorizationRequest,
        ) -> crate::transport::Call<'_, ()> {
            Box::pin(async { Err(crate::Failure::Unauthorized) })
        }
    }

    fn directory() -> std::path::PathBuf {
        crate::storage::test_directory("local-tools")
    }

    fn config(root: &Path) -> Config {
        let mut config: Config = serde_json::from_value(serde_json::json!({
            "serverUrl": "https://localhost:18451",
            "stateDirectory": root,
            "ipcEndpoint": root.join("client.sock"),
            "authorizedPeers": ["1000"],
            "requestTimeoutSeconds": 10
        }))
        .unwrap();
        config.ca_certificate_path = Some(root.join("gateway-ca.pem"));
        #[cfg(windows)]
        {
            config.ipc_endpoint = crate::install::ipc_endpoint();
            config.authorized_peers = vec![crate::platform::windows::current_sid().unwrap()];
        }
        config.validate().unwrap();
        config
    }

    fn profiles() -> Vec<InstalledAuthorizationProfile> {
        crate::authorization_profile::builtin_profiles().unwrap()
    }

    fn selected(root: &Path, ids: &[&str]) -> Settings {
        let folder = root.join("hotfolder");
        if !folder.exists() {
            ProtectedStore::open(folder.clone()).unwrap();
        }
        Settings {
            hotfolder: hotfolder::Settings {
                root: folder,
                max_file_bytes: 65536,
                max_entries: 256,
                extensions: vec!["txt".into()],
            },
            gateway_url: "https://localhost:18450".into(),
            gateway_token_path: root.join("old-site-token"),
            gateway_ca_path: Some(root.join("old-site-ca.pem")),
            device_id: "remote".into(),
            authorization_profiles: profiles()
                .into_iter()
                .filter(|p| ids.contains(&p.tool.id.as_str()))
                .collect(),
            web_search_token_path: Some(root.join("old-site-search")),
            web_search_allowed_domains: vec!["example.com".into()],
        }
    }

    #[test]
    fn profiles_from_another_build_are_replaced_with_this_builds() {
        let root = directory();
        let mut config = config(&root);
        let mut settings = selected(&root, &["hotfolder.list", "hotfolder.read_text"]);
        settings.authorization_profiles[0].tool.package_digest = "0".repeat(64);
        settings.authorization_profiles[1].tool.version = "0.9.0".into();
        config.tools = Some(settings);
        let healed = heal_with(&mut config, &root, &profiles()).unwrap();
        assert_eq!(
            healed,
            Healed {
                refreshed: 2,
                restored: false
            }
        );
        let current = profiles();
        for profile in &config.tools.unwrap().authorization_profiles {
            assert!(current.contains(profile));
        }
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn cleared_gateway_settings_get_the_remembered_selection_for_the_current_gateway() {
        let root = directory();
        let mut config = config(&root);
        config.tools = Some(selected(
            &root,
            &["hotfolder.list", "client.read_log_entry"],
        ));
        assert_eq!(
            heal_with(&mut config, &root, &profiles()).unwrap(),
            Healed::default()
        );
        // Switch gateway or Repair gateway connection.
        config.tools = None;
        let healed = heal_with(&mut config, &root, &profiles()).unwrap();
        assert!(healed.restored);
        let tools = config.tools.unwrap();
        let mut ids: Vec<_> = tools
            .authorization_profiles
            .iter()
            .map(|p| p.tool.id.as_str())
            .collect();
        ids.sort();
        assert_eq!(ids, ["client.read_log_entry", "hotfolder.list"]);
        assert_eq!(tools.gateway_url, "https://localhost:18451");
        assert_eq!(tools.gateway_ca_path, Some(root.join("gateway-ca.pem")));
        assert_eq!(tools.gateway_token_path, root.join(TOKEN));
        assert!(
            tools.web_search_token_path.is_none() && tools.web_search_allowed_domains.is_empty()
        );
        assert!(crate::tool_gateway::secret(&tools.gateway_token_path).is_ok());
        // The service can build its executor from the restored settings.
        let executor = crate::builtins::Executor::new(&tools, std::sync::Arc::new(Deny)).unwrap();
        assert_eq!(executor.catalog().len(), 2);
        // Windows refuses to remove files the executor still holds open.
        drop(executor);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn a_selection_on_a_parked_gateway_is_adopted_when_none_is_remembered() {
        let root = directory();
        let parked = root.join("profiles").join("debug");
        std::fs::create_dir_all(&parked).unwrap();
        let mut saved = config(&root);
        saved.server_url = "https://localhost:18450".into();
        saved.tools = Some(selected(&root, &["hotfolder.write_text"]));
        std::fs::write(
            parked.join("client.json"),
            serde_json::to_vec(&saved).unwrap(),
        )
        .unwrap();
        let mut config = config(&root);
        assert!(heal_with(&mut config, &root, &profiles()).unwrap().restored);
        let tools = config.tools.unwrap();
        assert_eq!(tools.authorization_profiles.len(), 1);
        assert_eq!(tools.gateway_url, "https://localhost:18451");
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn nothing_is_restored_without_a_selection_or_its_folder() {
        let root = directory();
        let mut config = config(&root);
        assert_eq!(
            heal_with(&mut config, &root, &profiles()).unwrap(),
            Healed::default()
        );
        assert!(config.tools.is_none());
        config.tools = Some(selected(&root, &["hotfolder.list"]));
        heal_with(&mut config, &root, &profiles()).unwrap();
        config.tools = None;
        std::fs::remove_dir_all(root.join("hotfolder")).unwrap();
        assert_eq!(
            heal_with(&mut config, &root, &profiles()).unwrap(),
            Healed::default()
        );
        assert!(config.tools.is_none());
        std::fs::remove_dir_all(root).unwrap();
    }
}
