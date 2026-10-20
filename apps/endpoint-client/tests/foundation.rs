// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Custody, wire and outage regressions. Test transports never enter production wiring.
use olo_toolgate_client::{
    config::Config,
    contracts::Contracts,
    identity::DeviceKey,
    ipc,
    service::ClientService,
    storage::ProtectedStore,
    transport::{Call, ControlPort},
    Failure,
};
use olo_toolgate_contracts::v2::SignedTenantDiscovery;
use olo_toolgate_contracts::*;
use std::{path::PathBuf, sync::Arc};
struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        #[cfg(windows)]
        let base = PathBuf::from(std::env::var_os("ProgramData").unwrap());
        // macOS /tmp is a symlink; exercise custody through its real protected parent.
        #[cfg(target_os = "macos")]
        let base = PathBuf::from("/private/tmp");
        #[cfg(all(unix, not(target_os = "macos")))]
        let base = std::env::temp_dir();
        let path = base.join(format!(
            "toolgate-client-{}",
            olo_toolgate_client::identity::nonce().unwrap()
        ));
        let builder = std::fs::DirBuilder::new();
        #[cfg(unix)]
        let mut builder = builder;
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        builder.create(&path).unwrap();
        #[cfg(windows)]
        olo_toolgate_client::platform::windows::protect_acl(&path).unwrap();
        Self(path)
    }
    fn store(&self) -> ProtectedStore {
        ProtectedStore::open(self.0.clone()).unwrap()
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        std::fs::remove_dir_all(&self.0).unwrap();
    }
}
struct Offline;
#[test]
fn activity_is_durable_bounded_redacted_and_interruption_is_not_success() {
    use olo_toolgate_client::activity::Activity;
    let directory = Directory::new();
    let activity = Activity::open(directory.0.clone()).unwrap();
    let command = activity.begin("hotfolder.write_text");
    assert_eq!(activity.snapshot().active.len(), 1);
    assert!(activity.snapshot().active[0].progress_percent.is_none());
    command.finish(true);
    assert_eq!(
        activity.snapshot().last_command.unwrap().state,
        ClientCommandState::Succeeded
    );
    drop(activity.begin("hotfolder.read_text"));
    assert_eq!(
        activity.snapshot().last_command.unwrap().state,
        ClientCommandState::Interrupted
    );
    activity.begin("calculator.evaluate").finish(false);
    for _ in 0..110 {
        activity.event("Connection", "ACTIVE");
    }
    activity.event(
        "secret=https://private.example/token",
        "password=do-not-log",
    );
    let snapshot = activity.snapshot();
    assert_eq!(snapshot.events.len(), 100);
    let bytes = directory.store().read("activity.json").unwrap().unwrap();
    assert!(!String::from_utf8_lossy(&bytes).contains("private.example"));
    assert!(!String::from_utf8_lossy(&bytes).contains("do-not-log"));
    let interrupted = activity.begin("interrupted.command");
    let reopened = Activity::open(directory.0.clone()).unwrap();
    assert!(reopened.snapshot().active.is_empty());
    assert_eq!(
        reopened.snapshot().last_command.unwrap().state,
        ClientCommandState::Interrupted
    );
    drop(interrupted);
}
#[tokio::test]
async fn activity_ipc_remains_available_during_execution_and_checks_peer() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let directory = Directory::new();
    let service = Arc::new(tokio::sync::Mutex::new(service(&directory)));
    let activity = service.lock().await.activity.clone();
    let command = activity.begin("calculator.evaluate");
    let execution = service.lock().await;
    let contracts = Contracts::new().unwrap();
    let request = ClientIpcRequest {
        protocol_version: 1,
        request_id: "activity-while-busy".into(),
        operation: ClientIpcOperation::Activity,
    };
    let bytes = contracts.encode("ClientIpcRequest", &request).unwrap();
    let (mut stream, mut peer) = tokio::io::duplex(16384);
    peer.write_u32(bytes.len() as u32).await.unwrap();
    peer.write_all(&bytes).await.unwrap();
    tokio::time::timeout(
        std::time::Duration::from_millis(500),
        ipc::connection(
            &mut stream,
            "authorized",
            &["authorized".into()],
            &service,
            &contracts,
            &activity,
        ),
    )
    .await
    .unwrap()
    .unwrap();
    let mut response = vec![0; peer.read_u32().await.unwrap() as usize];
    peer.read_exact(&mut response).await.unwrap();
    let response: ClientIpcResponse = contracts.decode("ClientIpcResponse", &response).unwrap();
    assert_eq!(
        response.activity.unwrap().active[0].name,
        "calculator.evaluate"
    );
    assert_eq!(
        ipc::connection(
            &mut stream,
            "untrusted",
            &["authorized".into()],
            &service,
            &contracts,
            &activity
        )
        .await,
        Err(Failure::Unauthorized)
    );
    drop(execution);
    command.finish(true);
}
#[test]
fn composition_directory_gates_decode_closed_canonical_records() {
    let contracts = Contracts::new().unwrap();
    let fixture: serde_json::Value = serde_json::from_str(include_str!(
        "../../../tests/fixtures/contracts/v1/valid.json"
    ))
    .unwrap();
    let device: ControlDevice = contracts
        .decode(
            "ControlDevice",
            &serde_json::to_vec(&fixture["ControlDevice"]).unwrap(),
        )
        .unwrap();
    assert_eq!(
        device.owner_user_id,
        fixture["ControlDevice"]["ownerUserId"].as_str().unwrap()
    );
    let _: ControlUser = contracts
        .decode(
            "ControlUser",
            &serde_json::to_vec(&fixture["ControlUser"]).unwrap(),
        )
        .unwrap();
    let mut changed = fixture["ControlDevice"].clone();
    changed["credential"] = serde_json::json!("not-directory-metadata");
    assert_eq!(
        contracts.decode::<ControlDevice>("ControlDevice", &serde_json::to_vec(&changed).unwrap()),
        Err(Failure::Validation)
    );
}
impl ControlPort for Offline {
    fn ping(&self, _: EndpointPresencePing) -> Call<'_, EndpointConnectionDirective> {
        Box::pin(async { Err(Failure::Unavailable) })
    }
    fn discovery(&self) -> Call<'_, SignedTenantDiscovery> {
        Box::pin(async { Err(Failure::Unavailable) })
    }
    fn start(&self, _: EndpointEnrollmentStart) -> Call<'_, EndpointEnrollmentChallenge> {
        Box::pin(async { Err(Failure::Unavailable) })
    }
    fn poll(&self, _: EndpointEnrollmentPoll) -> Call<'_, EndpointEnrollmentResult> {
        Box::pin(async { Err(Failure::Unavailable) })
    }
    fn check_in(&self, _: DeviceIdentity, _: EndpointCheckIn) -> Call<'_, EndpointCheckInAck> {
        Box::pin(async { Err(Failure::Unavailable) })
    }
}
fn config(directory: &Directory) -> Config {
    Config {
        tenant_id: None,
        deployment: None,
        execution: None,
        gateway: None,
        server_url: "https://control.example.test".into(),
        state_directory: directory.0.clone(),
        ipc_endpoint: {
            #[cfg(unix)]
            {
                directory
                    .0
                    .join("client.sock")
                    .to_string_lossy()
                    .into_owned()
            }
            #[cfg(windows)]
            {
                r"\\.\pipe\olo-toolgate-client".into()
            }
        },
        authorized_peers: vec![{
            #[cfg(unix)]
            {
                unsafe { libc::geteuid() }.to_string()
            }
            #[cfg(windows)]
            {
                olo_toolgate_client::platform::windows::current_sid().unwrap()
            }
        }],
        ca_certificate_path: None,
        request_timeout_seconds: 5,
        local_console_url: None,
    }
}
fn service(directory: &Directory) -> ClientService {
    let store = directory.store();
    ClientService::open(config(directory), store, |_| Ok(Arc::new(Offline))).unwrap()
}
#[test]
fn journal_binds_origin_before_key_creation_and_rejects_repointing_without_mutation() {
    let directory = Directory::new();
    let initial = service(&directory);
    let store = directory.store();
    let before = store.read("journal.json").unwrap().unwrap();
    let journal: serde_json::Value = serde_json::from_slice(&before).unwrap();
    assert_eq!(journal["formatVersion"], 3);
    assert_eq!(journal["serverUrl"], config(&directory).server_url);
    let key_hash = olo_toolgate_client::digest(&store.read("device-key").unwrap().unwrap());
    drop(initial);
    let mut foreign = config(&directory);
    foreign.server_url = "https://foreign.example".into();
    assert_eq!(
        ClientService::open(foreign, directory.store(), |_| panic!(
            "foreign state must not construct transport"
        ))
        .err(),
        Some(Failure::TenantMismatch)
    );
    assert_eq!(store.read("journal.json").unwrap().unwrap(), before);
    assert_eq!(
        olo_toolgate_client::digest(&store.read("device-key").unwrap().unwrap()),
        key_hash
    );
    assert!(
        ClientService::open(config(&directory), directory.store(), |_| Ok(Arc::new(
            Offline
        )))
        .is_ok()
    );
}
#[test]
fn administrator_uuid_is_persisted_before_transport_and_cannot_be_replaced() {
    let directory = Directory::new();
    let mut settings = config(&directory);
    settings.tenant_id = Some("10000000-0000-4000-8000-000000000001".into());
    let initial = ClientService::open(settings.clone(), directory.store(), |_| {
        let bytes = directory.store().read("journal.json")?.unwrap();
        let journal: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(journal["tenantId"], settings.tenant_id.as_deref().unwrap());
        Ok(Arc::new(Offline))
    })
    .unwrap();
    drop(initial);
    let before = directory.store().read("journal.json").unwrap().unwrap();
    settings.tenant_id = Some("10000000-0000-4000-8000-000000000002".into());
    assert_eq!(
        ClientService::open(settings, directory.store(), |_| panic!(
            "foreign UUID cannot construct transport"
        ))
        .err(),
        Some(Failure::TenantMismatch)
    );
    assert_eq!(
        directory.store().read("journal.json").unwrap().unwrap(),
        before
    );
}
#[test]
fn unsupported_journal_is_left_unchanged_without_creating_a_key() {
    for bytes in [
        br#"{}"#.as_slice(),
        br#"{"formatVersion":1,"serverUrl":"https://control.example.test","tenantId":null}"#
            .as_slice(),
    ] {
        let directory = Directory::new();
        let store = directory.store();
        store.write("journal.json", bytes).unwrap();
        assert_eq!(
            ClientService::open(config(&directory), directory.store(), |_| panic!(
                "unsupported state must not construct transport"
            ))
            .err(),
            Some(Failure::Validation)
        );
        assert_eq!(store.read("journal.json").unwrap().unwrap(), bytes);
        assert!(store.read("device-key").unwrap().is_none());
    }
}
#[test]
fn orphan_key_cannot_be_adopted_into_a_new_tenant_journal() {
    let directory = Directory::new();
    let store = directory.store();
    let key = DeviceKey::load_or_create(&store).unwrap();
    let fingerprint = key.fingerprint();
    drop(key);
    assert_eq!(
        ClientService::open(config(&directory), directory.store(), |_| panic!(
            "orphan state must not construct transport"
        ))
        .err(),
        Some(Failure::Validation)
    );
    assert!(store.read("journal.json").unwrap().is_none());
    assert_eq!(
        DeviceKey::load_or_create(&store).unwrap().fingerprint(),
        fingerprint
    );
}
/// Signed releases do not need the local Gateway credential: relayed calls are authorized by Control (ADR 027).
#[test]
fn execution_and_releases_do_not_require_local_gateway_settings() {
    let directory = Directory::new();
    let mut config = config(&directory);
    config.execution = Some(olo_toolgate_client::execution::Settings {
        engine_path: directory.0.join("docker"),
        engine_endpoint: "unix:///var/run/docker.sock".into(),
        state_directory: directory.0.join("runtimes"),
        allow_first_use_pull: true,
        pull_timeout_seconds: 60,
        runtimes: vec![],
        tools: vec![],
        files: None,
    });
    let fleet: serde_json::Value =
        serde_json::from_str(include_str!("../../../tests/fixtures/fleet/v1/signed.json")).unwrap();
    config.deployment = Some(olo_toolgate_client::deployment::Settings {
        release_keys: serde_json::from_value(fleet["releaseKeys"].clone()).unwrap(),
        organization_keys: serde_json::from_value(fleet["organizationKeys"].clone()).unwrap(),
    });
    assert!(config.gateway.is_none());
    assert_eq!(config.validate(), Ok(()));
    config.execution = None;
    assert_eq!(
        config.validate(),
        Err(Failure::Validation),
        "releases still need execution settings"
    );
}

#[test]
fn key_survives_restart_and_csr_contains_no_private_key() {
    let directory = Directory::new();
    let store = directory.store();
    let first = DeviceKey::load_or_create(&store).unwrap();
    let second = DeviceKey::load_or_create(&store).unwrap();
    assert_eq!(first.fingerprint(), second.fingerprint());
    let csr = first.csr("device-test").unwrap();
    assert!(csr.contains("CERTIFICATE REQUEST"));
    assert!(!csr.contains("PRIVATE"));
    let other = Directory::new();
    assert_ne!(
        first.fingerprint(),
        DeviceKey::load_or_create(&other.store())
            .unwrap()
            .fingerprint()
    );
}
#[test]
fn storage_bounds_names_and_exclusive_service_lease() {
    let directory = Directory::new();
    let store = directory.store();
    assert_eq!(store.write("../escape", b"x"), Err(Failure::Validation));
    assert_eq!(
        store.write("journal.json", &vec![0; 131073]),
        Err(Failure::Validation)
    );
    let lease = store.lease().unwrap();
    assert!(matches!(store.lease(), Err(Failure::Conflict)));
    drop(lease);
    assert!(store.lease().is_ok());
    store.write("journal.json", b"first").unwrap();
    store.write("journal.json", b"second").unwrap();
    assert_eq!(store.read("journal.json").unwrap().unwrap(), b"second");
}
#[cfg(unix)]
#[test]
fn permissive_or_linked_key_storage_is_rejected() {
    use std::os::unix::fs::{symlink, PermissionsExt};
    let directory = Directory::new();
    let store = directory.store();
    store.write("device-key", b"invalid").unwrap();
    std::fs::set_permissions(
        directory.0.join("device-key"),
        std::fs::Permissions::from_mode(0o644),
    )
    .unwrap();
    assert_eq!(store.read("device-key"), Err(Failure::Unauthorized));
    std::fs::remove_file(directory.0.join("device-key")).unwrap();
    symlink("/etc/passwd", directory.0.join("device-key")).unwrap();
    assert_eq!(store.read("device-key"), Err(Failure::Unauthorized));
}
#[test]
fn discovery_origin_and_ipc_peer_checks_are_exact() {
    for url in [
        "http://control.example.test",
        "https://user@control.example.test",
        "https://control.example.test/path",
        "https://control.example.test?x=1",
        "https://169.254.169.254",
    ] {
        assert!(olo_toolgate_client::config::origin(url).is_err(), "{url}");
    }
    assert!(ipc::authorized("1000", &["1000".into()]));
    assert!(!ipc::authorized("01000", &["1000".into()]));
    assert!(!ipc::authorized("0", &["1000".into()]));
}
#[test]
fn ipc_contract_cannot_export_keys_or_accept_arbitrary_operations() {
    let contracts = Contracts::new().unwrap();
    for bytes in [br#"{"protocolVersion":1,"requestId":"request-1","operation":"EXPORT_KEY"}"#.as_slice(),
        br#"{"protocolVersion":1,"requestId":"request-1","operation":"HEALTH","path":"/etc/passwd"}"#.as_slice(),
        br#"{"protocolVersion":1,"requestId":"request-1","operation":"HEALTH","operation":"ENROLL"}"#.as_slice()] {
        assert!(contracts.decode::<ClientIpcRequest>("ClientIpcRequest", bytes).is_err());
    }
}
#[test]
fn live_discovery_boundary_rejects_the_archived_v1_envelope() {
    let bytes = include_bytes!("../../../tests/fixtures/client/discovery-public.json");
    assert!(olo_toolgate_tenant_identity::verify_bytes(
        bytes,
        "https://control.example.test",
        None,
        None,
        1700000000000
    )
    .is_err());
}
#[tokio::test]
async fn offline_enrollment_never_becomes_ready_and_key_is_retained() {
    let directory = Directory::new();
    let mut service = service(&directory);
    assert_eq!(service.enroll().await.unwrap_err(), Failure::Unavailable);
    assert!(!service.health().ready);
    assert_eq!(service.health().state, EndpointState::Unenrolled);
    assert_eq!(service.tick().await, Err(Failure::Unavailable));
    assert!(!service.health().ready);
    drop(service);
    assert!(!self::service(&directory).health().ready);
}
#[tokio::test]
async fn unauthorized_ipc_rejected_before_reading_payload() {
    let directory = Directory::new();
    let service = Arc::new(tokio::sync::Mutex::new(service(&directory)));
    let (mut stream, _peer) = tokio::io::duplex(256);
    assert_eq!(
        ipc::connection(
            &mut stream,
            "untrusted",
            &["authorized".into()],
            &service,
            &Contracts::new().unwrap(),
            &service.lock().await.activity.clone(),
        )
        .await,
        Err(Failure::Unauthorized)
    );
}
#[tokio::test]
async fn console_ipc_denies_foreign_context_before_health_or_enrollment() {
    use olo_toolgate_contracts::v2::{
        ConsoleClientOperation, ConsoleClientRequest, ConsoleClientResponse,
    };
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let directory = Directory::new();
    let service = Arc::new(tokio::sync::Mutex::new(service(&directory)));
    let contracts = Contracts::new().unwrap();
    let activity = service.lock().await.activity.clone();
    for (operation, console, expected, allowed) in [
        (
            ConsoleClientOperation::Health,
            "https://other.example/console/",
            None,
            false,
        ),
        (
            ConsoleClientOperation::Enroll,
            "https://other.example/console/",
            None,
            false,
        ),
        (
            ConsoleClientOperation::Health,
            "https://control.example.test/console/",
            None,
            true,
        ),
        (
            ConsoleClientOperation::Health,
            "https://control.example.test/console/",
            Some("10000000-0000-4000-8000-000000000001"),
            false,
        ),
    ] {
        let before = directory.store().read("journal.json").unwrap();
        let request = ConsoleClientRequest {
            kind: "console-tenant-state".into(),
            request_id: "context-check".into(),
            operation,
            console_url: console.into(),
            expected_tenant_id: expected.map(str::to_owned),
        };
        let bytes = contracts.encode("ConsoleClientRequest", &request).unwrap();
        let (mut stream, mut peer) = tokio::io::duplex(16384);
        peer.write_u32(bytes.len() as u32).await.unwrap();
        peer.write_all(&bytes).await.unwrap();
        ipc::connection(
            &mut stream,
            "authorized",
            &["authorized".into()],
            &service,
            &contracts,
            &activity,
        )
        .await
        .unwrap();
        let size = peer.read_u32().await.unwrap() as usize;
        let mut bytes = vec![0; size];
        peer.read_exact(&mut bytes).await.unwrap();
        let reply: ConsoleClientResponse =
            contracts.decode("ConsoleClientResponse", &bytes).unwrap();
        assert_eq!(
            reply.error,
            if allowed {
                None
            } else {
                Some(ErrorCode::TenantMismatch)
            }
        );
        assert_eq!(reply.health.is_some(), allowed);
        assert!(reply.challenge.is_none());
        let wire: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        for field in ["activity", "connections", "power"] {
            assert!(wire.get(field).is_none());
        }
        if !allowed {
            assert!(wire.get("tenantId").is_none());
            assert_eq!(wire.as_object().unwrap().len(), 2);
        }
        assert_eq!(directory.store().read("journal.json").unwrap(), before);
    }
}

#[tokio::test]
async fn busy_console_requests_receive_a_correlated_conflict_frame() {
    use olo_toolgate_contracts::v2::{
        ConsoleClientOperation, ConsoleClientRequest, ConsoleClientResponse,
    };
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let directory = Directory::new();
    let service = Arc::new(tokio::sync::Mutex::new(service(&directory)));
    let contracts = Contracts::new().unwrap();
    let activity = service.lock().await.activity.clone();
    let guard = service.lock().await;
    for operation in [
        ConsoleClientOperation::Health,
        ConsoleClientOperation::Enroll,
    ] {
        let request = ConsoleClientRequest {
            kind: "console-tenant-state".into(),
            request_id: "busy-console".into(),
            operation,
            console_url: "https://control.example.test/console/".into(),
            expected_tenant_id: None,
        };
        let bytes = contracts.encode("ConsoleClientRequest", &request).unwrap();
        let (mut stream, mut peer) = tokio::io::duplex(16384);
        peer.write_u32(bytes.len() as u32).await.unwrap();
        peer.write_all(&bytes).await.unwrap();
        tokio::time::timeout(
            std::time::Duration::from_secs(3),
            ipc::connection(
                &mut stream,
                "authorized",
                &["authorized".into()],
                &service,
                &contracts,
                &activity,
            ),
        )
        .await
        .unwrap()
        .unwrap();
        let size = peer.read_u32().await.unwrap();
        let mut bytes = vec![0; size as usize];
        peer.read_exact(&mut bytes).await.unwrap();
        let response: ConsoleClientResponse =
            contracts.decode("ConsoleClientResponse", &bytes).unwrap();
        assert_eq!(response.request_id, request.request_id);
        assert_eq!(response.error, Some(ErrorCode::Conflict));
        assert!(
            response.health.is_none()
                && response.challenge.is_none()
                && response.tenant_id.is_none()
        );
        let wire: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(wire.as_object().unwrap().len(), 2);
    }
    drop(guard);
}

#[tokio::test]
async fn health_waits_for_poll_but_enrollment_retains_immediate_backpressure() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    for operation in [ClientIpcOperation::Health, ClientIpcOperation::Enroll] {
        let directory = Directory::new();
        let service = Arc::new(tokio::sync::Mutex::new(service(&directory)));
        let activity = service.lock().await.activity.clone();
        let guard = service.lock().await;
        let contracts = Arc::new(Contracts::new().unwrap());
        let (mut stream, mut peer) = tokio::io::duplex(8192);
        let request = ClientIpcRequest {
            protocol_version: 1,
            request_id: "health-poll-contention".into(),
            operation: operation.clone(),
        };
        let bytes = contracts.encode("ClientIpcRequest", &request).unwrap();
        peer.write_u32(bytes.len() as u32).await.unwrap();
        peer.write_all(&bytes).await.unwrap();
        let state = service.clone();
        let schema = contracts.clone();
        let pending = tokio::spawn(async move {
            ipc::connection(
                &mut stream,
                "authorized",
                &["authorized".into()],
                &state,
                &schema,
                &activity,
            )
            .await
        });
        if matches!(operation, ClientIpcOperation::Health) {
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
            assert!(
                !pending.is_finished(),
                "Health must wait for the active poll"
            );
            drop(guard);
        } else {
            tokio::time::timeout(std::time::Duration::from_secs(2), async {
                while !pending.is_finished() {
                    tokio::task::yield_now().await
                }
            })
            .await
            .unwrap();
            drop(guard);
        }
        pending.await.unwrap().unwrap();
        let count = peer.read_u32().await.unwrap() as usize;
        let mut bytes = vec![0; count];
        peer.read_exact(&mut bytes).await.unwrap();
        let response: ClientIpcResponse = contracts.decode("ClientIpcResponse", &bytes).unwrap();
        if matches!(operation, ClientIpcOperation::Health) {
            assert!(response.error.is_none());
            assert_eq!(response.health.unwrap().state, EndpointState::Unenrolled);
        } else {
            assert_eq!(response.error, Some(ErrorCode::Conflict));
            assert!(response.challenge.is_none());
        }
    }
}
