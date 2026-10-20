// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Length-framed, bounded OS-authenticated IPC; no remote listener or credential export.
use crate::{
    connections::Connections, contracts::Contracts, power::Power, service::ClientService, Failure,
    Result,
};
use olo_toolgate_contracts::*;
use std::{sync::Arc, time::Duration};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tracing::Instrument;
pub fn authorized(peer: &str, allowed: &[String]) -> bool {
    allowed.iter().any(|entry| entry == peer)
}
async fn read<S: AsyncRead + Unpin>(stream: &mut S) -> Result<Vec<u8>> {
    let size = stream.read_u32().await.map_err(|_| Failure::Unavailable)? as usize;
    if size == 0 || size > 131072 {
        return Err(Failure::Validation);
    }
    let mut bytes = vec![0; size];
    stream
        .read_exact(&mut bytes)
        .await
        .map_err(|_| Failure::Unavailable)?;
    Ok(bytes)
}
async fn write<S: AsyncWrite + Unpin>(stream: &mut S, bytes: &[u8]) -> Result<()> {
    if bytes.len() > 131072 {
        return Err(Failure::Validation);
    }
    stream
        .write_u32(bytes.len() as u32)
        .await
        .map_err(|_| Failure::Unavailable)?;
    stream
        .write_all(bytes)
        .await
        .map_err(|_| Failure::Unavailable)?;
    stream.flush().await.map_err(|_| Failure::Unavailable)
}
fn code(failure: Failure) -> ErrorCode {
    match failure {
        Failure::Unauthorized | Failure::Revoked => ErrorCode::Forbidden,
        Failure::TenantMismatch => ErrorCode::TenantMismatch,
        Failure::EnrollmentRequired => ErrorCode::NotFound,
        Failure::Validation => ErrorCode::Validation,
        Failure::Conflict => ErrorCode::Conflict,
        Failure::Expired => ErrorCode::Timeout,
        Failure::Unsupported => ErrorCode::Unsupported,
        Failure::Unavailable => ErrorCode::DependencyUnavailable,
    }
}
pub async fn connection<S: AsyncRead + AsyncWrite + Unpin>(
    stream: &mut S,
    peer: &str,
    peers: &[String],
    service: &Arc<tokio::sync::Mutex<ClientService>>,
    contracts: &Contracts,
    activity: &Arc<crate::activity::Activity>,
) -> Result<()> {
    connection_with_gateways(
        stream, peer, peers, service, None, None, contracts, activity,
    )
    .await
}
/// Same as [`connection`], and also reports every gateway connection the service holds and
/// switches the device's power. Without a switch the power commands are unsupported.
#[allow(clippy::too_many_arguments)]
pub async fn connection_with_gateways<S: AsyncRead + AsyncWrite + Unpin>(
    stream: &mut S,
    peer: &str,
    peers: &[String],
    service: &Arc<tokio::sync::Mutex<ClientService>>,
    gateways: Option<&Connections>,
    power: Option<&Power>,
    contracts: &Contracts,
    activity: &Arc<crate::activity::Activity>,
) -> Result<()> {
    if !authorized(peer, peers) {
        activity.event("Local IPC", "UNAUTHORIZED");
        return Err(Failure::Unauthorized);
    }
    let bytes = tokio::time::timeout(Duration::from_secs(5), read(stream))
        .await
        .map_err(|_| Failure::Unavailable)??;
    handle(
        stream, peer, peers, service, gateways, power, contracts, activity, &bytes,
    )
    .await
}
// Keep authenticated peer, bounded payload and independent diagnostic state explicit.
#[allow(clippy::too_many_arguments)]
async fn handle<S: AsyncWrite + Unpin>(
    stream: &mut S,
    peer: &str,
    peers: &[String],
    service: &Arc<tokio::sync::Mutex<ClientService>>,
    gateways: Option<&Connections>,
    power: Option<&Power>,
    contracts: &Contracts,
    activity: &Arc<crate::activity::Activity>,
    bytes: &[u8],
) -> Result<()> {
    if !authorized(peer, peers) {
        activity.event("Local IPC", "UNAUTHORIZED");
        return Err(Failure::Unauthorized);
    }
    let envelope: serde_json::Value =
        serde_json::from_slice(bytes).map_err(|_| Failure::Validation)?;
    // Switched off by the owner: no tool request, runtime or gateway contact is started.
    let off = power.is_some_and(|p| !p.is_on());
    if envelope["protocolVersion"] == 3 {
        let request: LocalRuntimeIpcRequest = contracts.decode("LocalRuntimeIpcRequest", bytes)?;
        let mut response = LocalRuntimeIpcResponse {
            request_id: request.request_id.clone(),
            health: None,
            result: None,
            error: None,
        };
        if off && request.operation != LocalRuntimeOperation::Status {
            response.error = Some(code(Failure::Unavailable));
            return write(
                stream,
                &contracts.encode("LocalRuntimeIpcResponse", &response)?,
            )
            .await;
        }
        let mut state = match service.try_lock() {
            Ok(state) => state,
            Err(_) => {
                response.error = Some(ErrorCode::Conflict);
                return write(
                    stream,
                    &contracts.encode("LocalRuntimeIpcResponse", &response)?,
                )
                .await;
            }
        };
        let command = match &request.operation {
            LocalRuntimeOperation::Prepare => Some(activity.begin("Prepare runtimes")),
            LocalRuntimeOperation::Invoke => request
                .invocation
                .as_ref()
                .map(|i| activity.begin(&i.tool_id)),
            _ => None,
        };
        let result = match request.operation {
            LocalRuntimeOperation::Status if request.invocation.is_none() => {
                state.runtime_health().map(|h| response.health = Some(h))
            }
            LocalRuntimeOperation::Prepare if request.invocation.is_none() => state
                .prepare_runtimes()
                .await
                .map(|h| response.health = Some(h)),
            LocalRuntimeOperation::Invoke => match request.invocation {
                Some(input) if input.request_id == request.request_id => {
                    let span = tracing::info_span!("local_runtime",request_id=%request.request_id,tool_id=%input.tool_id);
                    state
                        .invoke_runtime(input)
                        .instrument(span)
                        .await
                        .map(|out| response.result = Some(out))
                }
                _ => Err(Failure::Validation),
            },
            _ => Err(Failure::Unauthorized),
        };
        if let Some(command) = command {
            command.finish(result.is_ok());
        }
        if let Err(e) = result {
            response.error = Some(code(e));
        }
        return write(
            stream,
            &contracts.encode("LocalRuntimeIpcResponse", &response)?,
        )
        .await;
    }
    if envelope["protocolVersion"] == 2 {
        let request: LocalToolIpcRequest = contracts.decode("LocalToolIpcRequest", bytes)?;
        let mut response = LocalToolIpcResponse {
            request_id: request.request_id,
            tools: None,
            output: None,
            error: None,
        };
        if off {
            response.error = Some(code(Failure::Unavailable));
            return write(
                stream,
                &contracts.encode("LocalToolIpcResponse", &response)?,
            )
            .await;
        }
        let mut state = match service.try_lock() {
            Ok(state) => state,
            Err(_) => {
                response.error = Some(ErrorCode::Conflict);
                return write(
                    stream,
                    &contracts.encode("LocalToolIpcResponse", &response)?,
                )
                .await;
            }
        };
        let command = if request.operation == LocalToolOperation::Call {
            request
                .invocation
                .as_ref()
                .map(|i| activity.begin(&i.tool_id))
        } else {
            None
        };
        let result = match request.operation {
            LocalToolOperation::Catalog if request.invocation.is_none() => state
                .tool_catalog(request.agent_id.as_deref())
                .await
                .map(|tools| response.tools = Some(tools)),
            LocalToolOperation::Call => match request.invocation {
                Some(invocation) => {
                    let span = tracing::info_span!("local_tool_call",request_id=%response.request_id,tool_id=%invocation.tool_id);
                    state
                        .execute_tool(invocation)
                        .instrument(span)
                        .await
                        .map(|output| response.output = Some(output))
                }
                None => Err(Failure::Validation),
            },
            _ => Err(Failure::Validation),
        };
        if let Some(command) = command {
            command.finish(result.is_ok());
        }
        if let Err(failure) = result {
            response.error = Some(code(failure));
            tracing::warn!(event="local_tool_call",result="rejected",error=?failure);
        }
        let bytes = contracts.encode("LocalToolIpcResponse", &response)?;
        drop(state);
        return write(stream, &bytes).await;
    }
    if bytes.len() > 4096 {
        return Err(Failure::Validation);
    }
    if envelope.get("kind").and_then(serde_json::Value::as_str) == Some("console-tenant-state") {
        use olo_toolgate_contracts::v2::{ConsoleClientOperation, ConsoleClientRequest};
        let request: ConsoleClientRequest = contracts.decode("ConsoleClientRequest", bytes)?;
        let mut response = ClientIpcResponse {
            request_id: request.request_id,
            health: None,
            challenge: None,
            activity: None,
            error: None,
            connections: None,
            power: None,
        };
        let mut state = service.try_lock().map_err(|_| Failure::Conflict)?;
        let result = match state
            .authorize_console(&request.console_url, request.expected_tenant_id.as_deref())
        {
            Err(failure) => Err(failure),
            Ok(()) => match request.operation {
                ConsoleClientOperation::Health => {
                    response.health = Some(state.health());
                    Ok(())
                }
                ConsoleClientOperation::Enroll if !off => state
                    .enroll()
                    .await
                    .map(|prompt| response.challenge = Some(prompt)),
                _ => Err(Failure::Unavailable),
            },
        };
        if let Err(failure) = result {
            response.error = Some(code(failure));
        }
        drop(state);
        return write(stream, &contracts.encode("ClientIpcResponse", &response)?).await;
    }
    if serde_json::from_slice::<serde_json::Value>(bytes)
        .ok()
        .is_some_and(|value| {
            value.get("kind").and_then(serde_json::Value::as_str) == Some("console-device-proof")
        })
    {
        let request: ConsoleDeviceProofRequest =
            contracts.decode("ConsoleDeviceProofRequest", bytes)?;
        let state = service.try_lock().map_err(|_| Failure::Conflict)?;
        let proof = state.console_device_proof(&request.console_url, &request.nonce)?;
        drop(state);
        return write(stream, &contracts.encode("ConsoleDeviceProof", &proof)?).await;
    }
    let request: ClientIpcRequest = contracts.decode("ClientIpcRequest", bytes)?;
    let mut response = ClientIpcResponse {
        request_id: request.request_id,
        health: None,
        challenge: None,
        activity: None,
        error: None,
        connections: None,
        power: power.map(Power::state),
    };
    // Connection snapshots are published by each heartbeat and never wait for a service lock.
    if request.operation == ClientIpcOperation::Activity {
        response.activity = Some(activity.snapshot());
        response.connections = gateways.map(|g| g.snapshot(true));
        return write(stream, &contracts.encode("ClientIpcResponse", &response)?).await;
    }
    // The owner's switch never waits behind a check-in; each gateway connection reacts to it.
    if let ClientIpcOperation::PowerOn | ClientIpcOperation::PowerOff = request.operation {
        let on = request.operation == ClientIpcOperation::PowerOn;
        match power.ok_or(Failure::Unsupported).and_then(|p| p.set(on)) {
            Ok(state) => {
                activity.event("Power", if on { "ON" } else { "OFF" });
                tracing::info!(event = "device_power", on);
                response.power = Some(state);
            }
            Err(failure) => response.error = Some(code(failure)),
        }
        return write(stream, &contracts.encode("ClientIpcResponse", &response)?).await;
    }
    if off && request.operation != ClientIpcOperation::Health {
        response.error = Some(code(Failure::Unavailable));
        return write(stream, &contracts.encode("ClientIpcResponse", &response)?).await;
    }
    // Health may wait briefly behind a poll. Listener permits bound waiting readers;
    // mutating commands still return immediate backpressure instead of queuing effects.
    let locked = if matches!(&request.operation, ClientIpcOperation::Health) {
        tokio::time::timeout(Duration::from_secs(5), service.lock())
            .await
            .ok()
    } else {
        service.try_lock().ok()
    };
    let mut state = match locked {
        Some(state) => state,
        None => {
            response.error = Some(ErrorCode::Conflict);
            return write(stream, &contracts.encode("ClientIpcResponse", &response)?).await;
        }
    };
    let command = match request.operation {
        ClientIpcOperation::Enroll => Some(activity.begin("Request device approval")),
        ClientIpcOperation::CheckIn => Some(activity.begin("Check in")),
        _ => None,
    };
    let result = match request.operation {
        ClientIpcOperation::Activity
        | ClientIpcOperation::PowerOn
        | ClientIpcOperation::PowerOff => unreachable!(),
        ClientIpcOperation::Health => {
            response.health = Some(state.health());
            response.connections = gateways.map(|g| g.snapshot(false));
            Ok(())
        }
        ClientIpcOperation::Enroll => state
            .request_approval()
            .await
            .map(|challenge| response.challenge = Some(challenge)),
        ClientIpcOperation::CheckIn => state
            .tick()
            .await
            .map(|_| response.health = Some(state.health())),
    };
    if let Some(command) = command {
        command.finish(result.is_ok());
    }
    if let Err(failure) = result {
        response.error = Some(code(failure));
    }
    if matches!(
        request.operation,
        ClientIpcOperation::Enroll | ClientIpcOperation::CheckIn
    ) {
        // Recalculate the background timer after this one-shot command; do not ping twice.
        state.schedule_changed.notify_one();
    }
    let bytes = contracts.encode("ClientIpcResponse", &response)?;
    drop(state);
    tokio::time::timeout(Duration::from_secs(5), write(stream, &bytes))
        .await
        .map_err(|_| Failure::Unavailable)?
}
#[cfg(unix)]
pub async fn listen(
    endpoint: &str,
    peers: Vec<String>,
    service: Arc<tokio::sync::Mutex<ClientService>>,
    gateways: Arc<Connections>,
    power: Arc<Power>,
    contracts: Arc<Contracts>,
    mut shutdown: tokio::sync::watch::Receiver<bool>,
) -> Result<()> {
    let activity = service.lock().await.activity.clone();
    use std::os::unix::fs::PermissionsExt;
    let path = std::path::Path::new(endpoint);
    crate::storage::check_parents(path)?;
    if path.exists() {
        use std::os::unix::fs::{FileTypeExt, MetadataExt};
        let meta = std::fs::symlink_metadata(path).map_err(|_| Failure::Unavailable)?;
        if !meta.file_type().is_socket() || meta.uid() != unsafe { libc::geteuid() } {
            return Err(Failure::Unauthorized);
        }
        // Service lease is already held, so only a stale socket from this identity is removed.
        std::fs::remove_file(path).map_err(|_| Failure::Unavailable)?;
    }
    let listener = tokio::net::UnixListener::bind(path).map_err(|_| Failure::Unavailable)?;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o666))
        .map_err(|_| Failure::Unavailable)?;
    let limit = Arc::new(tokio::sync::Semaphore::new(16));
    let mut tasks = tokio::task::JoinSet::new();
    loop {
        tokio::select! {
            _=shutdown.changed()=>break,
            incoming=listener.accept()=>{
                let(mut stream,_)=incoming.map_err(|_|Failure::Unavailable)?;let permit=match limit.clone().try_acquire_owned(){Ok(p)=>p,Err(_)=>continue};
                let peer=stream.peer_cred().map_err(|_|Failure::Unauthorized)?.uid().to_string();
                let service=service.clone();let gateways=gateways.clone();let power=power.clone();let contracts=contracts.clone();let peers=peers.clone();let activity=activity.clone();
                tasks.spawn(async move{let _permit=permit;let _=tokio::time::timeout(Duration::from_secs(230),connection_with_gateways(&mut stream,&peer,&peers,&service,Some(&gateways),Some(&power),&contracts,&activity)).await;});
            },
            Some(_)=tasks.join_next()=>{},
        }
    }
    tasks.abort_all();
    while tasks.join_next().await.is_some() {}
    drop(listener);
    std::fs::remove_file(path).map_err(|_| Failure::Unavailable)
}
#[cfg(windows)]
pub async fn listen(
    endpoint: &str,
    peers: Vec<String>,
    service: Arc<tokio::sync::Mutex<ClientService>>,
    gateways: Arc<Connections>,
    power: Arc<Power>,
    contracts: Arc<Contracts>,
    mut shutdown: tokio::sync::watch::Receiver<bool>,
) -> Result<()> {
    let activity = service.lock().await.activity.clone();
    use std::os::windows::io::AsRawHandle;
    use tokio::net::windows::named_pipe::ServerOptions;
    fn create(
        endpoint: &str,
        peers: &[String],
        first: bool,
    ) -> Result<tokio::net::windows::named_pipe::NamedPipeServer> {
        let descriptor = crate::platform::windows::Descriptor::new(peers)?;
        let mut attributes = descriptor.attributes();
        unsafe {
            ServerOptions::new()
                .first_pipe_instance(first)
                .reject_remote_clients(true)
                .max_instances(17)
                .create_with_security_attributes_raw(
                    endpoint,
                    (&mut attributes as *mut windows_sys::Win32::Security::SECURITY_ATTRIBUTES)
                        .cast(),
                )
        }
        .map_err(|_| Failure::Unauthorized)
    }
    let mut pipe = create(endpoint, &peers, true)?;
    let mut tasks = tokio::task::JoinSet::new();
    let limit = Arc::new(tokio::sync::Semaphore::new(16));
    loop {
        tokio::select! {
            _=shutdown.changed()=>break,
            connected=pipe.connect()=>{
                connected.map_err(|_|Failure::Unavailable)?;
                let next=create(endpoint,&peers,false)?;
                let mut stream=std::mem::replace(&mut pipe,next);let permit=match limit.clone().try_acquire_owned(){Ok(p)=>p,Err(_)=>continue};
                let service=service.clone();let gateways=gateways.clone();let power=power.clone();let contracts=contracts.clone();let peers=peers.clone();let activity=activity.clone();
                tasks.spawn(async move{let _permit=permit;let _=tokio::time::timeout(Duration::from_secs(230),async{
                    // Impersonation authenticates the token associated with data actually read.
                    // SAFETY: stream owns the live connected server pipe during the query.
                    let bytes=read(&mut stream).await?;let peer=unsafe { crate::platform::windows::peer_sid(stream.as_raw_handle()) }?;
                    handle(&mut stream,&peer,&peers,&service,Some(&gateways),Some(&power),&contracts,&activity,&bytes).await
                }).await;});
            },
            Some(_)=tasks.join_next()=>{},
        }
    }
    tasks.abort_all();
    while tasks.join_next().await.is_some() {}
    Ok(())
}
pub async fn call(endpoint: &str, operation: ClientIpcOperation) -> Result<ClientIpcResponse> {
    let contracts = Contracts::new()?;
    let request = ClientIpcRequest {
        protocol_version: 1,
        request_id: crate::identity::nonce()?,
        operation,
    };
    let bytes = contracts.encode("ClientIpcRequest", &request)?;
    // A bounded activity history can exceed the smaller enrollment/health budget.
    let limit = if request.operation == ClientIpcOperation::Activity {
        131072
    } else {
        16384
    };
    let bytes = exchange(endpoint, &bytes, limit).await?;
    let response: ClientIpcResponse = contracts.decode("ClientIpcResponse", &bytes)?;
    if response.request_id != request.request_id {
        return Err(Failure::Unauthorized);
    }
    Ok(response)
}
/// The service checks context under its lock before exposing health or starting enrollment.
pub async fn console_call(
    endpoint: &str,
    operation: olo_toolgate_contracts::v2::ConsoleClientOperation,
    console_url: &str,
) -> Result<ClientIpcResponse> {
    let contracts = Contracts::shared()?;
    let request = olo_toolgate_contracts::v2::ConsoleClientRequest {
        kind: "console-tenant-state".into(),
        request_id: crate::identity::nonce()?,
        operation,
        console_url: console_url.into(),
        expected_tenant_id: None,
    };
    let bytes = contracts.encode("ConsoleClientRequest", &request)?;
    let bytes = exchange(endpoint, &bytes, 16384).await?;
    let response: ClientIpcResponse = contracts.decode("ClientIpcResponse", &bytes)?;
    if response.request_id != request.request_id {
        return Err(Failure::Unauthorized);
    }
    Ok(response)
}
pub async fn console_proof(
    endpoint: &str,
    console_url: &str,
    nonce: &str,
) -> Result<ConsoleDeviceProof> {
    let contracts = Contracts::new()?;
    let request = ConsoleDeviceProofRequest {
        kind: "console-device-proof".to_owned(),
        console_url: console_url.to_owned(),
        nonce: nonce.to_owned(),
    };
    let bytes = contracts.encode("ConsoleDeviceProofRequest", &request)?;
    let response = exchange(endpoint, &bytes, 16384).await?;
    contracts.decode("ConsoleDeviceProof", &response)
}
pub async fn call_tool(
    endpoint: &str,
    invocation: Option<LocalToolCall>,
) -> Result<LocalToolIpcResponse> {
    call_tool_for_agent(endpoint, invocation, None).await
}
pub async fn call_tool_for_agent(
    endpoint: &str,
    invocation: Option<LocalToolCall>,
    agent_id: Option<String>,
) -> Result<LocalToolIpcResponse> {
    let contracts = Contracts::new()?;
    let request = LocalToolIpcRequest {
        protocol_version: 2,
        request_id: crate::identity::nonce()?,
        operation: if invocation.is_some() {
            LocalToolOperation::Call
        } else {
            LocalToolOperation::Catalog
        },
        invocation,
        agent_id,
    };
    let bytes = contracts.encode("LocalToolIpcRequest", &request)?;
    let response: LocalToolIpcResponse = contracts.decode(
        "LocalToolIpcResponse",
        &exchange(endpoint, &bytes, 131072).await?,
    )?;
    if response.request_id != request.request_id {
        return Err(Failure::Unauthorized);
    }
    Ok(response)
}
pub async fn call_runtime(
    endpoint: &str,
    operation: LocalRuntimeOperation,
    invocation: Option<LocalToolInput>,
) -> Result<LocalRuntimeIpcResponse> {
    let contracts = Contracts::new()?;
    let request = LocalRuntimeIpcRequest {
        protocol_version: 3,
        request_id: invocation
            .as_ref()
            .map(|i| i.request_id.clone())
            .unwrap_or(crate::identity::nonce()?),
        operation,
        invocation,
    };
    let bytes = contracts.encode("LocalRuntimeIpcRequest", &request)?;
    let response: LocalRuntimeIpcResponse = contracts.decode(
        "LocalRuntimeIpcResponse",
        &exchange(endpoint, &bytes, 131072).await?,
    )?;
    if response.request_id != request.request_id {
        return Err(Failure::Unauthorized);
    }
    Ok(response)
}
async fn exchange(endpoint: &str, bytes: &[u8], max: usize) -> Result<Vec<u8>> {
    #[cfg(unix)]
    let mut stream = tokio::net::UnixStream::connect(endpoint)
        .await
        .map_err(|_| Failure::Unavailable)?;
    #[cfg(unix)]
    if stream.peer_cred().map_err(|_| Failure::Unauthorized)?.uid() != 0 {
        return Err(Failure::Unauthorized);
    }
    #[cfg(windows)]
    let mut stream = tokio::net::windows::named_pipe::ClientOptions::new()
        .open(endpoint)
        .map_err(|_| Failure::Unavailable)?;
    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawHandle;
        // SAFETY: stream owns the live pipe handle throughout the synchronous query.
        unsafe { crate::platform::windows::verify_service_pipe(stream.as_raw_handle()) }?;
    }
    write(&mut stream, bytes).await?;
    let size = stream.read_u32().await.map_err(|_| Failure::Unavailable)? as usize;
    if size == 0 || size > max {
        return Err(Failure::Validation);
    }
    let mut bytes = vec![0; size];
    stream
        .read_exact(&mut bytes)
        .await
        .map_err(|_| Failure::Unavailable)?;
    Ok(bytes)
}
