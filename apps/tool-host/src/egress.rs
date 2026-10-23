// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The broker's upstream connections (platform-requirements.md §11.6): names are resolved here and every
//! resolved address is checked at connect time, and TLS is verified against the system roots,
//! the destination's CA bundle, or its SPKI pins.

use olo_toolgate_contracts::v2::{PrivateRangeApproval, ServiceDestination};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

/// A resolved address the destination may not use; the call is refused, not sent.
#[derive(Debug)]
pub struct AddressRefused(pub String);

impl std::fmt::Display for AddressRefused {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for AddressRefused {}

/// What an address is, for the connect-time check.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AddressClass {
    Public,
    /// RFC 1918, CGNAT and unique-local: allowed only by an approved private range.
    Private,
    Loopback,
    /// Link-local (including cloud metadata), unspecified, multicast, broadcast and reserved: never.
    Forbidden,
}

pub fn classify(ip: IpAddr) -> AddressClass {
    match ip {
        IpAddr::V4(v4) => {
            let [a, b, ..] = v4.octets();
            if v4.is_loopback() {
                AddressClass::Loopback
            } else if v4.is_link_local()
                || v4.is_unspecified()
                || v4.is_multicast()
                || v4.is_broadcast()
                || a == 0
                || a >= 240
            {
                AddressClass::Forbidden
            } else if v4.is_private() || (a == 100 && (64..128).contains(&b)) {
                AddressClass::Private
            } else {
                AddressClass::Public
            }
        }
        IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() {
                return classify(IpAddr::V4(v4));
            }
            let first = v6.segments()[0];
            if v6.is_loopback() {
                AddressClass::Loopback
            } else if v6.is_unspecified() || v6.is_multicast() || (first & 0xffc0) == 0xfe80 {
                AddressClass::Forbidden
            } else if (first & 0xfe00) == 0xfc00 {
                AddressClass::Private
            } else {
                AddressClass::Public
            }
        }
    }
}

/// Whether `ip` is inside `cidr` (`a.b.c.d/n` or `x::/n`).
pub fn in_cidr(ip: IpAddr, cidr: &str) -> bool {
    let Some((base, bits)) = cidr.split_once('/') else {
        return false;
    };
    let (Ok(base), Ok(bits)) = (base.parse::<IpAddr>(), bits.parse::<u32>()) else {
        return false;
    };
    match (ip, base) {
        (IpAddr::V4(ip), IpAddr::V4(base)) if bits <= 32 => {
            let mask = if bits == 0 {
                0
            } else {
                u32::MAX << (32 - bits)
            };
            u32::from(ip) & mask == u32::from(base) & mask
        }
        (IpAddr::V6(ip), IpAddr::V6(base)) if bits <= 128 => {
            let mask = if bits == 0 {
                0
            } else {
                u128::MAX << (128 - bits)
            };
            u128::from(ip) & mask == u128::from(base) & mask
        }
        _ => false,
    }
}

/// The connect-time rule for one destination.
#[derive(Debug, Clone)]
pub struct AddressPolicy {
    pub host: String,
    pub port: u16,
    pub private_ranges: Vec<PrivateRangeApproval>,
    pub allow_loopback: bool,
}

impl AddressPolicy {
    pub fn check(&self, ip: IpAddr) -> Result<(), AddressRefused> {
        let allowed = match classify(ip) {
            AddressClass::Public => true,
            AddressClass::Loopback => self.allow_loopback,
            AddressClass::Private => self.private_ranges.iter().any(|r| {
                r.host.eq_ignore_ascii_case(&self.host)
                    && in_cidr(ip, &r.cidr)
                    && r.ports.contains(&u64::from(self.port))
            }),
            AddressClass::Forbidden => false,
        };
        if allowed {
            Ok(())
        } else {
            Err(AddressRefused(format!(
                "{} resolves to {ip}, which destination policy does not allow",
                self.host
            )))
        }
    }
}

/// Resolves the destination's name and keeps only addresses the policy allows.
struct CheckedResolver(AddressPolicy);

impl reqwest::dns::Resolve for CheckedResolver {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        let policy = self.0.clone();
        let host = name.as_str().to_string();
        Box::pin(async move {
            if !host.eq_ignore_ascii_case(&policy.host) {
                return Err(Box::new(AddressRefused(format!(
                    "{host} is not the destination host"
                ))) as _);
            }
            let addrs: Vec<SocketAddr> = tokio::net::lookup_host((host.as_str(), policy.port))
                .await?
                .collect();
            let mut refused = None;
            let allowed: Vec<SocketAddr> = addrs
                .into_iter()
                .filter(|a| match policy.check(a.ip()) {
                    Ok(()) => true,
                    Err(e) => {
                        refused = Some(e);
                        false
                    }
                })
                .collect();
            // One forbidden address refuses the name, so a rebinding answer cannot mix one in.
            if let Some(e) = refused {
                return Err(Box::new(e) as _);
            }
            if allowed.is_empty() {
                return Err(Box::new(AddressRefused(format!("{host} did not resolve"))) as _);
            }
            Ok(Box::new(allowed.into_iter()) as reqwest::dns::Addrs)
        })
    }
}

/// An HTTP client for one approved destination.
pub fn client(
    destination: &ServiceDestination,
    policy: AddressPolicy,
) -> Result<reqwest::Client, String> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let mut builder = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .tls_sslkeylogfile(false)
        .tls_info(true)
        .connect_timeout(Duration::from_secs(10))
        .pool_max_idle_per_host(2)
        .dns_resolver(Arc::new(CheckedResolver(policy)));
    let tls = &destination.tls;
    if !tls.spki_pins.is_empty() {
        let pins = tls
            .spki_pins
            .iter()
            .map(|p| {
                p.strip_prefix("sha256/")
                    .map(str::to_string)
                    .ok_or_else(|| format!("pin {p} must be sha256/<base64>"))
            })
            .collect::<Result<Vec<_>, _>>()?;
        let provider = rustls::crypto::ring::default_provider();
        let algorithms = provider.signature_verification_algorithms;
        let mut config = rustls::ClientConfig::builder_with_provider(Arc::new(provider))
            .with_safe_default_protocol_versions()
            .map_err(|e| e.to_string())?
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(Pinned { pins, algorithms }))
            .with_no_client_auth();
        config.alpn_protocols = vec![b"http/1.1".to_vec()];
        builder = builder.tls_backend_preconfigured(config);
    } else if let Some(bundle) = &tls.ca_bundle {
        let certs = reqwest::Certificate::from_pem_bundle(bundle.as_bytes())
            .map_err(|e| format!("caBundle of {}: {e}", destination.id))?;
        if certs.is_empty() {
            return Err(format!(
                "caBundle of {} holds no certificate",
                destination.id
            ));
        }
        builder = builder.tls_certs_only(certs);
    }
    builder.build().map_err(|e| e.to_string())
}

/// Trusts a server when a certificate in its chain carries one of the pins. As in the Java
/// standalone runtime, an admin-pinned SPKI takes the place of the CA and host-name checks.
#[derive(Debug)]
struct Pinned {
    pins: Vec<String>,
    algorithms: rustls::crypto::WebPkiSupportedAlgorithms,
}

/// `base64(sha256(SubjectPublicKeyInfo))` of a DER certificate.
pub fn spki_pin(cert: &[u8]) -> Option<String> {
    use base64::Engine;
    use sha2::Digest;
    let (_, parsed) = x509_parser::parse_x509_certificate(cert).ok()?;
    let digest = sha2::Sha256::digest(parsed.tbs_certificate.subject_pki.raw);
    Some(base64::engine::general_purpose::STANDARD.encode(digest))
}

impl ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let matched = std::iter::once(end_entity)
            .chain(intermediates)
            .filter_map(|c| spki_pin(c))
            .any(|pin| self.pins.contains(&pin));
        if matched {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(
                "no certificate in the chain matches the destination's SPKI pins".into(),
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algorithms.supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy(private: Vec<PrivateRangeApproval>, loopback: bool) -> AddressPolicy {
        AddressPolicy {
            host: "api.example".into(),
            port: 443,
            private_ranges: private,
            allow_loopback: loopback,
        }
    }

    #[test]
    fn metadata_link_local_and_loopback_are_refused() {
        let p = policy(vec![], false);
        for ip in [
            "169.254.169.254",
            "127.0.0.1",
            "0.0.0.0",
            "::1",
            "fe80::1",
            "::ffff:169.254.169.254",
            "224.0.0.1",
        ] {
            assert!(p.check(ip.parse().unwrap()).is_err(), "{ip}");
        }
        assert!(p.check("93.184.216.34".parse().unwrap()).is_ok());
        assert!(policy(vec![], true)
            .check("127.0.0.1".parse().unwrap())
            .is_ok());
    }

    #[test]
    fn private_addresses_need_an_approved_range_for_the_host_and_port() {
        let range = PrivateRangeApproval {
            host: "api.example".into(),
            cidr: "10.1.0.0/16".into(),
            ports: vec![443],
        };
        let p = policy(vec![range.clone()], false);
        assert!(p.check("10.1.2.3".parse().unwrap()).is_ok());
        assert!(p.check("10.2.0.1".parse().unwrap()).is_err());
        assert!(p.check("192.168.1.1".parse().unwrap()).is_err());
        assert!(p.check("100.64.0.1".parse().unwrap()).is_err());
        let other_port = PrivateRangeApproval {
            ports: vec![8443],
            ..range
        };
        assert!(policy(vec![other_port], false)
            .check("10.1.2.3".parse().unwrap())
            .is_err());
    }

    #[test]
    fn cidr_matching() {
        assert!(in_cidr("10.0.0.1".parse().unwrap(), "10.0.0.0/8"));
        assert!(!in_cidr("11.0.0.1".parse().unwrap(), "10.0.0.0/8"));
        assert!(in_cidr("fd00::5".parse().unwrap(), "fd00::/8"));
        assert!(!in_cidr("10.0.0.1".parse().unwrap(), "fd00::/8"));
        assert!(!in_cidr("10.0.0.1".parse().unwrap(), "10.0.0.0"));
    }
}
