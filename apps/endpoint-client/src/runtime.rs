// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Protected service lifecycle, bounded IPC and one serialized heartbeat loop per gateway.
use crate::{
    config::Config,
    connections::{Connection, Connections},
    contracts::Contracts,
    identity::DeviceKey,
    service::ClientService,
    storage::ProtectedStore,
    transport::HttpsControl,
    Failure, Result,
};
use std::sync::Arc;
/// Fixed worker limits keep system services within their OS task/memory budget,
/// including machines with many logical CPUs. No environment-driven thread explosion.
pub fn executor() -> Result<tokio::runtime::Runtime> {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .max_blocking_threads(8)
        .enable_all()
        .build()
        .map_err(|_| Failure::Unavailable)
}
pub async fn run(config: Config, shutdown: tokio::sync::watch::Receiver<bool>) -> Result<()> {
    crate::platform::require_service_identity()?;
    let store = ProtectedStore::open(config.state_directory.clone())?;
    let key = Arc::new(DeviceKey::load_or_create(&store)?);
    let contracts = Arc::new(Contracts::new()?);
    let control = Arc::new(HttpsControl::new(
        config.clone(),
        key.clone(),
        contracts.clone(),
    )?);
    let (primary_healed, healing) = healed(&config, &config.state_directory);
    let primary = config.clone();
    let service = match ClientService::open(primary_healed, store, key.clone(), control) {
        Ok(service) => service,
        // Never let self-healing stop a service that would otherwise start.
        Err(failure) if healing.is_some() => {
            tracing::warn!(event="local_tools",result="ignored",error=?failure);
            let store = ProtectedStore::open(primary.state_directory.clone())?;
            let control = Arc::new(HttpsControl::new(
                primary.clone(),
                key.clone(),
                contracts.clone(),
            )?);
            ClientService::open(primary, store, key.clone(), control)?
        }
        Err(failure) => return Err(failure),
    };
    // One owner switch for the focused gateway and every background connection.
    let power = crate::power::Power::open(config.state_directory.clone())?;
    let service = Arc::new(tokio::sync::Mutex::new(service.with_power(power.clone())));
    let activity = service.lock().await.activity.clone();
    if !power.is_on() {
        activity.event("Power", "OFF");
    }
    if let Some(summary) = healing {
        activity.event("Tools", &summary);
    }
    let connections = Arc::new(Connections::default());
    let focused = Connection::new(&*service.lock().await, true);
    connections.add(focused.clone());
    let mut heartbeats = vec![heartbeat(service.clone(), focused, shutdown.clone())];
    // Gateways the device switched away from stay connected; one failing never blocks the others.
    for background in crate::connections::background(&config) {
        let server = background.server_url.clone();
        let open = |background: Config| {
            let store = ProtectedStore::open(background.state_directory.clone())?;
            let control = Arc::new(HttpsControl::new(
                background.clone(),
                key.clone(),
                contracts.clone(),
            )?);
            ClientService::open(background, store, key.clone(), control)
                .map(|state| state.with_power(power.clone()))
        };
        let (background_healed, healing) = healed(&background, &config.state_directory);
        let opened = match open(background_healed) {
            Err(failure) if healing.is_some() => {
                tracing::warn!(event="local_tools",server=%server,result="ignored",error=?failure);
                open(background)
            }
            opened => opened,
        };
        match opened {
            Ok(state) => {
                let connection = Connection::new(&state, false);
                connections.add(connection.clone());
                heartbeats.push(heartbeat(
                    Arc::new(tokio::sync::Mutex::new(state)),
                    connection,
                    shutdown.clone(),
                ));
            }
            Err(failure) => {
                tracing::warn!(event="background_gateway",server=%server,error=?failure);
                activity.event("Background gateway", &format!("{:?}", failure));
            }
        }
    }
    tracing::info!(
        event = "client_service",
        result = "started",
        version = env!("CARGO_PKG_VERSION"),
        gateways = heartbeats.len()
    );
    let result = crate::ipc::listen(
        &config.ipc_endpoint,
        config.authorized_peers,
        service.clone(),
        connections,
        power,
        contracts,
        shutdown,
    )
    .await;
    for heartbeat in &heartbeats {
        heartbeat.abort();
    }
    for heartbeat in heartbeats {
        heartbeat.await.map(|_| ()).or_else(|e| {
            if e.is_cancelled() {
                Ok(())
            } else {
                Err(Failure::Unavailable)
            }
        })?;
    }
    tracing::info!(event = "client_service", result = "stopped");
    let cleanup = service.lock().await.shutdown_runtimes().await;
    if let Err(failure) = cleanup {
        tracing::warn!(event="runtime_shutdown",error=?failure);
    }
    activity.event("Service", "STOPPED");
    result.and(cleanup)
}
/// The configuration with the device's built-in tool selection healed, and a summary when
/// anything changed. A healing failure leaves the configuration as it was.
fn healed(config: &Config, device_root: &std::path::Path) -> (Config, Option<String>) {
    let mut healed = config.clone();
    match crate::local_tools::heal(&mut healed, device_root) {
        Ok(result) if result.restored || result.refreshed > 0 => {
            tracing::info!(event="local_tools",server=%config.server_url,restored=result.restored,refreshed=result.refreshed);
            let summary = if result.restored {
                format!(
                    "Restored the built-in tool selection for {}",
                    config.server_url
                )
            } else {
                format!(
                    "Refreshed {} built-in tool profiles for this client build",
                    result.refreshed
                )
            };
            (healed, Some(summary))
        }
        Ok(_) => (healed, None),
        Err(failure) => {
            tracing::warn!(event="local_tools",result="unavailable",error=?failure);
            (config.clone(), None)
        }
    }
}
/// One serialized check-in loop per gateway connection. While the owner's switch is off the
/// loop sends one final report and then stays silent until the owner switches it on.
fn heartbeat(
    service: Arc<tokio::sync::Mutex<ClientService>>,
    connection: Arc<Connection>,
    mut shutdown: tokio::sync::watch::Receiver<bool>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let (activity, schedule_changed, mut power) = {
            let state = service.lock().await;
            (
                state.activity.clone(),
                state.schedule_changed.clone(),
                state.power.subscribe(),
            )
        };
        let mut last_health = None;
        let mut next = tokio::time::Instant::now();
        // The switch is applied at start and again, at once, every time the owner flips it.
        let mut switched = true;
        loop {
            if !switched {
                let on = *power.borrow();
                tokio::select! {
                    _=shutdown.changed()=>break,
                    changed=power.changed()=>{
                        if changed.is_err() { break; }
                        switched = true;
                    },
                    _=schedule_changed.notified()=>{
                        let state = service.lock().await;
                        connection.publish(&state);
                        let finished = tokio::time::Instant::now();
                        next = heartbeat_deadline(finished, finished, state.next_delay_millis(), state.is_revoked());
                        continue;
                    },
                    _=tokio::time::sleep_until(next), if on=>{}
                }
            }
            if std::mem::take(&mut switched) && !*power.borrow_and_update() {
                let mut state = service.lock().await;
                if let Err(failure) = state.power_off().await {
                    activity.event("Power off report", &format!("{:?}", failure));
                }
                connection.publish(&state);
                continue;
            }
            let started = tokio::time::Instant::now();
            let (delay, revoked) = {
                let mut state = service.lock().await;
                let result = state.tick().await;
                let health = state.health();
                let current = format!("{:?}-{}", health.state, health.ready);
                if last_health.as_ref() != Some(&current) {
                    activity.event("Connection", &current);
                    last_health = Some(current);
                }
                if let Err(failure) = result {
                    activity.event("Check in", &format!("{:?}", failure));
                }
                connection.publish(&state);
                (state.next_delay_millis(), state.is_revoked())
            };
            next = heartbeat_deadline(started, tokio::time::Instant::now(), delay, revoked);
        }
    })
}
fn heartbeat_deadline(
    started: tokio::time::Instant,
    finished: tokio::time::Instant,
    delay: u64,
    revoked: bool,
) -> tokio::time::Instant {
    // Revoked delay is measured from receipt time to the next local hour.
    // Other intervals include network time; never overlap or catch up requests.
    let anchor = if revoked { finished } else { started };
    (anchor + std::time::Duration::from_millis(delay)).max(finished)
}
#[cfg(windows)]
pub mod windows {
    use super::*;
    use windows_service::{
        define_windows_service,
        service::*,
        service_control_handler::{self, ServiceControlHandlerResult},
        service_dispatcher,
    };
    define_windows_service!(ffi_service, entry);
    pub fn dispatch() -> Result<()> {
        service_dispatcher::start("OloToolGateClient", ffi_service)
            .map_err(|_| Failure::Unavailable)
    }
    fn entry(_arguments: Vec<std::ffi::OsString>) {
        let (tx, rx) = tokio::sync::watch::channel(false);
        let Ok(handle) =
            service_control_handler::register("OloToolGateClient", move |event| match event {
                ServiceControl::Stop | ServiceControl::Shutdown => {
                    let _ = tx.send(true);
                    ServiceControlHandlerResult::NoError
                }
                ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
                _ => ServiceControlHandlerResult::NotImplemented,
            })
        else {
            return;
        };
        let status = |state, code| ServiceStatus {
            service_type: ServiceType::OWN_PROCESS,
            current_state: state,
            controls_accepted: ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
            exit_code: ServiceExitCode::Win32(code),
            checkpoint: 0,
            wait_hint: std::time::Duration::default(),
            process_id: None,
        };
        let outcome = (|| {
            let config = Config::load(&crate::install::config_path())?;
            handle
                .set_service_status(status(ServiceState::Running, 0))
                .map_err(|_| Failure::Unavailable)?;
            executor()?.block_on(run(config, rx))
        })();
        let _ = handle.set_service_status(status(
            ServiceState::Stopped,
            if outcome.is_ok() { 0 } else { 1 },
        ));
    }
}

#[cfg(test)]
mod schedule_tests {
    #[test]
    fn network_latency_does_not_move_the_revoked_hour_boundary() {
        let started = tokio::time::Instant::now();
        let finished = started + std::time::Duration::from_secs(2);
        let next = super::heartbeat_deadline(started, finished, 3598000, true);
        assert_eq!(next, started + std::time::Duration::from_secs(3600));
        assert_eq!(
            super::heartbeat_deadline(started, finished, 5000, false),
            started + std::time::Duration::from_secs(5)
        );
        assert_eq!(
            super::heartbeat_deadline(started, finished, 500, false),
            finished
        );
    }
}
