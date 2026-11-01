// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Real TLS negotiation through the production destination client.
use olo_toolgate_contracts::v2::{DestinationScheme, EgressMode, ServiceDestination, TlsSettings};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use std::io::{Read, Write};
use std::sync::Arc;
use std::time::Duration;
use tool_host::egress::{client, AddressPolicy};

#[tokio::test]
async fn broker_negotiates_only_http1_when_target_prefers_http2() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let certified = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let pem = certified.cert.pem();
    let mut config = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(certified.cert.der().to_vec())],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
                certified.signing_key.serialize_der(),
            )),
        )
        .unwrap();
    // Select h2 if the broker ever starts offering it through Cargo feature unification.
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let port = listener.local_addr().unwrap().port();
    let peer = tokio::task::spawn_blocking(move || {
        let until = std::time::Instant::now() + Duration::from_secs(5);
        let socket = loop {
            match listener.accept() {
                Ok((socket, _)) => break socket,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    assert!(std::time::Instant::now() < until, "broker did not connect");
                    std::thread::sleep(Duration::from_millis(5));
                }
                Err(error) => panic!("accept: {error}"),
            }
        };
        socket
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        socket
            .set_write_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        let connection = rustls::ServerConnection::new(Arc::new(config)).unwrap();
        let mut stream = rustls::StreamOwned::new(connection, socket);
        while stream.conn.is_handshaking() {
            stream.conn.complete_io(&mut stream.sock).unwrap();
        }
        assert_eq!(stream.conn.alpn_protocol(), Some(b"http/1.1".as_slice()));
        let mut wire = Vec::new();
        let mut byte = [0u8; 1];
        while !wire.ends_with(b"\r\n\r\n") {
            assert!(wire.len() < 16384);
            stream.read_exact(&mut byte).unwrap();
            wire.push(byte[0]);
        }
        assert!(wire.starts_with(b"POST /write HTTP/1.1\r\n"));
        let mut body = [0u8; 7];
        stream.read_exact(&mut body).unwrap();
        assert_eq!(&body, b"payload");
        stream
            .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
            .unwrap();
        stream.flush().unwrap();
    });
    let destination = ServiceDestination {
        id: "test".into(),
        scheme: DestinationScheme::Https,
        host: "localhost".into(),
        port: u64::from(port),
        mode: EgressMode::Brokered,
        tls: TlsSettings {
            ca_bundle: Some(pem),
            spki_pins: vec![],
        },
    };
    let client = client(
        &destination,
        AddressPolicy {
            host: "localhost".into(),
            port,
            private_ranges: vec![],
            allow_loopback: true,
        },
    )
    .unwrap();
    let result = client
        .post(format!("https://localhost:{port}/write"))
        .body("payload")
        .timeout(Duration::from_secs(5))
        .send()
        .await
        .unwrap();
    assert_eq!(result.version(), reqwest::Version::HTTP_11);
    assert_eq!(result.text().await.unwrap(), "ok");
    peer.await.unwrap();
}
