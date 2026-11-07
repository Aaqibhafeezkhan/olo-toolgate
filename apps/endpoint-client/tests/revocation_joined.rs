// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Actual fixed-origin Control + Native service owner + managed engine fixture.
//! No production enrollment, T5, signature or runtime implementation is replaced.
use olo_toolgate_client::{
    config::Config, contracts::Contracts, service::ClientService, storage::ProtectedStore,
    transport::HttpsControl,
};
use std::{path::PathBuf, sync::Arc};
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "owned fresh Control and engine: tools/quickstart/check.py --revocation --native-revocation"]
async fn native_owner_joins_current_control_and_managed_runtime() {
    let path = PathBuf::from(
        std::env::var_os("TOOLGATE_NATIVE_REVOCATION_CONFIG")
            .expect("explicit owned fixture config"),
    );
    let mut config: Config = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
    let settings = config.execution.as_mut().unwrap();
    for tool in &mut settings.tools {
        tool.authorization_profile.tool.package_digest =
            olo_toolgate_client::authorization_profile::managed_package_digest(
                tool,
                &settings.runtimes[0],
            )
            .unwrap();
    }
    config.validate().unwrap();
    let root = path.parent().unwrap();
    std::fs::write(
        root.join("profile.json"),
        serde_json::to_vec(&config.execution.as_ref().unwrap().tools[0].authorization_profile)
            .unwrap(),
    )
    .unwrap();
    if std::env::var("TOOLGATE_NATIVE_REVOCATION_CONFIG_ONLY").as_deref() == Ok("1") {
        return; // Explicit fixture preflight, never execution or delivery evidence.
    }
    let store = ProtectedStore::open(config.state_directory.clone()).unwrap();
    let contracts = Arc::new(Contracts::new().unwrap());
    let mut service = ClientService::open(config.clone(), store, |key| {
        Ok(Arc::new(HttpsControl::new(
            config.clone(),
            key,
            contracts.clone(),
        )?))
    })
    .unwrap();
    let prompt = service.enroll().await.unwrap();
    std::fs::write(
        root.join("prompt.json"),
        serde_json::to_vec(&prompt).unwrap(),
    )
    .unwrap();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(600);
    let mut prepared = false;
    while !root.join("stop").exists() {
        assert!(
            std::time::Instant::now() < deadline,
            "bounded owned Native qualification"
        );
        let started = tokio::time::Instant::now();
        let _ = service.tick().await; // Real outage handling, never a fabricated renewal.
        if service.health().ready && !prepared {
            let health = service.prepare_runtimes().await.unwrap();
            assert!(health.ready);
            prepared = true;
        }
        std::fs::write(root.join("health.json"),serde_json::to_vec(
            &serde_json::json!({"device":service.health(),"runtime":service.runtime_health().ok()})
        ).unwrap()).unwrap();
        // Match the real service owner's interval, including outage backoff.
        let delay = std::time::Duration::from_millis(service.next_delay_millis());
        let until = started + delay;
        while tokio::time::Instant::now() < until && !root.join("stop").exists() {
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
    }
    service.shutdown_runtimes().await.unwrap();
}
