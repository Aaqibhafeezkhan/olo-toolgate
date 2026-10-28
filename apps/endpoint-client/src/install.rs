// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Explicit OS service installation commands; fixed paths/arguments and no shell evaluation.
use crate::{config::Config, Failure, Result};
use std::{
    path::{Path, PathBuf},
    process::Command,
};
pub fn config_path() -> PathBuf {
    #[cfg(target_os = "linux")]
    {
        PathBuf::from("/etc/olo-toolgate/client.json")
    }
    #[cfg(target_os = "macos")]
    {
        PathBuf::from("/Library/Application Support/OLO/ToolGate/client.json")
    }
    #[cfg(windows)]
    {
        PathBuf::from(r"C:\ProgramData\OLO\ToolGate\client.json")
    }
}
pub fn ipc_endpoint() -> String {
    #[cfg(target_os = "linux")]
    {
        "/run/olo-toolgate/client.sock".into()
    }
    #[cfg(target_os = "macos")]
    {
        "/Library/Application Support/OLO/ToolGate/run/client.sock".into()
    }
    #[cfg(windows)]
    {
        r"\\.\pipe\olo-toolgate-client".into()
    }
}
pub fn binary_path() -> PathBuf {
    #[cfg(target_os = "linux")]
    {
        PathBuf::from("/usr/local/lib/olo-toolgate/olo-toolgate-client")
    }
    #[cfg(target_os = "macos")]
    {
        PathBuf::from("/Library/Application Support/OLO/ToolGate/bin/olo-toolgate-client")
    }
    #[cfg(windows)]
    {
        PathBuf::from(r"C:\Program Files\OLO\ToolGate\olo-toolgate-client.exe")
    }
}
fn directory(path: &Path, private: bool) -> Result<()> {
    if !path.exists() {
        let parent = path.parent().ok_or(Failure::Validation)?;
        if !parent.exists() {
            directory(parent, false)?;
        }
        check_directory(parent, false)?;
        let builder = std::fs::DirBuilder::new();
        #[cfg(unix)]
        let mut builder = builder;
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(if private { 0o700 } else { 0o755 });
        }
        builder.create(path).map_err(|_| Failure::Unavailable)?;
        #[cfg(windows)]
        crate::platform::windows::protect_install_acl(path, !private)?;
    }
    check_directory(path, private)
}
fn check_directory(path: &Path, private: bool) -> Result<()> {
    crate::storage::check_owned(path, private)?;
    #[cfg(windows)]
    {
        crate::platform::windows::check_install_acl(path, private)?;
        for ancestor in path.ancestors().skip(1) {
            crate::storage::check_owned(ancestor, false)?;
            crate::platform::windows::check_install_acl(ancestor, false)?;
        }
    }
    Ok(())
}
fn admin() -> Result<()> {
    #[cfg(unix)]
    {
        crate::platform::require_service_identity()
    }
    #[cfg(windows)]
    {
        crate::platform::windows::require_administrator()
    }
}
fn command(program: &str, args: &[&str]) -> Result<()> {
    let mut command = Command::new(program);
    command.args(args);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    if command
        .status()
        .map_err(|_| Failure::Unavailable)?
        .success()
    {
        Ok(())
    } else {
        Err(Failure::Unavailable)
    }
}
pub fn install(server: &str) -> Result<()> {
    install_for_peer(server, None)
}
pub fn install_for_peer(server: &str, peer: Option<&str>) -> Result<()> {
    install_for_peer_with_ca(server, peer, None, None, None)
}
pub fn install_for_peer_with_ca(
    server: &str,
    peer: Option<&str>,
    ca: Option<&str>,
    console: Option<&str>,
    tenant: Option<&str>,
) -> Result<()> {
    install_config(server, peer, None, ca, console, tenant)
}
pub fn reinstall(server: &str, peer: Option<&str>) -> Result<()> {
    reinstall_with_ca(server, peer, None, None, None)
}
pub fn reinstall_with_ca(
    server: &str,
    peer: Option<&str>,
    ca: Option<&str>,
    console: Option<&str>,
    tenant: Option<&str>,
) -> Result<()> {
    admin()?;
    validate_peer(peer)?;
    #[cfg(windows)]
    if ca.is_some() {
        // Reconcile persisted enrollment too: the config may already contain a replacement CA.
        configure_with_ca(server, peer, ca, console, tenant)?;
    }
    let previous = Config::load(&config_path())?;
    if crate::config::origin(server)? != previous.server_url
        || tenant.is_some_and(|tenant| previous.tenant_id.as_deref() != Some(tenant))
    {
        return Err(Failure::TenantMismatch);
    }
    let saved = crate::storage::ProtectedStore::open(previous.state_directory.clone())?;
    crate::service::validate_saved_binding(&previous, &saved)?;
    remove(false)?;
    install_config(server, peer, Some(previous), ca, console, tenant)
}
#[cfg(not(windows))]
pub fn configure(_: &str, _: Option<&str>) -> Result<()> {
    Err(Failure::Unsupported)
}
#[cfg(not(windows))]
pub fn configure_with_ca(
    _: &str,
    _: Option<&str>,
    _: Option<&str>,
    _: Option<&str>,
    _: Option<&str>,
) -> Result<()> {
    Err(Failure::Unsupported)
}
#[cfg(windows)]
pub fn configure(server: &str, peer: Option<&str>) -> Result<()> {
    configure_with_ca(server, peer, None, None, None)
}
/// Repairs the installed organization only; switching requires explicit uninstall and purge.
#[cfg(windows)]
pub fn configure_with_ca(
    server: &str,
    peer: Option<&str>,
    ca: Option<&str>,
    console: Option<&str>,
    tenant: Option<&str>,
) -> Result<()> {
    reconfigure(server, peer, ca, console, tenant)
}
#[cfg(windows)]
fn reconfigure(
    server: &str,
    peer: Option<&str>,
    ca: Option<&str>,
    console: Option<&str>,
    tenant: Option<&str>,
) -> Result<()> {
    admin()?;
    validate_peer(peer)?;
    let server = crate::config::origin(server)?;
    let console = console.map(crate::config::local_console).transpose()?;
    let previous = Config::load(&config_path())?;
    if previous.server_url != server
        || tenant.is_some_and(|tenant| previous.tenant_id.as_deref() != Some(tenant))
    {
        return Err(Failure::TenantMismatch);
    }
    let saved = crate::storage::ProtectedStore::open(previous.state_directory.clone())?;
    crate::service::validate_saved_binding(&previous, &saved)?;
    if local_gateway_trust_changed(&previous, ca)? {
        return Err(Failure::TenantMismatch);
    }
    let mut settings = previous.clone();
    let path = config_path();
    let update = (|| {
        stop_windows_service()?;
        if let Some(console) = &console {
            settings.local_console_url = Some(console.clone());
        }
        if let Some(peer) = peer {
            if !settings.authorized_peers.iter().any(|p| p == peer) {
                settings.authorized_peers.push(peer.to_owned());
            }
        }
        if let Some(ca) = ca {
            settings.ca_certificate_path = Some(save_ca(ca)?);
        }
        settings.validate()?;
        write_config(&path, &settings)
    })();
    if let Err(failure) = update {
        let _ = command(
            r"C:\Windows\System32\sc.exe",
            &["start", "OloToolGateClient"],
        );
        return Err(failure);
    }
    if let Err(failure) = record_gateway(&settings) {
        tracing::warn!(event="gateway_index",error=?failure);
    }
    let registry = command(
        r"C:\Windows\System32\reg.exe",
        &[
            "add",
            r"HKLM\Software\OLO\ToolGate",
            "/v",
            "ServerUrl",
            "/t",
            "REG_SZ",
            "/d",
            &server,
            "/f",
        ],
    );
    let start = command(
        r"C:\Windows\System32\sc.exe",
        &["start", "OloToolGateClient"],
    );
    start.and(registry)
}
#[cfg(windows)]
fn stop_windows_service() -> Result<()> {
    use windows_service::{
        service::{ServiceAccess, ServiceState},
        service_manager::{ServiceManager, ServiceManagerAccess},
    };
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)
        .map_err(|_| Failure::Unavailable)?;
    let service = manager
        .open_service(
            "OloToolGateClient",
            ServiceAccess::QUERY_STATUS | ServiceAccess::STOP,
        )
        .map_err(|_| Failure::Unavailable)?;
    if service
        .query_status()
        .map_err(|_| Failure::Unavailable)?
        .current_state
        == ServiceState::Running
    {
        service.stop().map_err(|_| Failure::Unavailable)?;
    }
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
    while service
        .query_status()
        .map_err(|_| Failure::Unavailable)?
        .current_state
        != ServiceState::Stopped
    {
        if std::time::Instant::now() >= deadline {
            return Err(Failure::Unavailable);
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    Ok(())
}
fn validate_peer(peer: Option<&str>) -> Result<()> {
    #[cfg(windows)]
    if peer.is_some_and(|sid| {
        !sid.starts_with("S-1-")
            || sid.len() > 184
            || !sid
                .bytes()
                .all(|b| b.is_ascii_digit() || b == b'S' || b == b'-')
    }) {
        return Err(Failure::Validation);
    }
    #[cfg(unix)]
    if peer.is_some() {
        return Err(Failure::Unsupported);
    }
    Ok(())
}
/// Compare installer-verified local TLS trust. Tenant device CAs are an independent domain.
#[cfg(any(windows, test))]
fn local_gateway_trust_changed(settings: &Config, ca: Option<&str>) -> Result<bool> {
    let Some(ca) = ca else { return Ok(false) };
    let expected = crate::identity::certificate_der(ca)?;
    if let Some(path) = &settings.ca_certificate_path {
        let current = crate::storage::read_owned(path, 16384, false)?;
        let current = std::str::from_utf8(&current).map_err(|_| Failure::Validation)?;
        if crate::identity::certificate_der(current)? != expected {
            return Ok(true);
        }
    }
    Ok(false)
}
fn save_ca(ca: &str) -> Result<PathBuf> {
    if ca.len() > 16384 {
        return Err(Failure::Validation);
    }
    reqwest::Certificate::from_pem(ca.as_bytes()).map_err(|_| Failure::Validation)?;
    use sha2::Digest;
    let path = config_path()
        .parent()
        .ok_or(Failure::Validation)?
        .join(format!(
            "gateway-ca-{}.crt",
            sha2::Sha256::digest(ca.as_bytes())
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>()
        ));
    crate::storage::check_parents(&path)?;
    if path.exists() {
        if crate::storage::read_owned(&path, 16384, false)? != ca.as_bytes() {
            return Err(Failure::Conflict);
        }
        return Ok(path);
    }
    let temporary = path.with_extension(format!("{}.crt", crate::identity::nonce()?));
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o644).custom_flags(libc::O_NOFOLLOW);
    }
    let mut file = options.open(&temporary).map_err(|_| Failure::Unavailable)?;
    file.write_all(ca.as_bytes())
        .and_then(|_| file.sync_all())
        .map_err(|_| Failure::Unavailable)?;
    drop(file);
    #[cfg(windows)]
    crate::platform::windows::protect_install_acl(&temporary, true)?;
    std::fs::rename(&temporary, &path).map_err(|_| Failure::Unavailable)?;
    Ok(path)
}
fn write_new(path: &Path, bytes: &[u8], public: bool) -> Result<()> {
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options
            .mode(if public { 0o644 } else { 0o600 })
            .custom_flags(libc::O_NOFOLLOW);
    }
    let mut file = options.open(path).map_err(|_| Failure::Conflict)?;
    let written = file
        .write_all(bytes)
        .and_then(|_| file.sync_all())
        .map_err(|_| Failure::Unavailable);
    drop(file);
    #[cfg(windows)]
    let written = written.and_then(|_| crate::platform::windows::protect_install_acl(path, public));
    if written.is_err() {
        let _ = std::fs::remove_file(path);
    }
    written
}
/// Atomically replaces a public, administrator-owned document beside the configuration.
fn replace_public(path: &Path, bytes: &[u8]) -> Result<()> {
    let temporary = path.with_extension(format!("{}.json", crate::identity::nonce()?));
    write_new(&temporary, bytes, true)?;
    std::fs::rename(&temporary, path).map_err(|_| {
        let _ = std::fs::remove_file(&temporary);
        Failure::Unavailable
    })
}
#[cfg(windows)]
fn write_config(path: &Path, settings: &Config) -> Result<()> {
    replace_public(
        path,
        &serde_json::to_vec_pretty(settings).map_err(|_| Failure::Validation)?,
    )
}
/// Public organization shortcut. It carries no device identity or credentials.
#[derive(Clone, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct KnownGateway {
    server_url: String,
    #[serde(default)]
    console_url: Option<String>,
}
fn gateway_index_path() -> PathBuf {
    config_path().with_file_name("gateways.json")
}
fn known_gateways() -> Vec<KnownGateway> {
    crate::storage::read_owned(&gateway_index_path(), 16384, false)
        .ok()
        .and_then(|bytes| serde_json::from_slice::<Vec<KnownGateway>>(&bytes).ok())
        .unwrap_or_default()
        .into_iter()
        .filter(|gateway| {
            crate::config::origin(&gateway.server_url).ok().as_ref() == Some(&gateway.server_url)
                && gateway.console_url.as_ref().is_none_or(|console| {
                    crate::config::local_console(console).ok().as_ref() == Some(console)
                        && crate::config::loopback(&gateway.server_url)
                })
        })
        .collect()
}
/// The loopback console recorded for a local gateway, used to refresh its CA on repair.
pub fn remembered_console(server: &str) -> Option<String> {
    if let Ok(active) = Config::load(&config_path()) {
        if active.server_url == server && active.local_console_url.is_some() {
            return active.local_console_url;
        }
    }
    known_gateways()
        .into_iter()
        .find(|gateway| gateway.server_url == server)
        .and_then(|gateway| gateway.console_url)
}
fn record_gateway(current: &Config) -> Result<()> {
    let gateways = [KnownGateway {
        server_url: current.server_url.clone(),
        console_url: current.local_console_url.clone(),
    }];
    replace_public(
        &gateway_index_path(),
        &serde_json::to_vec_pretty(&gateways).map_err(|_| Failure::Validation)?,
    )
}
fn install_config(
    server: &str,
    peer: Option<&str>,
    previous: Option<Config>,
    ca: Option<&str>,
    console: Option<&str>,
    tenant: Option<&str>,
) -> Result<()> {
    admin()?;
    let origin = crate::config::origin(server)?;
    validate_peer(peer)?;
    let binary = binary_path();
    let config = config_path();
    if config.exists() {
        return Err(Failure::Conflict);
    }
    directory(binary.parent().ok_or(Failure::Validation)?, false)?;
    let config_directory = config.parent().ok_or(Failure::Validation)?.to_owned();
    directory(&config_directory, false)?;
    #[cfg(target_os = "linux")]
    let (state, mut peers) = (PathBuf::from("/var/lib/olo-toolgate"), vec!["0".to_owned()]);
    #[cfg(target_os = "macos")]
    let (state, mut peers) = (
        PathBuf::from("/Library/Application Support/OLO/ToolGate/state"),
        vec!["0".to_owned()],
    );
    #[cfg(unix)]
    if let Ok(uid) = std::env::var("SUDO_UID") {
        if uid.parse::<u32>().is_ok() && !peers.contains(&uid) {
            peers.push(uid);
        }
    }
    #[cfg(windows)]
    let (state, peers) = (
        PathBuf::from(r"C:\ProgramData\OLO\ToolGate\state"),
        vec![
            "S-1-5-18".to_owned(),
            crate::platform::windows::current_sid()?,
        ],
    );
    directory(&state, true)?;
    #[cfg(unix)]
    directory(
        Path::new(&ipc_endpoint())
            .parent()
            .ok_or(Failure::Validation)?,
        false,
    )?;
    let mut settings = previous.unwrap_or(Config {
        tenant_id: None,
        deployment: None,
        execution: None,
        gateway: None,
        server_url: origin,
        state_directory: state,
        ipc_endpoint: ipc_endpoint(),
        authorized_peers: peers,
        ca_certificate_path: None,
        request_timeout_seconds: 10,
        local_console_url: None,
    });
    if tenant.is_some_and(|tenant| {
        settings
            .tenant_id
            .as_deref()
            .is_some_and(|old| old != tenant)
    }) {
        return Err(Failure::TenantMismatch);
    }
    if let Some(tenant) = tenant {
        settings.tenant_id = Some(tenant.into());
    }
    let saved = crate::storage::ProtectedStore::open(settings.state_directory.clone())?;
    crate::service::validate_saved_binding(&settings, &saved)?;
    #[cfg(windows)]
    if local_gateway_trust_changed(&settings, ca)? {
        return Err(Failure::TenantMismatch);
    }
    if let Some(peer) = peer {
        if !settings.authorized_peers.iter().any(|value| value == peer) {
            settings.authorized_peers.push(peer.to_owned());
        }
    }
    if let Some(ca) = ca {
        settings.ca_certificate_path = Some(save_ca(ca)?);
    }
    if let Some(console) = console {
        settings.local_console_url = Some(crate::config::local_console(console)?);
    }
    settings.validate()?;
    let executable = std::env::current_exe().map_err(|_| Failure::Unavailable)?;
    let bytes = std::fs::read(executable).map_err(|_| Failure::Unavailable)?;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o755).custom_flags(libc::O_NOFOLLOW);
    }
    use std::io::Write;
    let mut file = options.open(&binary).map_err(|_| Failure::Conflict)?;
    file.write_all(&bytes)
        .and_then(|_| file.sync_all())
        .map_err(|_| Failure::Unavailable)?;
    drop(file);
    #[cfg(windows)]
    crate::platform::windows::protect_install_acl(&binary, true)?;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o644).custom_flags(libc::O_NOFOLLOW);
    }
    options
        .open(&config)
        .and_then(|mut f| {
            f.write_all(&serde_json::to_vec_pretty(&settings).map_err(std::io::Error::other)?)
        })
        .map_err(|_| Failure::Unavailable)?;
    #[cfg(windows)]
    crate::platform::windows::protect_install_acl(&config, true)?;
    #[cfg(target_os = "linux")]
    {
        let unit = include_str!("../packaging/olo-toolgate-client.service");
        write_definition(
            Path::new("/etc/systemd/system/olo-toolgate-client.service"),
            unit.as_bytes(),
        )?;
        command("/usr/bin/systemctl", &["daemon-reload"])?;
        command(
            "/usr/bin/systemctl",
            &["enable", "--now", "olo-toolgate-client.service"],
        )?;
    }
    #[cfg(target_os = "macos")]
    {
        write_definition(
            Path::new("/Library/LaunchDaemons/io.ololabs.toolgate.client.plist"),
            include_bytes!("../packaging/io.ololabs.toolgate.client.plist"),
        )?;
        command(
            "/bin/launchctl",
            &[
                "bootstrap",
                "system",
                "/Library/LaunchDaemons/io.ololabs.toolgate.client.plist",
            ],
        )?;
    }
    #[cfg(windows)]
    {
        // Installer paths are fixed, so binPath quoting cannot contain caller-controlled switches.
        let path = format!("\"{}\" service", binary.to_string_lossy());
        command(
            r"C:\Windows\System32\sc.exe",
            &[
                "create",
                "OloToolGateClient",
                "binPath=",
                &path,
                "start=",
                "auto",
                "obj=",
                "LocalSystem",
            ],
        )?;
        command(
            r"C:\Windows\System32\sc.exe",
            &["start", "OloToolGateClient"],
        )?;
    }
    if let Err(failure) = record_gateway(&settings) {
        tracing::warn!(event="gateway_index",error=?failure);
    }
    Ok(())
}
#[cfg(unix)]
fn write_definition(path: &Path, bytes: &[u8]) -> Result<()> {
    crate::storage::check_parents(path)?;
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o644).custom_flags(libc::O_NOFOLLOW);
    }
    options
        .open(path)
        .and_then(|mut f| f.write_all(bytes))
        .map_err(|_| Failure::Conflict)
}
/// Uninstall retains enrollment custody by default; purge deletes only the fixed protected state directory.
pub fn uninstall(purge: bool) -> Result<()> {
    remove(purge)
}
/// Unloads the launchd job and waits until launchd no longer knows it. bootout answers 36 (operation now in progress)
/// while the daemon is still stopping, which is not a failure.
#[cfg(target_os = "macos")]
fn launchd_bootout() -> Result<()> {
    const JOB: &str = "system/io.ololabs.toolgate.client";
    let status = Command::new("/bin/launchctl")
        .args(["bootout", JOB])
        .status()
        .map_err(|_| Failure::Unavailable)?;
    if !status.success() && status.code() != Some(36) {
        return Err(Failure::Unavailable);
    }
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
    loop {
        let listed = Command::new("/bin/launchctl")
            .args(["print", JOB])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .map_err(|_| Failure::Unavailable)?;
        if !listed.success() {
            return Ok(());
        }
        if std::time::Instant::now() >= deadline {
            return Err(Failure::Unavailable);
        }
        std::thread::sleep(std::time::Duration::from_millis(200));
    }
}

/// Uninstall retains its tenant journal unless the operator explicitly requests purge.
#[cfg(not(windows))]
fn remove(purge: bool) -> Result<()> {
    admin()?;
    let config = Config::load(&config_path())?;
    #[cfg(target_os = "linux")]
    {
        command(
            "/usr/bin/systemctl",
            &["disable", "--now", "olo-toolgate-client.service"],
        )?;
        std::fs::remove_file("/etc/systemd/system/olo-toolgate-client.service")
            .map_err(|_| Failure::Unavailable)?;
        command("/usr/bin/systemctl", &["daemon-reload"])?;
    }
    #[cfg(target_os = "macos")]
    {
        launchd_bootout()?;
        std::fs::remove_file("/Library/LaunchDaemons/io.ololabs.toolgate.client.plist")
            .map_err(|_| Failure::Unavailable)?;
    }
    if purge {
        #[cfg(target_os = "linux")]
        let expected = PathBuf::from("/var/lib/olo-toolgate");
        #[cfg(target_os = "macos")]
        let expected = PathBuf::from("/Library/Application Support/OLO/ToolGate/state");
        #[cfg(windows)]
        let expected = PathBuf::from(r"C:\ProgramData\OLO\ToolGate\state");
        if config.state_directory != expected {
            return Err(Failure::Unauthorized);
        }
        purge_device(
            &expected,
            config_path().parent().ok_or(Failure::Validation)?,
        )?;
    }
    std::fs::remove_file(config_path()).map_err(|_| Failure::Unavailable)?;
    std::fs::remove_file(binary_path()).map_err(|_| Failure::Unavailable)?;
    Ok(())
}

#[cfg(windows)]
fn remove(purge: bool) -> Result<()> {
    admin()?;
    // Removal uses only fixed installation paths, even after a partial uninstall
    // removed the configuration or service. Never follow a custom state path.
    remove_windows_service()?;
    if purge {
        purge_device(
            Path::new(r"C:\ProgramData\OLO\ToolGate\state"),
            config_path().parent().ok_or(Failure::Validation)?,
        )?;
    }
    remove_file_if_present(&config_path())?;
    remove_file_if_present(&binary_path())
}

#[cfg(any(windows, test))]
fn remove_file_if_present(path: &Path) -> Result<()> {
    match std::fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(_) => Err(Failure::Unavailable),
    }
}

#[cfg(windows)]
fn remove_windows_service() -> Result<()> {
    use windows_service::{
        service::{ServiceAccess, ServiceState},
        service_manager::{ServiceManager, ServiceManagerAccess},
        Error,
    };
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)
        .map_err(|_| Failure::Unavailable)?;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
    match manager.open_service(
        "OloToolGateClient",
        ServiceAccess::QUERY_STATUS | ServiceAccess::STOP | ServiceAccess::DELETE,
    ) {
        Ok(service) => {
            let state = service
                .query_status()
                .map_err(|_| Failure::Unavailable)?
                .current_state;
            if state != ServiceState::Stopped && state != ServiceState::StopPending {
                service.stop().map_err(|_| Failure::Unavailable)?;
            }
            while service
                .query_status()
                .map_err(|_| Failure::Unavailable)?
                .current_state
                != ServiceState::Stopped
            {
                if std::time::Instant::now() >= deadline {
                    return Err(Failure::Unavailable);
                }
                std::thread::sleep(std::time::Duration::from_millis(100));
            }
            service.delete().map_err(|_| Failure::Unavailable)?;
        }
        Err(Error::Winapi(error)) if error.raw_os_error() == Some(1060) => return Ok(()),
        Err(Error::Winapi(error)) if error.raw_os_error() == Some(1072) => {}
        Err(_) => return Err(Failure::Unavailable),
    }
    // Close our handle before waiting for SCM to finish deleting the registration.
    loop {
        match manager.open_service("OloToolGateClient", ServiceAccess::QUERY_STATUS) {
            Err(Error::Winapi(error)) if error.raw_os_error() == Some(1060) => return Ok(()),
            Ok(_) => {}
            Err(Error::Winapi(error)) if error.raw_os_error() == Some(1072) => {}
            Err(_) => return Err(Failure::Unavailable),
        }
        if std::time::Instant::now() >= deadline {
            return Err(Failure::Unavailable);
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}

/// Explicit purge removes enrollment, device keys, logs, local tool settings and
/// remembered gateways and CAs. Links inside the state directory
/// are deleted, never followed (`remove_dir_all` deletes links, never their targets).
fn purge_device(state: &Path, config_directory: &Path) -> Result<()> {
    if state.try_exists().map_err(|_| Failure::Unavailable)? {
        if std::fs::symlink_metadata(state)
            .map_err(|_| Failure::Unavailable)?
            .file_type()
            .is_symlink()
        {
            return Err(Failure::Unauthorized);
        }
        crate::storage::check_owned(state, true)?;
        for entry in std::fs::read_dir(state).map_err(|_| Failure::Unavailable)? {
            let entry = entry.map_err(|_| Failure::Unavailable)?;
            let kind = entry.file_type().map_err(|_| Failure::Unavailable)?;
            let path = entry.path();
            if kind.is_dir() {
                std::fs::remove_dir_all(&path)
            } else {
                // A file, or a link removed as itself.
                std::fs::remove_file(&path).or_else(|_| std::fs::remove_dir(&path))
            }
            .map_err(|_| Failure::Unavailable)?;
        }
    }
    if !config_directory
        .try_exists()
        .map_err(|_| Failure::Unavailable)?
    {
        return Ok(());
    }
    for entry in std::fs::read_dir(config_directory).map_err(|_| Failure::Unavailable)? {
        let path = entry.map_err(|_| Failure::Unavailable)?.path();
        let remembered = path
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| {
                name == "gateways.json"
                    || (name.starts_with("gateway-ca-") && name.ends_with(".crt"))
            });
        if remembered {
            std::fs::remove_file(&path).map_err(|_| Failure::Unavailable)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn repair_compares_tls_trust_independently_of_device_issuer() {
        #[cfg(windows)]
        let base = PathBuf::from(std::env::var_os("ProgramData").unwrap());
        #[cfg(target_os = "macos")]
        let base = PathBuf::from("/private/tmp");
        #[cfg(all(unix, not(target_os = "macos")))]
        let base = std::env::temp_dir();
        let directory = base.join(format!(
            "toolgate-trust-test-{}",
            crate::identity::nonce().unwrap()
        ));
        let store = crate::storage::ProtectedStore::open(directory.clone()).unwrap();
        let old = rcgen::generate_simple_self_signed(vec!["localhost".into()])
            .unwrap()
            .cert
            .pem();
        let new = rcgen::generate_simple_self_signed(vec!["localhost".into()])
            .unwrap()
            .cert
            .pem();
        let path = directory.join("ca.crt");
        std::fs::write(&path, &new).unwrap();
        #[cfg(windows)]
        crate::platform::windows::protect_acl(&path).unwrap();
        let settings = Config {
            tenant_id: None,
            deployment: None,
            execution: None,
            gateway: None,
            server_url: "https://localhost:18450".into(),
            state_directory: directory.clone(),
            ipc_endpoint: ipc_endpoint(),
            authorized_peers: vec!["test".into()],
            ca_certificate_path: Some(path.clone()),
            request_timeout_seconds: 10,
            local_console_url: None,
        };
        assert!(!local_gateway_trust_changed(&settings, Some(&new)).unwrap());
        let journal = |issuer: &str| {
            serde_json::to_vec(
                &serde_json::json!({"identity":{"issuerCertificatePem":issuer},"manifest":null}),
            )
            .unwrap()
        };
        store.write("journal.json", &journal(&old)).unwrap();
        // The device issuer is separate from the TLS bootstrap root.
        assert!(!local_gateway_trust_changed(&settings, Some(&new)).unwrap());
        assert!(!local_gateway_trust_changed(&settings, None).unwrap());
        store.write("journal.json", &journal(&new)).unwrap();
        assert!(!local_gateway_trust_changed(&settings, Some(&new.replace('\n', "\r\n"))).unwrap());
        std::fs::write(&path, &old).unwrap();
        assert!(local_gateway_trust_changed(&settings, Some(&new)).unwrap());
        std::fs::remove_file(path).unwrap();
        std::fs::remove_file(directory.join("journal.json")).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn explicit_purge_removes_identity_and_key_before_another_tenant_can_be_installed() {
        let root = std::env::temp_dir().join(format!(
            "toolgate-purge-test-{}",
            crate::identity::nonce().unwrap()
        ));
        let state = root.join("state");
        let profile = state.join("profiles").join("parked");
        std::fs::create_dir_all(&profile).unwrap();
        for file in [
            state.join("journal.json"),
            state.join("device-key"),
            state.join("local-tools.json"),
            profile.join("journal.json"),
            root.join("gateways.json"),
            root.join("gateway-ca-1.crt"),
            root.join("client.json"),
        ] {
            std::fs::write(file, b"x").unwrap();
        }
        // A link inside the state directory is removed, never followed.
        let outside = root.join("outside");
        std::fs::create_dir(&outside).unwrap();
        std::fs::write(outside.join("keep"), b"x").unwrap();
        std::os::unix::fs::symlink(&outside, state.join("link")).unwrap();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&state, std::fs::Permissions::from_mode(0o700)).unwrap();
        purge_device(&state, &root).unwrap();
        // A new tenant gets a fresh device key after an explicit purge.
        let left: Vec<_> = std::fs::read_dir(&state)
            .unwrap()
            .map(|entry| entry.unwrap().file_name())
            .collect();
        assert!(left.is_empty());
        assert!(!root.join("gateways.json").exists());
        assert!(!root.join("gateway-ca-1.crt").exists());
        // The configuration and binary are removed by uninstall itself.
        assert!(root.join("client.json").exists());
        assert!(outside.join("keep").exists());
        // Retry finishes safely after the first pass already removed files.
        purge_device(&state, &root).unwrap();
        remove_file_if_present(&root.join("client.json")).unwrap();
        remove_file_if_present(&root.join("client.json")).unwrap();
        assert!(!state.join("device-key").exists());
        std::fs::remove_dir_all(root).unwrap();
    }
}
