// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
use std::{path::PathBuf, sync::Arc};
use tool_host::{config::Config, control::Control, device::Device};
use toolgate_cli::canonical::sha256_tagged;
struct Area(PathBuf);
impl Area {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "tool-host-tenant-{}-{}",
            std::process::id(),
            rand::random::<u128>()
        ));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Area {
    fn drop(&mut self) {
        std::fs::remove_dir_all(&self.0).unwrap();
    }
}
fn config(area: &Area) -> Config {
    serde_json::from_value(serde_json::json!({"hostId":"host-1","listen":"127.0.0.1:8095","permitKeys":[{"kid":"test","publicKeyPath":area.0.join("public.pem")}],"permitIssuer":"control","storeDir":area.0.join("store"),"workDir":area.0.join("work"),"control":{"tenantId":"10000000-0000-4000-8000-000000000001","url":"https://127.0.0.1:1"},"enrollment":{"identityDir":area.0.join("identity")}})).unwrap()
}
#[tokio::test]
async fn preprovisioned_state_rejects_foreign_certificate_before_loading_a_key_and_keeps_its_origin(
) {
    let area = Area::new();
    let mut settings = config(&area);
    settings.enrollment = None;
    settings.control.identity_trust_directory = Some(area.0.join("workload-trust"));
    let certificate = area.0.join("certificate.pem");
    let key_path = area.0.join("key.pem");
    settings.control.client_certificate_path = Some(certificate.clone());
    settings.control.client_key_path = Some(key_path.clone());
    let key = rcgen::KeyPair::generate().unwrap();
    let leaf = |tenant: &str| {
        let mut params = rcgen::CertificateParams::new(Vec::<String>::new()).unwrap();
        let mut subject = rcgen::DistinguishedName::new();
        subject.push(rcgen::DnType::CommonName, "host-1");
        subject.push(rcgen::DnType::OrganizationalUnitName, tenant);
        subject.push(rcgen::DnType::OrganizationName, "server");
        params.distinguished_name = subject;
        params.self_signed(&key).unwrap().pem()
    };
    std::fs::write(&certificate, leaf("10000000-0000-4000-8000-000000000002")).unwrap();
    assert!(Control::new(&settings.control)
        .err()
        .unwrap()
        .contains("tenant identity mismatch"));
    assert!(!key_path.exists());
    assert!(!settings
        .control
        .identity_trust_directory
        .as_ref()
        .unwrap()
        .exists());
    std::fs::write(&certificate, leaf(&settings.control.tenant_id)).unwrap();
    std::fs::write(&key_path, key.serialize_pem()).unwrap();
    drop(Control::new(&settings.control).unwrap());
    let journal = settings
        .control
        .identity_trust_directory
        .as_ref()
        .unwrap()
        .join("authority.json");
    let before = std::fs::read(&journal).unwrap();
    settings.control.url = "https://foreign.example.test".into();
    assert!(Control::new(&settings.control).is_err());
    assert_eq!(std::fs::read(&journal).unwrap(), before);
}
#[tokio::test]
async fn installation_keeps_its_tenant_before_any_network_contact_and_rejects_rebinding() {
    let area = Area::new();
    let mut config = config(&area);
    let dir = config.enrollment.as_ref().unwrap().identity_dir.clone();
    drop(Device::open(&config, Arc::new(Control::new(&config.control).unwrap())).unwrap());
    let before = std::fs::read(dir.join("device.json")).unwrap();
    let key = sha256_tagged(&std::fs::read(dir.join("device-key.pem")).unwrap());
    drop(Device::open(&config, Arc::new(Control::new(&config.control).unwrap())).unwrap());
    config.control.tenant_id = "10000000-0000-4000-8000-000000000002".into();
    assert!(Device::open(&config, Arc::new(Control::new(&config.control).unwrap())).is_err());
    assert_eq!(std::fs::read(dir.join("device.json")).unwrap(), before);
    assert_eq!(
        sha256_tagged(&std::fs::read(dir.join("device-key.pem")).unwrap()),
        key
    );
}
#[tokio::test]
async fn unsupported_journal_is_rejected_without_creating_or_replacing_a_key() {
    let area = Area::new();
    let config = config(&area);
    let dir = &config.enrollment.as_ref().unwrap().identity_dir;
    std::fs::create_dir(dir).unwrap();
    let old = br#"{"identity":null,"challenge":null,"sequence":0}"#;
    std::fs::write(dir.join("device.json"), old).unwrap();
    assert!(Device::open(&config, Arc::new(Control::new(&config.control).unwrap())).is_err());
    assert_eq!(std::fs::read(dir.join("device.json")).unwrap(), old);
    assert!(!dir.join("device-key.pem").exists());
}
#[tokio::test]
async fn orphaned_prior_keys_are_not_adopted_into_a_new_tenant_binding() {
    let area = Area::new();
    let config = config(&area);
    let dir = &config.enrollment.as_ref().unwrap().identity_dir;
    std::fs::create_dir(dir).unwrap();
    let prior = b"unsupported prior development state";
    std::fs::write(dir.join("device-key.pem"), prior).unwrap();
    assert!(Device::open(&config, Arc::new(Control::new(&config.control).unwrap())).is_err());
    assert!(!dir.join("device.json").exists());
    assert_eq!(std::fs::read(dir.join("device-key.pem")).unwrap(), prior);
}
