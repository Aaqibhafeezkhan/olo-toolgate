// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! `olo-toolgate-tool-host`: reads its configuration from `TOOLGATE_TOOL_HOST_CONFIG` and serves
//! Gateway dispatch until stopped.

use std::process::ExitCode;
use tool_host::config::Config;

#[tokio::main]
async fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .json()
        .with_writer(std::io::stderr)
        .init();
    let Some(path) = std::env::var_os("TOOLGATE_TOOL_HOST_CONFIG") else {
        eprintln!("TOOLGATE_TOOL_HOST_CONFIG must name the Tool Host configuration file");
        return ExitCode::from(78);
    };
    let config = match Config::load(std::path::Path::new(&path)) {
        Ok(config) => config,
        Err(e) => {
            eprintln!("configuration refused: {e}");
            return ExitCode::from(78);
        }
    };
    let host = match tool_host::server::Host::new(config.clone()) {
        Ok(host) => host,
        Err(e) => {
            eprintln!("startup refused: {e}");
            return ExitCode::from(78);
        }
    };
    if let Err(failure) = host.control().qualify().await {
        eprintln!("workload identity refused: {failure}");
        return ExitCode::from(78);
    }
    if config.enrollment.is_some() {
        match tool_host::device::Device::open(&config, host.control()) {
            Ok(device) => {
                tokio::spawn(device.run());
                // An enrolled host also takes calls relayed through Control, so it runs tools
                // wherever it is, whether or not the Gateway can reach its listener.
                tokio::spawn(tool_host::server::pull(host.clone()));
                tokio::spawn(tool_host::mcp_scan::pull(host.clone()));
            }
            Err(e) => {
                eprintln!("enrollment refused: {e}");
                return ExitCode::from(78);
            }
        }
    }
    let listener = match tokio::net::TcpListener::bind(config.listen).await {
        Ok(listener) => listener,
        Err(e) => {
            eprintln!("cannot listen on {}: {e}", config.listen);
            return ExitCode::from(71);
        }
    };
    tracing::info!(host_id = %config.host_id, listen = %config.listen, "tool host ready");
    let shutdown = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    match axum::serve(listener, tool_host::server::router(host))
        .with_graceful_shutdown(shutdown)
        .await
    {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("tool host stopped: {e}");
            ExitCode::from(70)
        }
    }
}
