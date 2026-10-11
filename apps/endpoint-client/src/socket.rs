// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! One bounded mTLS job channel. Losing it falls back to the durable HTTP protocol.
use crate::{contracts::Contracts, diagnostics::PacketLog, Failure, Result};
use futures_util::{SinkExt, StreamExt};
use olo_toolgate_contracts::{ClientSocketOperation, ClientSocketReply, ClientSocketRequest};
use std::{
    collections::BTreeMap,
    time::{Duration, Instant},
};
use tokio_tungstenite::{
    tungstenite::{
        handshake::{client::generate_key, derive_accept_key},
        protocol::{Role, WebSocketConfig},
        Message,
    },
    WebSocketStream,
};
pub const PATH: &str = "/api/control/v1/endpoint/socket";
const IDLE: Duration = Duration::from_secs(30);
const PING: Duration = Duration::from_secs(5);
const PONG_TIMEOUT: Duration = Duration::from_secs(10);
#[derive(Default)]
struct State {
    wire: Option<WebSocketStream<reqwest::Upgraded>>,
    jobs: BTreeMap<String, u64>,
    idle: Option<Instant>,
    attempt: Option<Instant>,
    ping: Option<(Instant, Vec<u8>)>,
    last_ping: Option<Instant>,
    expires: u64,
}
#[cfg(test)]
mod tests {
    use super::*;
    async fn fixture(wrong_id: bool, dropped: bool) -> (String, tokio::task::JoinHandle<()>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let origin = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut wire = tokio_tungstenite::accept_async(stream).await.unwrap();
            wire.send(Message::Ping(b"server-heartbeat".to_vec().into()))
                .await
                .unwrap();
            let mut peer_pong = false;
            loop {
                match wire.next().await.unwrap().unwrap() {
                    Message::Ping(_) => wire.flush().await.unwrap(),
                    Message::Pong(payload) => {
                        assert_eq!(payload.as_ref(), b"server-heartbeat");
                        peer_pong = true;
                    }
                    Message::Text(text) => {
                        if dropped {
                            wire.close(None).await.unwrap();
                            break;
                        }
                        let request: ClientSocketRequest = serde_json::from_str(&text).unwrap();
                        let fixtures: serde_json::Value = serde_json::from_str(include_str!(
                            "../../../tests/fixtures/contracts/v1/valid.json"
                        ))
                        .unwrap();
                        let reply = serde_json::json!({"requestId":if wrong_id { "f".repeat(32) } else { request.request_id }, "status":200, "body":fixtures["EndpointCheckInAck"]});
                        wire.send(Message::Text(reply.to_string().into()))
                            .await
                            .unwrap();
                        if wrong_id {
                            break;
                        }
                    }
                    Message::Close(_) => {
                        assert!(peer_pong);
                        let _ = wire.flush().await;
                        break;
                    }
                    _ => panic!("Unexpected frame"),
                }
            }
        });
        (origin, task)
    }
    #[tokio::test]
    async fn channel_correlates_replies_answers_ping_closes_after_idle_and_falls_back() {
        for (wrong_id, dropped) in [(false, false), (true, false), (false, true)] {
            let (origin, server) = fixture(wrong_id, dropped).await;
            #[cfg(windows)]
            let base = std::path::PathBuf::from(std::env::var_os("ProgramData").unwrap());
            #[cfg(target_os = "macos")]
            let base = std::path::PathBuf::from("/private/tmp");
            #[cfg(all(unix, not(target_os = "macos")))]
            let base = std::env::temp_dir();
            let directory = base.join(format!(
                "toolgate-socket-{}",
                crate::identity::nonce().unwrap()
            ));
            let packets = PacketLog::open(directory.clone()).unwrap();
            let contracts = Contracts::new().unwrap();
            let client = reqwest::Client::builder().no_proxy().build().unwrap();
            let channel = Channel::default();
            channel
                .ensure(
                    &client,
                    &origin,
                    crate::now() + 60000,
                    Some(("job".into(), crate::now() + 60000)),
                    &packets,
                )
                .await;
            let response: Option<Result<olo_toolgate_contracts::EndpointCheckInAck>> = channel
                .call(
                    ClientSocketOperation::CheckIn,
                    Some(b"{}"),
                    "EndpointCheckInAck",
                    &contracts,
                    &packets,
                )
                .await;
            if dropped {
                assert!(response.is_none());
                assert!(channel.state.lock().await.wire.is_none());
                assert!(channel.state.lock().await.jobs.contains_key("job"));
            } else if wrong_id {
                assert_eq!(response, Some(Err(Failure::Validation)));
            } else {
                assert!(response.unwrap().is_ok());
                assert!(channel.state.lock().await.ping.is_none());
                channel.finished("job").await;
                channel.state.lock().await.idle = Some(Instant::now() - IDLE);
                channel
                    .ensure(&client, &origin, crate::now() + 60000, None, &packets)
                    .await;
                assert!(channel.state.lock().await.wire.is_none());
            }
            tokio::time::timeout(Duration::from_secs(3), server)
                .await
                .unwrap()
                .unwrap();
            drop(packets);
            std::fs::remove_dir_all(directory).unwrap();
        }
    }
}
#[derive(Default)]
pub struct Channel {
    state: tokio::sync::Mutex<State>,
}
fn event(packets: &PacketLog, state: &str) {
    packets.record(
        "RECEIVE",
        PATH,
        "socket",
        (None, None),
        0,
        serde_json::json!({"state":state}),
    );
}
impl Channel {
    pub async fn ensure(
        &self,
        client: &reqwest::Client,
        origin: &str,
        expires: u64,
        job: Option<(String, u64)>,
        packets: &PacketLog,
    ) {
        let mut state = self.state.lock().await;
        if let Some((id, expiry)) = job {
            state.jobs.insert(id, expiry);
            state.idle = None;
        }
        state.jobs.retain(|_, expires| *expires > crate::now());
        if state.jobs.is_empty() && state.idle.is_none() {
            state.idle = Some(Instant::now());
        }
        if state.wire.is_some()
            && (state.expires <= crate::now()
                || state.idle.is_some_and(|idle| idle.elapsed() >= IDLE))
        {
            if let Some(mut wire) = state.wire.take() {
                let _ = tokio::time::timeout(Duration::from_secs(1), wire.close(None)).await;
            }
            event(packets, "IDLE_DISCONNECTED");
        }
        if state.wire.is_some()
            || state.jobs.is_empty()
            || state
                .attempt
                .is_some_and(|attempt| attempt.elapsed() < PING)
        {
            return;
        }
        state.attempt = Some(Instant::now());
        let connected = async {
            let key = generate_key();
            let mut request = client
                .get(format!("{origin}{PATH}"))
                .header("Connection", "Upgrade")
                .header("Upgrade", "websocket")
                .header("Sec-WebSocket-Version", "13")
                .header("Sec-WebSocket-Key", &key);
            if let Some(name) = crate::platform::system_name() {
                request = request.header("X-ToolGate-System-Name", name);
            }
            let response = request.send().await.map_err(|_| Failure::Unavailable)?;
            if response.status().as_u16() != 101
                || response
                    .headers()
                    .get("Sec-WebSocket-Accept")
                    .and_then(|v| v.to_str().ok())
                    != Some(derive_accept_key(key.as_bytes()).as_str())
            {
                return Err(Failure::Unavailable);
            }
            let upgraded = response.upgrade().await.map_err(|_| Failure::Unavailable)?;
            Ok(WebSocketStream::from_raw_socket(
                upgraded,
                Role::Client,
                Some(
                    WebSocketConfig::default()
                        .write_buffer_size(0)
                        .max_write_buffer_size(262144)
                        .max_message_size(Some(131072))
                        .max_frame_size(Some(131072)),
                ),
            )
            .await)
        }
        .await;
        match connected {
            Ok(wire) => {
                state.wire = Some(wire);
                state.expires = expires;
                state.ping = None;
                state.last_ping = None;
                event(packets, "CONNECTED");
            }
            Err(_) => event(packets, "HTTP_FALLBACK"),
        }
    }
    pub async fn finished(&self, id: &str) {
        let mut state = self.state.lock().await;
        state.jobs.remove(id);
        if state.jobs.is_empty() {
            state.idle = Some(Instant::now());
        }
    }
    pub async fn call<T: serde::de::DeserializeOwned>(
        &self,
        operation: ClientSocketOperation,
        body: Option<&[u8]>,
        model: &str,
        contracts: &Contracts,
        packets: &PacketLog,
    ) -> Option<Result<T>> {
        let mut state = self.state.lock().await;
        let mut wire = state.wire.take()?;
        let exchange = tokio::time::timeout(Duration::from_secs(15), async {
            if state.ping.as_ref().is_some_and(|(sent, _)| sent.elapsed() >= PONG_TIMEOUT) { return Err(Failure::Unavailable); }
            if state.ping.is_none() && state.last_ping.is_none_or(|sent| sent.elapsed() >= PING) {
                let nonce = crate::identity::nonce()?.into_bytes();
                wire.send(Message::Ping(nonce.clone().into())).await.map_err(|_| Failure::Unavailable)?;
                state.ping = Some((Instant::now(), nonce));state.last_ping = Some(Instant::now());event(packets, "PING");
            }
            let id = crate::identity::nonce()?;
            let body = body.map(serde_json::from_slice).transpose().map_err(|_| Failure::Validation)?;
            let request = ClientSocketRequest { request_id: id.clone(), operation, body };
            let bytes = contracts.encode("ClientSocketRequest", &request)?;
            let summary = request.body.as_ref().map(|body| crate::diagnostics::summary(&serde_json::json!(body))).unwrap_or_default();
            packets.record("SEND", PATH, &id, (None, None), bytes.len(), serde_json::json!({"state":"REQUEST", "operation":request.operation,"body":summary}));
            wire.send(Message::Text(String::from_utf8(bytes).map_err(|_| Failure::Validation)?.into())).await.map_err(|_| Failure::Unavailable)?;
            let deadline = tokio::time::Instant::now() + Duration::from_secs(15);
            loop {
                let message = tokio::time::timeout_at(deadline, wire.next()).await.map_err(|_| Failure::Unavailable)?
                    .ok_or(Failure::Unavailable)?.map_err(|_| Failure::Unavailable)?;
                match message {
                    Message::Pong(payload) => {
                        if state.ping.as_ref().is_some_and(|(_, nonce)| nonce.as_slice() == payload.as_ref()) { state.ping = None;event(packets, "PONG"); }
                    }
                    Message::Ping(_) => { wire.flush().await.map_err(|_| Failure::Unavailable)?;event(packets, "PEER_PING"); }
                    Message::Text(text) => {
                        let reply: ClientSocketReply = contracts.decode("ClientSocketReply", text.as_bytes())?;
                        if reply.request_id != id { return Err(Failure::Validation); }
                        let bytes = serde_json::to_vec(&reply.body).map_err(|_| Failure::Validation)?;
                        packets.record("RECEIVE", PATH, &id, (Some(reply.status as u16), Some(&id)), bytes.len(), serde_json::json!({"operation":request.operation,"body":crate::diagnostics::summary(&serde_json::json!(reply.body))}));
                        return match reply.status { 200 => contracts.decode(model, &bytes), 401 | 403 => Err(Failure::Revoked), 409 => Err(Failure::Conflict), 410 => Err(Failure::EnrollmentRequired), _ => Err(Failure::Unavailable) };
                    }
                    _ => return Err(Failure::Unavailable),
                }
            }
        }).await.unwrap_or(Err(Failure::Unavailable));
        if matches!(&exchange, Err(Failure::Unavailable)) {
            event(packets, "HTTP_FALLBACK");
            None
        } else {
            if exchange.is_ok() {
                state.wire = Some(wire);
            }
            Some(exchange)
        }
    }
}
