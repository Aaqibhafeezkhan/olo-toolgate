// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Unprivileged CLI; only the OS service entry point loads device secrets.
use olo_toolgate_client::{Failure, Result};
use olo_toolgate_contracts::ClientIpcOperation;
fn main() {
    tracing_subscriber::fmt()
        .json()
        .with_target(false)
        .with_writer(std::io::stderr)
        .init();
    let arguments: Vec<String> = std::env::args().skip(1).collect();
    #[cfg(windows)]
    if arguments == ["service"] {
        if let Err(failure) = olo_toolgate_client::runtime::windows::dispatch() {
            tracing::error!(event="client_startup",error=?failure);
            std::process::exit(1);
        }
        return;
    }
    let result = olo_toolgate_client::runtime::executor()
        .and_then(|runtime| runtime.block_on(command(arguments)));
    if let Err(failure) = result {
        tracing::error!(event="client_command",result="rejected",error=?failure);
        std::process::exit(1);
    }
}
async fn command(arguments: Vec<String>) -> Result<()> {
    let Some(operation) = arguments.first().map(String::as_str) else {
        return Err(Failure::Validation);
    };
    match operation {
        "activity" if arguments.len() == 1 => {
            let response = tokio::time::timeout(
                std::time::Duration::from_secs(5),
                olo_toolgate_client::ipc::call(
                    &olo_toolgate_client::install::ipc_endpoint(),
                    ClientIpcOperation::Activity,
                ),
            )
            .await
            .map_err(|_| Failure::Unavailable)??;
            println!(
                "{}",
                serde_json::to_string(&response.activity.ok_or(Failure::Unavailable)?)
                    .map_err(|_| Failure::Validation)?
            );
            Ok(())
        }
        // Every gateway connection with its health, name and activity, focused first.
        "connections" if arguments.len() == 1 => {
            let response = tokio::time::timeout(
                std::time::Duration::from_secs(5),
                olo_toolgate_client::ipc::call(
                    &olo_toolgate_client::install::ipc_endpoint(),
                    ClientIpcOperation::Activity,
                ),
            )
            .await
            .map_err(|_| Failure::Unavailable)??;
            println!(
                "{}",
                serde_json::to_string(&response.connections.ok_or(Failure::Unavailable)?)
                    .map_err(|_| Failure::Validation)?
            );
            Ok(())
        }
        // The owner's power switch; `status` never waits behind a check-in.
        "power"
            if arguments.len() == 2 && ["on", "off", "status"].contains(&arguments[1].as_str()) =>
        {
            let request = match arguments[1].as_str() {
                "on" => ClientIpcOperation::PowerOn,
                "off" => ClientIpcOperation::PowerOff,
                _ => ClientIpcOperation::Activity,
            };
            let response = tokio::time::timeout(
                std::time::Duration::from_secs(5),
                olo_toolgate_client::ipc::call(
                    &olo_toolgate_client::install::ipc_endpoint(),
                    request,
                ),
            )
            .await
            .map_err(|_| Failure::Unavailable)??;
            if response.error.is_some() {
                return Err(Failure::Unavailable);
            }
            println!(
                "{}",
                match response.power.ok_or(Failure::Unavailable)? {
                    olo_toolgate_contracts::DevicePower::On => "on",
                    olo_toolgate_contracts::DevicePower::Off => "off",
                }
            );
            Ok(())
        }
        "authorization-profiles" if arguments.len() == 1 => {
            println!(
                "{}",
                serde_json::to_string_pretty(
                    &olo_toolgate_client::authorization_profile::builtin_profiles()?
                )
                .map_err(|_| Failure::Validation)?
            );
            Ok(())
        }
        "runtimes"
            if arguments.len() == 2 && ["status", "prepare"].contains(&arguments[1].as_str()) =>
        {
            let op = if arguments[1] == "status" {
                olo_toolgate_contracts::LocalRuntimeOperation::Status
            } else {
                olo_toolgate_contracts::LocalRuntimeOperation::Prepare
            };
            let response = tokio::time::timeout(
                std::time::Duration::from_secs(220),
                olo_toolgate_client::ipc::call_runtime(
                    &olo_toolgate_client::install::ipc_endpoint(),
                    op,
                    None,
                ),
            )
            .await
            .map_err(|_| Failure::Expired)??;
            println!(
                "{}",
                serde_json::to_string(&response).map_err(|_| Failure::Validation)?
            );
            if response.error.is_some()
                || response
                    .health
                    .is_some_and(|h| arguments[1] == "prepare" && !h.ready)
            {
                return Err(Failure::Unavailable);
            }
            Ok(())
        }
        "run" if arguments.len() == 3 => {
            let input = olo_toolgate_contracts::LocalToolInput {
                protocol_version: 1,
                request_id: olo_toolgate_client::identity::nonce()?,
                tool_id: arguments[1].clone(),
                arguments: serde_json::from_str(&arguments[2]).map_err(|_| Failure::Validation)?,
            };
            let response = tokio::time::timeout(
                std::time::Duration::from_secs(220),
                olo_toolgate_client::ipc::call_runtime(
                    &olo_toolgate_client::install::ipc_endpoint(),
                    olo_toolgate_contracts::LocalRuntimeOperation::Invoke,
                    Some(input),
                ),
            )
            .await
            .map_err(|_| Failure::Expired)??;
            println!(
                "{}",
                serde_json::to_string(&response).map_err(|_| Failure::Validation)?
            );
            if response.error.is_some() {
                return Err(Failure::Unavailable);
            }
            Ok(())
        }
        "tools" if arguments.len() == 1 || arguments.len() == 3 => {
            let agent =
                (arguments.len() == 3 && arguments[1] == "--agent").then(|| arguments[2].clone());
            let invocation = if arguments.len() == 3 && agent.is_none() {
                Some(olo_toolgate_contracts::BuiltinInvocation {
                    tool_id: arguments[1].clone(),
                    arguments: serde_json::from_str(&arguments[2])
                        .map_err(|_| Failure::Validation)?,
                })
            } else {
                None
            };
            let response = tokio::time::timeout(
                std::time::Duration::from_secs(20),
                olo_toolgate_client::ipc::call_tool_for_agent(
                    &olo_toolgate_client::install::ipc_endpoint(),
                    invocation,
                    agent,
                ),
            )
            .await
            .map_err(|_| Failure::Unavailable)??;
            println!(
                "{}",
                serde_json::to_string(&response).map_err(|_| Failure::Validation)?
            );
            if response.error.is_some() {
                return Err(Failure::Unauthorized);
            }
            Ok(())
        }
        "version" if arguments.len() == 1 => {
            println!("{}", env!("CARGO_PKG_VERSION"));
            Ok(())
        }
        "build" if arguments.len() == 1 => {
            println!("{}", build_label());
            Ok(())
        }
        "resolve-server" if arguments.len() == 3 && arguments[1] == "--server" => {
            println!(
                "{}",
                olo_toolgate_client::browser::installation_server(&arguments[2]).await?
            );
            Ok(())
        }
        "resolve-installation" if arguments.len() == 3 && arguments[1] == "--server" => {
            println!(
                "{}",
                olo_toolgate_client::browser::installation(&arguments[2])
                    .await?
                    .server_url
            );
            Ok(())
        }
        "install" | "reinstall" | "configure" | "reenroll"
            if (arguments.len() == 3 || arguments.len() == 5)
                && arguments[1] == "--server"
                && (arguments.len() == 3 || arguments[3] == "--peer") =>
        {
            let peer = arguments.get(4).map(String::as_str);
            let installation = olo_toolgate_client::browser::installation(&arguments[2]).await?;
            let server = &installation.server_url;
            let ca = installation.ca_certificate_pem.as_deref();
            let console = installation.console_url.as_deref();
            if operation == "configure" {
                olo_toolgate_client::install::configure_with_ca(server, peer, ca, console)
            } else if operation == "reenroll" {
                olo_toolgate_client::install::reenroll_with_ca(server, peer, ca, console)
            } else if operation == "reinstall" {
                olo_toolgate_client::install::reinstall_with_ca(server, peer, ca, console)
            } else {
                olo_toolgate_client::install::install_for_peer_with_ca(server, peer, ca, console)
            }
        }
        "uninstall" if arguments.len() == 1 || arguments == ["uninstall", "--purge"] => {
            olo_toolgate_client::install::uninstall(arguments.len() == 2)
        }
        "service" if arguments.len() == 1 || arguments.len() == 3 && arguments[1] == "--config" => {
            let path = if arguments.len() == 3 {
                std::path::PathBuf::from(&arguments[2])
            } else {
                olo_toolgate_client::install::config_path()
            };
            let config = olo_toolgate_client::config::Config::load(&path)?;
            let (tx, rx) = tokio::sync::watch::channel(false);
            tokio::spawn(async move {
                #[cfg(unix)]
                {
                    if let Ok(mut signal) =
                        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
                    {
                        tokio::select! {_=signal.recv()=>{},_=tokio::signal::ctrl_c()=>{}}
                    }
                }
                #[cfg(windows)]
                {
                    let _ = tokio::signal::ctrl_c().await;
                }
                let _ = tx.send(true);
            });
            olo_toolgate_client::runtime::run(config, rx).await
        }
        "health" | "enroll" | "request-approval" | "check-in" if arguments.len() == 1 => {
            let request = match operation {
                "health" => ClientIpcOperation::Health,
                "enroll" | "request-approval" => ClientIpcOperation::Enroll,
                _ => ClientIpcOperation::CheckIn,
            };
            let response = tokio::time::timeout(
                std::time::Duration::from_secs(20),
                olo_toolgate_client::ipc::call(
                    &olo_toolgate_client::install::ipc_endpoint(),
                    request,
                ),
            )
            .await
            .map_err(|_| Failure::Unavailable)??;
            if response.error.is_some() {
                println!(
                    "{}",
                    serde_json::to_string(&response).map_err(|_| Failure::Validation)?
                );
                return Err(Failure::Unavailable);
            }
            if let Some(challenge) = response.challenge {
                let mut url = format!(
                    "{}?code={}",
                    challenge.verification_uri, challenge.user_code
                );
                // A local gateway's approval page is served by its loopback console.
                let local = olo_toolgate_client::config::Config::load(
                    &olo_toolgate_client::install::config_path(),
                )
                .ok()
                .and_then(|config| Some((config.server_url, config.local_console_url?)))
                .or_else(|| {
                    Some((
                        "https://localhost:18450".to_owned(),
                        "http://127.0.0.1:18090".to_owned(),
                    ))
                });
                if let Some((server, console)) = local {
                    if let Some(route) = url.strip_prefix(&format!("{server}/")) {
                        url = format!("{console}/{route}");
                    }
                }
                println!(
                    "Open {url} to confirm this device. Enrollment expires at {}.",
                    challenge.expires_at_unix_ms
                );
                println!(
                    "Compare this key fingerprint in your browser: {}",
                    challenge.key_fingerprint
                );
                // Browser launching belongs to the ordinary user process, never the protected service.
                if let Err(failure) = olo_toolgate_client::platform::open_browser(&url) {
                    tracing::warn!(event="browser_open",error=?failure);
                }
            }
            if let Some(health) = response.health {
                println!(
                    "{}",
                    serde_json::to_string(&health).map_err(|_| Failure::Validation)?
                );
            }
            Ok(())
        }
        _ => Err(Failure::Unsupported),
    }
}
/// CI run number and short commit captured at compile time; "local" otherwise.
fn build_label() -> String {
    match (option_env!("GITHUB_RUN_NUMBER"), option_env!("GITHUB_SHA")) {
        (Some(run), Some(sha)) => format!("{run} ({})", &sha[..sha.len().min(7)]),
        _ => "local".into(),
    }
}
