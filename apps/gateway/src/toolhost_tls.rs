// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Fixed tenant mTLS identity and exact Tool Host leaf pins. No system-root or plaintext fallback.
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{pem::PemObject, CertificateDer, PrivateKeyDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use serde::{Deserialize, Serialize};
use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Config {
    pub client_certificate_path: PathBuf,
    pub client_key_path: PathBuf,
    pub ca_certificate_path: PathBuf,
    pub server_certificates: Vec<PathBuf>,
}
fn invalid() -> String {
    "Dedicated tenant dispatch TLS identity, roots and server pins required".into()
}
fn read(path: &Path, secret: bool) -> Result<Vec<u8>, String> {
    let metadata = std::fs::symlink_metadata(path).map_err(|_| invalid())?;
    if !path.is_absolute() || !metadata.is_file() || metadata.len() > 131072 {
        return Err(invalid());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = metadata.permissions().mode();
        if mode & 0o022 != 0 || (secret && mode & 0o077 != 0) {
            return Err(invalid());
        }
    }
    std::fs::read(path).map_err(|_| invalid())
}
#[derive(Debug)]
struct Pinned {
    inner: Arc<dyn ServerCertVerifier>,
    leaves: Vec<Vec<u8>>,
}
impl ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        leaf: &CertificateDer<'_>,
        chain: &[CertificateDer<'_>],
        name: &ServerName<'_>,
        ocsp: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        self.inner
            .verify_server_cert(leaf, chain, name, ocsp, now)?;
        if self
            .leaves
            .iter()
            .any(|pin| pin.as_slice() == leaf.as_ref())
        {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("Tool Host leaf pin mismatch".into()))
        }
    }
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls12_signature(message, cert, signature)
    }
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls13_signature(message, cert, signature)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.inner.supported_verify_schemes()
    }
}
pub(crate) fn client(config: &Config, tenant: &str) -> Result<reqwest::Client, String> {
    if config.server_certificates.is_empty() || config.server_certificates.len() > 4 {
        return Err(invalid());
    }
    let certificate = read(&config.client_certificate_path, false)?;
    if olo_toolgate_tenant_identity::certificate_tenant(
        std::str::from_utf8(&certificate).map_err(|_| invalid())?,
    )
    .map_err(|_| invalid())?
        != tenant
    {
        return Err(invalid());
    }
    let chain = CertificateDer::pem_slice_iter(&certificate)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| invalid())?;
    if chain.len() != 1 {
        return Err(invalid());
    }
    let key = PrivateKeyDer::from_pem_slice(&read(&config.client_key_path, true)?)
        .map_err(|_| invalid())?;
    let mut roots = rustls::RootCertStore::empty();
    let ca = read(&config.ca_certificate_path, false)?;
    for root in CertificateDer::pem_slice_iter(&ca) {
        roots
            .add(root.map_err(|_| invalid())?)
            .map_err(|_| invalid())?;
    }
    if roots.is_empty() {
        return Err(invalid());
    }
    let inner = rustls::client::WebPkiServerVerifier::builder(Arc::new(roots))
        .build()
        .map_err(|_| invalid())?;
    let mut leaves = Vec::new();
    for path in &config.server_certificates {
        let bytes = read(path, false)?;
        let certs = CertificateDer::pem_slice_iter(&bytes)
            .collect::<Result<Vec<_>, _>>()
            .map_err(|_| invalid())?;
        if certs.len() != 1 || leaves.contains(&certs[0].to_vec()) {
            return Err(invalid());
        }
        leaves.push(certs[0].to_vec());
    }
    let tls = rustls::ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(Pinned { inner, leaves }))
        .with_client_auth_cert(chain, key)
        .map_err(|_| invalid())?;
    reqwest::Client::builder()
        .use_preconfigured_tls(tls)
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .retry(reqwest::retry::never())
        .connect_timeout(Duration::from_secs(3))
        .build()
        .map_err(|_| invalid())
}
