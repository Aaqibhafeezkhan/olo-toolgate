// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
use olo_toolgate_gateway::relay::{HttpRelay, RelayConfig, RelayPort};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
#[tokio::test]
async fn readiness_uses_authenticated_runtime_route_without_relying_on_management_port() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let path = std::env::temp_dir().join(format!(
        "toolgate-health-{}",
        olo_toolgate_gateway::unix_ms().unwrap()
    ));
    std::fs::write(&path, "health-fixture-token").unwrap();
    let server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let mut bytes = [0u8; 8192];
        let size = stream.read(&mut bytes).await.unwrap();
        let request = String::from_utf8_lossy(&bytes[..size]);
        assert!(request.starts_with("GET /api/control/v1/access/health HTTP/1.1"));
        assert!(request
            .to_ascii_lowercase()
            .contains("toolgate-tenant-id: 10000000-0000-4000-8000-000000000001"));
        assert!(request
            .to_ascii_lowercase()
            .contains("authorization: bearer health-fixture-token"));
        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 14\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{\"ready\":true}").await.unwrap();
    });
    let relay = HttpRelay::new(RelayConfig {
        ca_certificate_path: None,
        tenant_id: "10000000-0000-4000-8000-000000000001".into(),
        url: format!("http://{address}"),
        token_path: path.clone(),
        development_loopback_http: true,
    })
    .unwrap();
    assert!(!relay.ready(olo_toolgate_gateway::unix_ms().unwrap()));
    relay.health().await.unwrap();
    assert!(relay.ready(olo_toolgate_gateway::unix_ms().unwrap()));
    server.await.unwrap();
    std::fs::remove_file(path).unwrap();
}
