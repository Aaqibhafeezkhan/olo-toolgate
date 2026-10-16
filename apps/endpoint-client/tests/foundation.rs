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
    fn discovery(&self) -> Call<'_, SignedClientDiscovery> {
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
    let key = Arc::new(DeviceKey::load_or_create(&store).unwrap());
    ClientService::open(config(directory), store, key, Arc::new(Offline)).unwrap()
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
fn signed_discovery_rejects_wrong_origin_expiry_ca_and_corruption() {
    use olo_toolgate_client::identity::verify_discovery;
    let mut envelope: SignedClientDiscovery = serde_json::from_str(include_str!(
        "../../../tests/fixtures/client/discovery-public.json"
    ))
    .unwrap();
    let now = 1700000000000;
    let manifest = verify_discovery(&envelope, "https://control.example.test", now, None).unwrap();
    assert_eq!(manifest.server_id, "server-1");
    assert_eq!(
        verify_discovery(&envelope, "https://wrong.example.test", now, None).unwrap_err(),
        Failure::Unauthorized
    );
    assert_eq!(
        verify_discovery(
            &envelope,
            "https://control.example.test",
            now + 300000,
            None
        )
        .unwrap_err(),
        Failure::Unauthorized
    );
    assert_eq!(
        verify_discovery(
            &envelope,
            "https://control.example.test",
            now,
            Some(&"0".repeat(64))
        )
        .unwrap_err(),
        Failure::Unauthorized
    );
    let replacement = if envelope.signature.starts_with('A') {
        "B"
    } else {
        "A"
    };
    envelope.signature.replace_range(..1, replacement);
    assert_eq!(
        verify_discovery(&envelope, "https://control.example.test", now, None).unwrap_err(),
        Failure::Unauthorized
    );
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
