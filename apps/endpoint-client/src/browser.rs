// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Browser setup invokes only the protected client shipped beside the native host.
use crate::{Failure, Result};
use serde::Deserialize;
use std::path::Path;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct SiteConfiguration {
    server_url: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LocalTrust {
    server_url: String,
    ca_certificate_pem: String,
}
pub struct Installation {
    pub tenant_id: String,
    pub server_url: String,
    pub ca_certificate_pem: Option<String>,
    /// Loopback console that published `ca_certificate_pem`.
    pub console_url: Option<String>,
}
/// Reassert the browser-verified UUID after elevation and before installation can write any state.
pub async fn installation_expected(value: &str, expected: Option<&str>) -> Result<Installation> {
    if let Some(tenant) = expected {
        crate::contracts::Contracts::shared()?.encode("TenantUuid", &tenant)?;
    }
    let result = installation(value).await?;
    check_installation_tenant(&result, expected)?;
    Ok(result)
}
fn check_installation_tenant(value: &Installation, expected: Option<&str>) -> Result<()> {
    if expected.is_some_and(|tenant| tenant != value.tenant_id) {
        return Err(Failure::TenantMismatch);
    }
    Ok(())
}
/// Bootstrap local quickstart trust through loopback only, then verify HTTPS before installation.
pub async fn installation(value: &str) -> Result<Installation> {
    let server_url = installation_server(value).await?;
    let input = value.trim();
    let console =
        if input.starts_with("http://localhost:") || input.starts_with("http://127.0.0.1:") {
            Some(crate::config::local_console(input)?)
        } else if input.starts_with("localhost:") || input.starts_with("127.0.0.1:") {
            Some(crate::config::local_console(&format!("http://{input}"))?)
        } else if crate::config::loopback(&server_url) {
            // A local gateway URL alone does not name its console; use the one recorded when
            // this gateway was first set up, then the default Quickstart console.
            crate::install::remembered_console(&server_url).or_else(|| {
                matches!(
                    server_url.as_str(),
                    "https://localhost:18450" | "https://127.0.0.1:18450"
                )
                .then(|| "http://127.0.0.1:18090".to_owned())
            })
        } else {
            None
        };
    let Some(console) = console else {
        let client = reqwest::Client::builder()
            .no_proxy()
            .https_only(true)
            .redirect(reqwest::redirect::Policy::none())
            .timeout(std::time::Duration::from_secs(15))
            .build()
            .map_err(|_| Failure::Unavailable)?;
        let manifest = installation_discovery(&client, &server_url).await?;
        return Ok(Installation {
            tenant_id: manifest.tenant_id,
            server_url: manifest.control_url,
            ca_certificate_pem: None,
            console_url: None,
        });
    };
    let _ = rustls::crypto::ring::default_provider().install_default();
    let client = reqwest::Client::builder()
        .no_proxy()
        // This loopback HTTP bootstrap must also work on fresh systems without OS CA roots.
        .tls_certs_only(std::iter::empty())
        .redirect(reqwest::redirect::Policy::none())
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|_| Failure::Unavailable)?;
    let trust: LocalTrust = serde_json::from_slice(
        &fetch(
            &client,
            &format!("{console}/api/public/v1/clients/local-trust"),
            20000,
        )
        .await?,
    )
    .map_err(|_| Failure::Validation)?;
    let url = reqwest::Url::parse(&server_url).map_err(|_| Failure::Validation)?;
    if !matches!(url.host_str(), Some("localhost" | "127.0.0.1"))
        || crate::config::origin(&trust.server_url)? != server_url
        || trust.ca_certificate_pem.len() > 16384
    {
        return Err(Failure::Validation);
    }
    let root = reqwest::Certificate::from_pem(trust.ca_certificate_pem.as_bytes())
        .map_err(|_| Failure::Validation)?;
    let verified = reqwest::Client::builder()
        .no_proxy()
        .https_only(true)
        .tls_certs_only([root])
        .redirect(reqwest::redirect::Policy::none())
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|_| Failure::Unavailable)?;
    let manifest = installation_discovery(&verified, &server_url).await?;
    Ok(Installation {
        tenant_id: manifest.tenant_id,
        server_url: manifest.control_url,
        ca_certificate_pem: Some(trust.ca_certificate_pem),
        console_url: Some(console),
    })
}
async fn installation_discovery(
    client: &reqwest::Client,
    origin: &str,
) -> Result<olo_toolgate_contracts::v2::TenantDiscovery> {
    let bytes = fetch(
        client,
        &format!("{origin}/.well-known/olo-toolgate-client"),
        98304,
    )
    .await?;
    let envelope =
        crate::contracts::Contracts::shared()?.decode("SignedTenantDiscovery", &bytes)?;
    Ok(crate::discovery::verify(&envelope, origin, None, None, crate::now())?.manifest)
}
pub fn console_origin(value: &str) -> Result<String> {
    let mut url = reqwest::Url::parse(value).map_err(|_| Failure::Validation)?;
    if !url.path().starts_with("/console/") || value.len() > 2048 {
        return Err(Failure::Validation);
    }
    url.set_path("/");
    url.set_query(None);
    url.set_fragment(None);
    if url.scheme() == "http"
        && matches!(url.host_str(), Some("localhost" | "127.0.0.1"))
        && url.username().is_empty()
        && url.password().is_none()
    {
        return Ok(url.as_str().trim_end_matches('/').to_owned());
    }
    crate::config::origin(url.as_str())
}
async fn fetch(client: &reqwest::Client, url: &str, limit: u64) -> Result<Vec<u8>> {
    let mut response = client
        .get(url)
        .send()
        .await
        .map_err(|_| Failure::Unavailable)?;
    if !response.status().is_success() || response.content_length().is_some_and(|n| n > limit) {
        return Err(Failure::Validation);
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| Failure::Unavailable)? {
        if bytes.len() as u64 + chunk.len() as u64 > limit {
            return Err(Failure::Validation);
        }
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}
pub async fn connect(console_url: &str, expected_tenant: Option<&str>) -> Result<String> {
    let origin = console_origin(console_url)?;
    let installed = crate::install::config_path();
    let configured = if installed.exists() {
        let settings = crate::config::Config::load(&installed)?;
        if expected_tenant.is_some_and(|tenant| settings.tenant_id.as_deref() != Some(tenant)) {
            return Err(Failure::TenantMismatch);
        }
        authorize_console(&settings, console_url)?;
        Some(settings)
    } else {
        None
    };
    let server = site_server(&origin).await?;
    if let Some(settings) = &configured {
        if crate::config::origin(&settings.server_url)? != server {
            return Err(Failure::TenantMismatch);
        }
    }
    // A local console also publishes its gateway's CA, so any local stack connects in one click.
    let source = if origin.starts_with("http://") {
        origin
    } else {
        server.clone()
    };
    configure_client(server, source, expected_tenant).await
}
/// Only protected installation configuration grants an origin access to local device state.
pub fn authorize_console(config: &crate::config::Config, console_url: &str) -> Result<()> {
    let origin = console_origin(console_url)?;
    if origin != crate::config::origin(&config.server_url)?
        && config.local_console_url.as_deref() != Some(origin.as_str())
        && !crate::service::saved_console_audience(config, &origin)?
    {
        return Err(Failure::TenantMismatch);
    }
    Ok(())
}
/// Setup can use the local console address while service traffic remains HTTPS.
pub async fn installation_server(value: &str) -> Result<String> {
    let value = value.trim();
    if let Ok(server) = crate::config::origin(value) {
        return Ok(server);
    }
    let value = if value.starts_with("localhost:") || value.starts_with("127.0.0.1:") {
        format!("http://{value}")
    } else {
        value.to_owned()
    };
    let url = reqwest::Url::parse(&value).map_err(|_| Failure::Validation)?;
    if value.len() > 2048
        || url.scheme() != "http"
        || !matches!(url.host_str(), Some("localhost" | "127.0.0.1"))
        || !url.username().is_empty()
        || url.password().is_some()
        || url.path() != "/"
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(Failure::Validation);
    }
    site_server(url.as_str().trim_end_matches('/')).await
}
async fn site_server(origin: &str) -> Result<String> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let mut builder = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(std::time::Duration::from_secs(15));
    if origin.starts_with("http://") {
        builder = builder.tls_certs_only(std::iter::empty());
    }
    let client = builder.build().map_err(|_| Failure::Unavailable)?;
    let document = fetch(
        &client,
        &format!("{origin}/api/public/v1/clients/configuration"),
        4096,
    )
    .await?;
    let config: SiteConfiguration =
        serde_json::from_slice(&document).map_err(|_| Failure::Validation)?;
    crate::config::origin(&config.server_url)
}
async fn configure_client(
    server: String,
    source: String,
    expected_tenant: Option<&str>,
) -> Result<String> {
    let installation = installation_expected(&source, expected_tenant).await?;
    if installation.server_url != server {
        return Err(Failure::Validation);
    }
    let executable = std::env::current_exe().map_err(|_| Failure::Unavailable)?;
    let cli = executable
        .parent()
        .ok_or(Failure::Validation)?
        .join("olo-toolgate-client.exe");
    crate::storage::check_parents(&cli)?;
    crate::storage::check_owned(&cli, false)?;
    let peer = current_peer()?;
    let installed = crate::install::config_path();
    let operation = if installed.exists() {
        let settings = crate::config::Config::load(&installed)?;
        if crate::config::origin(&settings.server_url)? != server
            || settings.tenant_id.as_deref() != Some(installation.tenant_id.as_str())
        {
            return Err(Failure::TenantMismatch);
        }
        if crate::config::origin(&settings.server_url)? == server
            && settings.authorized_peers.contains(&peer)
        {
            let trust_matches = match (
                installation.ca_certificate_pem.as_deref(),
                settings.ca_certificate_path.as_ref(),
            ) {
                (Some(ca), Some(path)) => {
                    let bytes = crate::storage::read_owned(path, 16384, false)?;
                    let current = std::str::from_utf8(&bytes).map_err(|_| Failure::Validation)?;
                    crate::identity::certificate_der(ca)?
                        == crate::identity::certificate_der(current)?
                }
                (None, _) => true,
                _ => false,
            };
            if trust_matches {
                match connected(&settings.ipc_endpoint).await {
                    Some(true) => return Ok(server),
                    // Ask Control for a governed review while retaining the UUID/key anchor.
                    Some(false) => {
                        let response = crate::ipc::console_call(
                            &settings.ipc_endpoint,
                            olo_toolgate_contracts::v2::ConsoleClientOperation::Enroll,
                            &format!("{server}/console/"),
                        )
                        .await?;
                        match response.error {
                            Some(olo_toolgate_contracts::ErrorCode::TenantMismatch) => {
                                return Err(Failure::TenantMismatch)
                            }
                            Some(_) => return Err(Failure::Unavailable),
                            None => {}
                        }
                        return Ok(server);
                    }
                    None => {}
                }
            } else {
                return Err(Failure::TenantMismatch);
            }
        }
        "configure"
    } else {
        "install"
    };
    let parameters = format!(
        "{operation} --server \"{source}\" --peer \"{peer}\" --tenant-id {}",
        installation.tenant_id
    );
    elevate(&cli, &parameters)?;
    if operation == "configure" && connected(&crate::install::ipc_endpoint()).await == Some(false) {
        let response = crate::ipc::console_call(
            &crate::install::ipc_endpoint(),
            olo_toolgate_contracts::v2::ConsoleClientOperation::Enroll,
            &format!("{server}/console/"),
        )
        .await?;
        if response.error.is_some() {
            return Err(Failure::Unavailable);
        }
    }
    Ok(server)
}
/// Whether the focused enrollment works: `Some(true)` when ready or awaiting enrollment,
/// `Some(false)` when revoked or still offline after an immediate check-in, `None` when the
/// service cannot be asked.
async fn connected(endpoint: &str) -> Option<bool> {
    use olo_toolgate_contracts::{ClientIpcOperation, EndpointState};
    let health = |operation| async move {
        tokio::time::timeout(
            std::time::Duration::from_secs(25),
            crate::ipc::call(endpoint, operation),
        )
        .await
        .ok()?
        .ok()?
        .health
    };
    // A just-restarted service takes a moment to listen.
    let mut current = None;
    for _ in 0..10 {
        current = health(ClientIpcOperation::Health).await;
        if current.is_some() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
    }
    let current = current?;
    if current.ready
        || matches!(
            current.state,
            EndpointState::Unenrolled | EndpointState::Pending
        )
    {
        return Some(true);
    }
    if current.state == EndpointState::Revoked {
        return Some(false);
    }
    // Offline may only mean no check-in since the service started; ask for one now.
    Some(
        health(ClientIpcOperation::CheckIn)
            .await
            .is_some_and(|health| health.ready),
    )
}
#[cfg(windows)]
fn current_peer() -> Result<String> {
    crate::platform::windows::current_sid()
}
#[cfg(not(windows))]
fn current_peer() -> Result<String> {
    Err(Failure::Unsupported)
}
#[cfg(not(windows))]
fn elevate(_: &Path, _: &str) -> Result<()> {
    Err(Failure::Unsupported)
}
#[cfg(windows)]
fn elevate(path: &Path, parameters: &str) -> Result<()> {
    use windows_sys::Win32::{
        Foundation::CloseHandle,
        System::Threading::{GetExitCodeProcess, WaitForSingleObject},
        UI::Shell::{ShellExecuteExW, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW},
    };
    let wide = |s: &str| s.encode_utf16().chain(Some(0)).collect::<Vec<_>>();
    let path = wide(&path.to_string_lossy());
    let verb = wide("runas");
    let parameters = wide(parameters);
    unsafe {
        let mut info: SHELLEXECUTEINFOW = std::mem::zeroed();
        info.cbSize = std::mem::size_of::<SHELLEXECUTEINFOW>() as u32;
        info.fMask = SEE_MASK_NOCLOSEPROCESS;
        info.lpVerb = verb.as_ptr();
        info.lpFile = path.as_ptr();
        info.lpParameters = parameters.as_ptr();
        info.nShow = 0;
        if ShellExecuteExW(&mut info) == 0 || info.hProcess.is_null() {
            return Err(Failure::Unavailable);
        }
        let waited = WaitForSingleObject(info.hProcess, 600000);
        let mut code = 1;
        let success = waited == 0 && GetExitCodeProcess(info.hProcess, &mut code) != 0 && code == 0;
        CloseHandle(info.hProcess);
        if !success {
            return Err(Failure::Unavailable);
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    async fn local_configuration(response: String) -> (String, tokio::task::JoinHandle<()>) {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let origin = format!("http://{}", listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut request = Vec::new();
            loop {
                let mut bytes = [0; 1024];
                let count = stream.read(&mut bytes).await.unwrap();
                assert!(count > 0 && request.len() + count < 8192);
                request.extend_from_slice(&bytes[..count]);
                if request.windows(4).any(|part| part == b"\r\n\r\n") {
                    break;
                }
            }
            let request = String::from_utf8(request).unwrap();
            assert!(request.starts_with("GET /api/public/v1/clients/configuration HTTP/1.1\r\n"));
            assert!(!request.to_lowercase().contains("authorization:"));
            stream.write_all(response.as_bytes()).await.unwrap();
        });
        (origin, server)
    }
    fn response(body: &str) -> String {
        format!(
            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        )
    }
    #[tokio::test]
    async fn installer_resolves_local_console_to_https_without_browser_setup() {
        let (origin, server) =
            local_configuration(response(r#"{"serverUrl":"https://localhost:18450"}"#)).await;
        let shorthand = origin.strip_prefix("http://").unwrap();
        assert_eq!(
            installation_server(shorthand).await.unwrap(),
            "https://localhost:18450"
        );
        server.await.unwrap();
        assert_eq!(
            installation_server(" https://gate.example/ ")
                .await
                .unwrap(),
            "https://gate.example"
        );
    }
    #[tokio::test]
    async fn installer_rejects_remote_http_credentials_and_non_origin_urls() {
        for value in [
            "http://gate.example",
            "http://localhost.evil.example:18090",
            "http://user:password@localhost:18090",
            "https://user:password@gate.example",
            "http://localhost:18090/other",
            "http://localhost:18090/?server=evil",
            "http://localhost:18090/#enroll",
            "https://gate.example/other",
            "https://gate.example?server=evil",
            "file:///etc/passwd",
        ] {
            assert!(installation_server(value).await.is_err(), "{value}");
        }
    }
    #[tokio::test]
    async fn installer_never_follows_redirects_or_accepts_invalid_configuration() {
        for body in [
            r#"{"serverUrl":"http://gate.example"}"#,
            r#"{"serverUrl":"https://user:password@gate.example"}"#,
            r#"{"serverUrl":"https://gate.example/other"}"#,
            r#"{"serverUrl":"https://gate.example","credentials":"unexpected"}"#,
            "not-json",
        ] {
            let (origin, server) = local_configuration(response(body)).await;
            assert!(installation_server(&origin).await.is_err());
            server.await.unwrap();
        }
        let (origin, server) = local_configuration(
            "HTTP/1.1 302 Found\r\nLocation: https://gate.example\r\nContent-Length: 0\r\n\r\n"
                .into(),
        )
        .await;
        assert!(installation_server(&origin).await.is_err());
        server.await.unwrap();
        let (origin, server) = local_configuration(response(&"x".repeat(4097))).await;
        assert!(installation_server(&origin).await.is_err());
        server.await.unwrap();
    }
    #[test]
    fn a_reassigned_origin_cannot_change_the_tenant_after_elevation() {
        let installation = Installation {
            tenant_id: "10000000-0000-4000-8000-000000000002".into(),
            server_url: "https://gate.example".into(),
            ca_certificate_pem: None,
            console_url: None,
        };
        assert_eq!(
            check_installation_tenant(&installation, Some("10000000-0000-4000-8000-000000000001")),
            Err(Failure::TenantMismatch)
        );
        assert_eq!(
            check_installation_tenant(&installation, Some(&installation.tenant_id)),
            Ok(())
        );
    }
    #[test]
    fn only_https_console_origins_are_accepted() {
        assert_eq!(
            console_origin("https://gate.example/console/#enroll").unwrap(),
            "https://gate.example"
        );
        for url in [
            "http://gate.example/console/",
            "https://user:password@gate.example/console/",
            "https://gate.example/other",
        ] {
            assert!(console_origin(url).is_err());
        }
    }
}
