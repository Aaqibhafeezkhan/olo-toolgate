// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! One-shot Chrome host. Setup accepts a console URL, never credentials or arbitrary commands.
use std::io::{Read, Write};

#[derive(serde::Deserialize, Debug, PartialEq, Eq, Clone, Copy)]
#[serde(rename_all = "lowercase")]
enum Operation {
    Health,
    Enroll,
    Connect,
    Proof,
}
#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Request {
    operation: Operation,
    console_url: Option<String>,
    nonce: Option<String>,
}
fn read_frame(input: &mut impl Read) -> std::io::Result<Request> {
    let mut size = [0; 4];
    input.read_exact(&mut size)?;
    let size = u32::from_le_bytes(size) as usize;
    if size == 0 || size > 1024 {
        return Err(std::io::ErrorKind::InvalidData.into());
    }
    let mut bytes = vec![0; size];
    input.read_exact(&mut bytes)?;
    let request: Request =
        serde_json::from_slice(&bytes).map_err(|_| std::io::ErrorKind::InvalidData)?;
    if matches!(request.operation, Operation::Connect | Operation::Proof)
        != request.console_url.is_some()
        || (request.operation == Operation::Proof) != request.nonce.is_some()
    {
        return Err(std::io::ErrorKind::InvalidData.into());
    }
    Ok(request)
}
fn main() {
    let origin = std::env::args().nth(1).unwrap_or_default();
    if origin != "chrome-extension://emmemldedebhbloibichmmdlbpjakfkf/" {
        std::process::exit(1);
    }
    let Ok(request) = read_frame(&mut std::io::stdin().lock()) else {
        std::process::exit(1);
    };
    if request.operation == Operation::Proof {
        let response = olo_toolgate_client::runtime::executor().and_then(|runtime| {
            runtime.block_on(async {
                tokio::time::timeout(
                    std::time::Duration::from_secs(20),
                    olo_toolgate_client::ipc::console_proof(
                        &olo_toolgate_client::install::ipc_endpoint(),
                        request.console_url.as_deref().unwrap_or(""),
                        request.nonce.as_deref().unwrap_or(""),
                    ),
                )
                .await
                .map_err(|_| olo_toolgate_client::Failure::Expired)?
            })
        });
        write_frame(match response {
            Ok(proof) => serde_json::json!({"proof":proof}),
            Err(_) => serde_json::json!({"error":"UNAVAILABLE"}),
        });
        return;
    }
    let connecting = request.operation == Operation::Connect;
    let operation = match request.operation {
        Operation::Health => olo_toolgate_contracts::ClientIpcOperation::Health,
        Operation::Enroll => olo_toolgate_contracts::ClientIpcOperation::Enroll,
        Operation::Connect => olo_toolgate_contracts::ClientIpcOperation::Health,
        Operation::Proof => unreachable!(),
    };
    let mut server_url = None;
    let response = olo_toolgate_client::runtime::executor().and_then(|runtime| {
        runtime.block_on(async {
            if let Some(url) = request.console_url {
                server_url = Some(olo_toolgate_client::browser::connect(&url).await?);
            }
            tokio::time::timeout(std::time::Duration::from_secs(20), async {
                loop {
                    let response = olo_toolgate_client::ipc::call(
                        &olo_toolgate_client::install::ipc_endpoint(),
                        operation.clone(),
                    )
                    .await;
                    if !connecting || response.as_ref().is_ok_and(|value| value.error.is_none()) {
                        return response;
                    }
                    tokio::time::sleep(std::time::Duration::from_millis(200)).await;
                }
            })
            .await
            .map_err(|_| olo_toolgate_client::Failure::Expired)?
        })
    });
    let mut value = match response {
        Ok(value) => {
            serde_json::to_value(value).unwrap_or(serde_json::json!({"error":"UNAVAILABLE"}))
        }
        Err(_) => serde_json::json!({"error":"UNAVAILABLE"}),
    };
    if server_url.is_none() {
        server_url =
            olo_toolgate_client::config::Config::load(&olo_toolgate_client::install::config_path())
                .ok()
                .map(|settings| settings.server_url);
    }
    if let Some(server) = server_url {
        value["serverUrl"] = serde_json::json!(server);
    }
    write_frame(value);
}
fn write_frame(value: serde_json::Value) {
    let bytes = serde_json::to_vec(&value).unwrap_or_default();
    if bytes.is_empty() || bytes.len() > 16384 {
        std::process::exit(1);
    }
    let mut output = std::io::stdout().lock();
    if output
        .write_all(&(bytes.len() as u32).to_le_bytes())
        .is_err()
        || output.write_all(&bytes).is_err()
        || output.flush().is_err()
    {
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounded_little_endian_protocol() {
        let body = br#"{"operation":"health"}"#;
        let mut bytes = (body.len() as u32).to_le_bytes().to_vec();
        bytes.extend(body);
        assert_eq!(
            read_frame(&mut bytes.as_slice()).unwrap().operation,
            Operation::Health
        );
        for invalid in [0u32, 1025, u32::MAX] {
            assert!(read_frame(&mut invalid.to_le_bytes().as_slice()).is_err());
        }
        assert!(read_frame(&mut [3, 0, 0, 0, 255, 255, 255].as_slice()).is_err());
        for invalid in [
            r#"{"operation":"health","operation":"enroll"}"#,
            r#"{"operation":"execute"}"#,
            r#"{"operation":"health","server":"https://evil.example"}"#,
            r#"{"operation":"connect"}"#,
            r#"{"operation":"health","consoleUrl":"https://gate.example/console/"}"#,
            r#"{"operation":"connect","consoleUrl":"https://gate.example/console/","credentials":"secret"}"#,
        ] {
            let mut bytes = (invalid.len() as u32).to_le_bytes().to_vec();
            bytes.extend(invalid.as_bytes());
            assert!(read_frame(&mut bytes.as_slice()).is_err());
        }
        let body = br#"{"operation":"connect","consoleUrl":"https://gate.example/console/"}"#;
        let mut bytes = (body.len() as u32).to_le_bytes().to_vec();
        bytes.extend(body);
        assert_eq!(
            read_frame(&mut bytes.as_slice()).unwrap().operation,
            Operation::Connect
        );
    }
}
