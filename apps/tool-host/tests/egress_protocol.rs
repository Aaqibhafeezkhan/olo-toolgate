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
async fn broker_uses_fresh_http1_connections_when_target_prefers_http2() {
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
        let config = Arc::new(config);
        let mut keepalive = Vec::new();
        for _ in 0..2 {
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
            let connection = rustls::ServerConnection::new(config.clone()).unwrap();
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
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
                .unwrap();
            stream.flush().unwrap();
            // Keep the first socket alive: a pooling client would reuse it and never
            // open the second connection this test requires.
            keepalive.push(stream);
        }
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
    for _ in 0..2 {
        let result = client
            .post(format!("https://localhost:{port}/write"))
            .body("payload")
            .timeout(Duration::from_secs(5))
            .send()
            .await
            .unwrap();
        assert_eq!(result.version(), reqwest::Version::HTTP_11);
        assert_eq!(result.text().await.unwrap(), "ok");
    }
    peer.await.unwrap();
}

/// Standard chain/name/time verification and authenticated-leaf pinning must
/// both succeed before any HTTP byte reaches the target application.
#[tokio::test]
async fn certificate_validation_and_leaf_pins_reject_forged_chains_before_application_contact() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    fn certificate(host: &str, expired: bool, ca: bool) -> (rcgen::Certificate, rcgen::KeyPair) {
        let mut params = rcgen::CertificateParams::new(vec![host.into()]).unwrap();
        if expired {
            params.not_before = rcgen::date_time_ymd(2000, 1, 1);
            params.not_after = rcgen::date_time_ymd(2001, 1, 1);
        }
        if ca {
            params.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Unconstrained);
        }
        let key = rcgen::KeyPair::generate().unwrap();
        (params.self_signed(&key).unwrap(), key)
    }
    let valid = certificate("localhost", false, false);
    let other = certificate("localhost", false, false);
    let wrong_host = certificate("elsewhere.invalid", false, false);
    let expired = certificate("localhost", true, false);
    let copied_ca = certificate("public-pinned-ca.invalid", false, true);
    for (name, peer, root, pinned, extra, expected) in [
        ("valid", &valid, Some(&valid.0), &valid.0, None, true),
        (
            "unknown-issuer",
            &other,
            Some(&valid.0),
            &other.0,
            None,
            false,
        ),
        ("system-untrusted", &other, None, &other.0, None, false),
        (
            "wrong-leaf-pin",
            &valid,
            Some(&valid.0),
            &other.0,
            None,
            false,
        ),
        (
            "wrong-host",
            &wrong_host,
            Some(&wrong_host.0),
            &wrong_host.0,
            None,
            false,
        ),
        (
            "expired",
            &expired,
            Some(&expired.0),
            &expired.0,
            None,
            false,
        ),
        // Ordinary TLS succeeds, but a copied unrelated CA in the supplied
        // chain cannot satisfy a pin against the authenticated leaf.
        (
            "copied-ca",
            &other,
            Some(&other.0),
            &copied_ca.0,
            Some(&copied_ca.0),
            false,
        ),
        // The original verifier also accepted this entirely unrelated chain.
        (
            "forged-chain",
            &other,
            Some(&copied_ca.0),
            &copied_ca.0,
            Some(&copied_ca.0),
            false,
        ),
    ] {
        let mut certificates = vec![CertificateDer::from(peer.0.der().to_vec())];
        if let Some(extra) = extra {
            certificates.push(CertificateDer::from(extra.der().to_vec()));
        }
        let config = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(
                certificates,
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(peer.1.serialize_der())),
            )
            .unwrap();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = tokio::task::spawn_blocking(move || {
            let until = std::time::Instant::now() + Duration::from_secs(4);
            let socket = loop {
                match listener.accept() {
                    Ok((socket, _)) => break socket,
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        if std::time::Instant::now() >= until {
                            return 0;
                        }
                        std::thread::sleep(Duration::from_millis(5));
                    }
                    Err(e) => panic!("TLS test listener failed: {e}"),
                }
            };
            socket
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            socket
                .set_write_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let mut stream = rustls::StreamOwned::new(
                rustls::ServerConnection::new(Arc::new(config)).unwrap(),
                socket,
            );
            let mut request = Vec::new();
            let mut byte = [0u8; 1];
            loop {
                match stream.read(&mut byte) {
                    Ok(1) => request.push(byte[0]),
                    _ => return usize::from(!request.is_empty()),
                }
                if request.ends_with(b"\r\n\r\n") {
                    break;
                }
                assert!(request.len() < 16384);
            }
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
                .unwrap();
            stream.flush().unwrap();
            1
        });
        let destination = ServiceDestination {
            id: "tls-boundary".into(),
            scheme: DestinationScheme::Https,
            host: "localhost".into(),
            port: port.into(),
            mode: EgressMode::Brokered,
            tls: TlsSettings {
                ca_bundle: root.map(rcgen::Certificate::pem),
                spki_pins: vec![format!(
                    "sha256/{}",
                    tool_host::egress::spki_pin(pinned.der()).unwrap()
                )],
            },
        };
        let policy = AddressPolicy {
            host: "localhost".into(),
            port,
            private_ranges: vec![],
            allow_loopback: true,
        };
        let response = client(&destination, policy)
            .unwrap()
            .get(format!("https://localhost:{port}/probe"))
            .timeout(Duration::from_secs(3))
            .send()
            .await;
        assert_eq!(
            response.is_ok(),
            expected,
            "TLS outcome for {name}: {response:?}"
        );
        assert_eq!(
            server.await.unwrap(),
            usize::from(expected),
            "application contact for {name}"
        );
    }
}

#[tokio::test]
async fn redirects_are_observed_without_contacting_same_or_foreign_redirect_targets() {
    use axum::{routing::get, Router};
    use std::sync::atomic::{AtomicUsize, Ordering};
    let contacts = Arc::new(AtomicUsize::new(0));
    let foreign_contacts = contacts.clone();
    let foreign = Router::new().fallback(get(move || {
        let contacts = foreign_contacts.clone();
        async move {
            contacts.fetch_add(1, Ordering::SeqCst);
            "foreign"
        }
    }));
    let foreign_listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let foreign_address = foreign_listener.local_addr().unwrap();
    let foreign_server =
        tokio::spawn(async move { axum::serve(foreign_listener, foreign).await.unwrap() });
    let same_contacts = contacts.clone();
    let origin = Router::new()
        .route(
            "/cross",
            get(move || async move {
                (
                    axum::http::StatusCode::FOUND,
                    [(
                        axum::http::header::LOCATION,
                        format!("http://{foreign_address}/foreign"),
                    )],
                )
            }),
        )
        .route(
            "/same",
            get(|| async {
                (
                    axum::http::StatusCode::TEMPORARY_REDIRECT,
                    [(axum::http::header::LOCATION, "/approved")],
                )
            }),
        )
        .route(
            "/approved",
            get(move || {
                let contacts = same_contacts.clone();
                async move {
                    contacts.fetch_add(1, Ordering::SeqCst);
                    "approved"
                }
            }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let origin_server = tokio::spawn(async move { axum::serve(listener, origin).await.unwrap() });
    let destination = ServiceDestination {
        id: "redirects".into(),
        scheme: DestinationScheme::Https,
        host: "127.0.0.1".into(),
        port: address.port().into(),
        mode: EgressMode::Brokered,
        tls: TlsSettings {
            ca_bundle: None,
            spki_pins: vec![],
        },
    };
    let policy = AddressPolicy {
        host: destination.host.clone(),
        port: address.port(),
        private_ranges: vec![],
        allow_loopback: true,
    };
    let client = client(&destination, policy).unwrap();
    // Explicit development-loopback HTTP fixture; production TLS is tested above.
    assert!(client
        .get(format!("http://{address}/approved"))
        .send()
        .await
        .unwrap()
        .status()
        .is_success());
    contacts.store(0, Ordering::SeqCst);
    assert_eq!(
        client
            .get(format!("http://{address}/cross"))
            .send()
            .await
            .unwrap()
            .status(),
        302
    );
    assert_eq!(
        client
            .get(format!("http://{address}/same"))
            .send()
            .await
            .unwrap()
            .status(),
        307
    );
    assert_eq!(contacts.load(Ordering::SeqCst), 0);
    foreign_server.abort();
    origin_server.abort();
}

#[tokio::test]
async fn hostname_and_literal_address_refusals_contact_no_target() {
    use std::sync::atomic::{AtomicUsize, Ordering};
    let contacts = Arc::new(AtomicUsize::new(0));
    let observed = contacts.clone();
    let app = axum::Router::new().fallback(axum::routing::get(move || {
        let observed = observed.clone();
        async move {
            observed.fetch_add(1, Ordering::SeqCst);
            "reachable"
        }
    }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let mut destination = ServiceDestination {
        id: "dns-refusal".into(),
        scheme: DestinationScheme::Https,
        host: "localhost".into(),
        port: address.port().into(),
        mode: EgressMode::Brokered,
        tls: TlsSettings {
            ca_bundle: None,
            spki_pins: vec![],
        },
    };
    let policy = AddressPolicy {
        host: destination.host.clone(),
        port: address.port(),
        private_ranges: vec![],
        allow_loopback: false,
    };
    let denied = client(&destination, policy.clone()).unwrap();
    assert!(denied
        .get(format!("http://localhost:{}/probe", address.port()))
        .timeout(Duration::from_secs(2))
        .send()
        .await
        .is_err());
    destination.host = "127.0.0.1".into();
    assert!(client(
        &destination,
        AddressPolicy {
            host: destination.host.clone(),
            ..policy.clone()
        }
    )
    .is_err());
    destination.host = "::ffff:169.254.169.254".into();
    assert!(client(
        &destination,
        AddressPolicy {
            host: destination.host.clone(),
            ..policy.clone()
        }
    )
    .is_err());
    assert_eq!(contacts.load(Ordering::SeqCst), 0);
    destination.host = "localhost".into();
    let permitted = client(
        &destination,
        AddressPolicy {
            allow_loopback: true,
            ..policy
        },
    )
    .unwrap();
    assert!(permitted
        .get(format!("http://localhost:{}/probe", address.port()))
        .send()
        .await
        .unwrap()
        .status()
        .is_success());
    assert_eq!(contacts.load(Ordering::SeqCst), 1);
    server.abort();
}
