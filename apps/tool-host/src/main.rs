// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! `olo-toolgate-tool-host`: reads its configuration from `TOOLGATE_TOOL_HOST_CONFIG` and serves
//! Gateway dispatch until stopped.

use std::process::ExitCode;
use tool_host::config::Config;

#[tokio::main(worker_threads = 2)]
async fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .json()
        .with_writer(std::io::stderr)
        .init();
    let arguments: Vec<_> = std::env::args_os().skip(1).collect();
    if arguments == [std::ffi::OsString::from("--isolation-probe")] {
        return match tool_host::sandbox::isolation_probe() {
            Ok(value) => {
                println!("{value}");
                // Pin this process's namespaces until the supervisor has
                // compared both live probes, then close on private stdin.
                use tokio::io::AsyncReadExt;
                let _ = tokio::io::stdin().read_u8().await;
                ExitCode::SUCCESS
            }
            Err(_) => ExitCode::from(78),
        };
    }
    if arguments == [std::ffi::OsString::from("--invocation-broker")] {
        return match tool_host::broker_process::serve(
            tokio::io::BufReader::new(tokio::io::stdin()),
            tokio::io::stdout(),
        )
        .await
        {
            Ok(()) => ExitCode::SUCCESS,
            Err(error) => {
                eprintln!("invocation broker stopped: {error}");
                ExitCode::from(78)
            }
        };
    }
    if !arguments.is_empty() {
        eprintln!("unsupported Tool Host mode");
        return ExitCode::from(78);
    }
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
    let dispatch_tls = match config
        .dispatch_tls
        .as_ref()
        .map(|settings| {
            tool_host::dispatch_tls::Listener::open(settings, &config.control.tenant_id)
        })
        .transpose()
    {
        Ok(tls) => tls,
        Err(error) => {
            eprintln!("dispatch TLS refused: {error}");
            return ExitCode::from(78);
        }
    };
    if let Err(failure) = host.qualify_execution().await {
        eprintln!("execution tier refused: {failure}");
        return ExitCode::from(78);
    }
    if let Err(failure) = host.control().qualify().await {
        eprintln!("workload identity refused: {failure}");
        return ExitCode::from(78);
    }
    // Bind and validate all inbound custody before any pull/authority task can admit work.
    let listener = if dispatch_tls.is_some() || config.development_dispatch_http {
        Some(match tokio::net::TcpListener::bind(config.listen).await {
            Ok(listener) => listener,
            Err(_) => {
                eprintln!("dispatch listener unavailable");
                return ExitCode::from(71);
            }
        })
    } else {
        None
    };
    tokio::spawn(tool_host::server::deliver_evidence(host.clone()));
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
    tokio::spawn(host.clone().supervise_revocations());
    if config.dispatch_tls.is_none() && !config.development_dispatch_http {
        tracing::info!(host_id=%config.host_id,"outbound-only tool host ready");
        let _ = tokio::signal::ctrl_c().await;
        return ExitCode::SUCCESS;
    }
    let Some(listener) = listener else {
        return ExitCode::from(78);
    };
    tracing::info!(host_id = %config.host_id, listen = %config.listen, "tool host ready");
    if let Some(tls) = dispatch_tls {
        return tokio::select! {
            result=tls.serve(listener,tool_host::server::router(host))=>match result {
                Ok(())=>ExitCode::SUCCESS,Err(_)=>ExitCode::from(71),
            },
            _=tokio::signal::ctrl_c()=>ExitCode::SUCCESS,
        };
    }
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
