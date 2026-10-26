// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Service bootstrap. Secrets/config contents are never included in diagnostics.
use olo_toolgate_gateway::{
    application::Gateway,
    auth::Authenticator,
    config::{read_bytes, read_json, Config},
    http::{management_router, runtime_router, AppState},
    server, unix_ms,
    validation::Contracts,
};
use std::{
    path::PathBuf,
    sync::{atomic::Ordering, Arc},
    time::Duration,
};
use tokio::{net::TcpListener, sync::watch};

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .json()
        .with_target(false)
        .with_writer(std::io::stderr)
        .init();
    if let Err(message) = run().await {
        tracing::error!(
            service = "gateway",
            version = env!("CARGO_PKG_VERSION"),
            event = "startup_or_runtime_failure",
            reason = message
        );
        std::process::exit(1);
    }
}

async fn run() -> Result<(), &'static str> {
    let contracts = Contracts::new()?;
    let config_path = PathBuf::from(
        std::env::var("TOOLGATE_GATEWAY_CONFIG")
            .map_err(|_| "TOOLGATE_GATEWAY_CONFIG is required")?,
    );
    let credentials_path = PathBuf::from(
        std::env::var("TOOLGATE_GATEWAY_CREDENTIALS")
            .map_err(|_| "TOOLGATE_GATEWAY_CREDENTIALS is required")?,
    );
    // Installed before anything else: an early SIGHUP must reload, never terminate the process.
    #[cfg(unix)]
    let mut hangup = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::hangup())
        .map_err(|_| "signal subsystem unavailable")?;
    let mut config: Config = read_json(&config_path)?;
    // One deployment-wide switch for Operator Insights (ADR 031), shared with Control.
    if let Ok(mode) = std::env::var("TOOLGATE_INSIGHTS_MODE") {
        if !mode.is_empty() {
            config.insights.mode = Some(mode);
        }
    }
    let now = unix_ms().ok_or("system clock unavailable")?;
    config.validate(&contracts, now)?;
    let auth = Authenticator::from_bytes(&read_bytes(&credentials_path)?, &contracts, now)?;
    let runtime = TcpListener::bind(config.listen)
        .await
        .map_err(|_| "runtime listener bind failed")?;
    let management = TcpListener::bind(config.management_listen)
        .await
        .map_err(|_| "management listener bind failed")?;
    let (shutdown, receiver) = watch::channel(false);
    let authority = Arc::new(olo_toolgate_gateway::relay::HttpRelay::new(
        config.control.clone(),
    )?);
    let gateway = Gateway {
        contracts,
        authority: authority.clone(),
    };
    let mut health_receiver = receiver.clone();
    let health_authority = authority.clone();
    let mut health_worker = tokio::spawn(async move {
        use olo_toolgate_gateway::relay::RelayPort;
        loop {
            let _ = health_authority.health().await;
            tokio::select! {
                _=health_receiver.changed()=>break,
                _=tokio::time::sleep(Duration::from_secs(2))=>{}
            }
        }
    });
    let state = AppState::new(gateway, auth, config.clone())?;
    let state = Arc::new(state);
    // Admission counters reach Control every 10 s; a slow or absent Control only delays them.
    let mut insights_receiver = shutdown.subscribe();
    let insights_state = state.clone();
    let insights_authority = authority.clone();
    let insights_worker = tokio::spawn(async move {
        // Spans every 2 s, admission counters every 10 s.
        let mut tick = 0u64;
        loop {
            tokio::select! {
                _=insights_receiver.changed()=>break,
                _=tokio::time::sleep(Duration::from_secs(2))=>{}
            }
            tick += 1;
            insights_state
                .spans
                .flush(insights_authority.as_ref())
                .await;
            if tick.is_multiple_of(5) {
                if let Some(now) = unix_ms() {
                    insights_state
                        .admissions
                        .flush(insights_authority.as_ref(), now, false)
                        .await;
                }
            }
        }
        insights_state
            .spans
            .flush(insights_authority.as_ref())
            .await;
        if let Some(now) = unix_ms() {
            let _ = tokio::time::timeout(
                Duration::from_secs(2),
                insights_state
                    .admissions
                    .flush(insights_authority.as_ref(), now, true),
            )
            .await;
        }
    });
    // SIGHUP: the administrator replaced the credential file atomically. Validate it whole, then swap.
    #[cfg(unix)]
    let reload_worker = {
        let state = state.clone();
        tokio::spawn(async move {
            while hangup.recv().await.is_some() {
                match state.reload_credentials(&credentials_path) {
                    Ok(count) => tracing::info!(
                        service = "gateway",
                        event = "credentials_reloaded",
                        count = count
                    ),
                    Err(reason) => tracing::warn!(
                        service = "gateway",
                        event = "credentials_reload_rejected",
                        reason = reason
                    ),
                }
            }
        })
    };
    let runtime_task = tokio::spawn(server::serve(
        runtime,
        runtime_router(state.clone()),
        config.limits.clone(),
        receiver.clone(),
    ));
    let management_task = tokio::spawn(server::serve(
        management,
        management_router(state.clone()),
        config.limits.clone(),
        receiver,
    ));
    tracing::info!(
        service = "gateway",
        version = env!("CARGO_PKG_VERSION"),
        event = "started",
        runtime_port = config.listen.port(),
        management_port = config.management_listen.port()
    );
    let mut runtime_task = runtime_task;
    let mut management_task = management_task;
    let mut failed = false;
    tokio::select! {
        signal = shutdown_signal() => { if signal.is_err() { failed = true; } },
        _ = &mut runtime_task => { failed = true; },
        _ = &mut management_task => { failed = true; },
        _ = &mut health_worker => { failed = true; },
    }
    #[cfg(unix)]
    reload_worker.abort();
    state.draining.store(true, Ordering::Release);
    let _ = shutdown.send(true);
    if !health_worker.is_finished()
        && tokio::time::timeout(
            Duration::from_millis(config.limits.shutdown_timeout_ms),
            &mut health_worker,
        )
        .await
        .is_err()
    {
        health_worker.abort();
    }
    tracing::info!(
        service = "gateway",
        version = env!("CARGO_PKG_VERSION"),
        event = "draining"
    );
    if !runtime_task.is_finished() {
        let _ = (&mut runtime_task).await;
    }
    if !management_task.is_finished() {
        let _ = (&mut management_task).await;
    }
    // The final admission batch is sent after requests stop, within its own short bound.
    let _ = tokio::time::timeout(Duration::from_secs(3), insights_worker).await;
    drop(state);
    tracing::info!(
        service = "gateway",
        version = env!("CARGO_PKG_VERSION"),
        event = "stopped"
    );
    if failed {
        Err("listener or signal subsystem failed")
    } else {
        Ok(())
    }
}

async fn shutdown_signal() -> Result<(), std::io::Error> {
    #[cfg(unix)]
    {
        let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
        tokio::select! { result = tokio::signal::ctrl_c() => result, _ = term.recv() => Ok(()) }
    }
    #[cfg(not(unix))]
    {
        tokio::signal::ctrl_c().await
    }
}
