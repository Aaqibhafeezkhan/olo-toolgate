// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Inbound dispatch accepts only pinned Gateway leaves under a dedicated TLS client CA.
use rustls::pki_types::{pem::PemObject, CertificateDer, PrivateKeyDer};
use serde::Deserialize;
use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tokio::sync::Semaphore;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Config {
    pub certificate_path: PathBuf,
    pub private_key_path: PathBuf,
    pub gateway_ca_path: PathBuf,
    /// Exact accepted Gateway leaf certificates, each carrying this tenant's UUID as its sole OU.
    pub gateway_certificates: Vec<PathBuf>,
}
fn invalid() -> String {
    "Dedicated dispatch TLS custody and tenant Gateway pins required".into()
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
fn certificates(bytes: &[u8]) -> Result<Vec<CertificateDer<'static>>, String> {
    let certs = CertificateDer::pem_slice_iter(bytes)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| invalid())?;
    if certs.is_empty() || certs.len() > 4 {
        return Err(invalid());
    }
    Ok(certs)
}
fn gateway_leaf(bytes: &[u8], tenant: &str) -> Result<(), String> {
    use x509_parser::prelude::*;
    let (remaining, certificate) = parse_x509_certificate(bytes).map_err(|_| invalid())?;
    let units: Vec<_> = certificate.subject().iter_organizational_unit().collect();
    let usage = certificate
        .extended_key_usage()
        .map_err(|_| invalid())?
        .ok_or_else(invalid)?;
    if !remaining.is_empty()
        || certificate.is_ca()
        || units.len() != 1
        || units[0].as_str().map_err(|_| invalid())? != tenant
        || !usage.value.client_auth
        || usage.value.server_auth
        || usage.value.any
        || usage.value.code_signing
        || usage.value.email_protection
        || usage.value.time_stamping
        || usage.value.ocsp_signing
        || !usage.value.other.is_empty()
        || !certificate.validity().is_valid()
    {
        return Err(invalid());
    }
    Ok(())
}
pub struct Listener {
    acceptor: tokio_rustls::TlsAcceptor,
    pins: Arc<Vec<Vec<u8>>>,
}
impl Listener {
    pub fn open(config: &Config, tenant: &str) -> Result<Self, String> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        if config.gateway_certificates.is_empty() || config.gateway_certificates.len() > 4 {
            return Err(invalid());
        }
        let mut roots = rustls::RootCertStore::empty();
        for ca in certificates(&read(&config.gateway_ca_path, false)?)? {
            roots.add(ca).map_err(|_| invalid())?;
        }
        let verifier = rustls::server::WebPkiClientVerifier::builder(Arc::new(roots))
            .build()
            .map_err(|_| invalid())?;
        let key = PrivateKeyDer::from_pem_slice(&read(&config.private_key_path, true)?)
            .map_err(|_| invalid())?;
        let mut tls = rustls::ServerConfig::builder()
            .with_client_cert_verifier(verifier)
            .with_single_cert(certificates(&read(&config.certificate_path, false)?)?, key)
            .map_err(|_| invalid())?;
        tls.alpn_protocols = vec![b"http/1.1".to_vec()];
        let mut pins = Vec::new();
        for path in &config.gateway_certificates {
            let list = certificates(&read(path, false)?)?;
            if list.len() != 1 {
                return Err(invalid());
            }
            gateway_leaf(list[0].as_ref(), tenant)?;
            let pin = list[0].to_vec();
            if pins.contains(&pin) {
                return Err(invalid());
            }
            pins.push(pin);
        }
        Ok(Self {
            acceptor: tokio_rustls::TlsAcceptor::from(Arc::new(tls)),
            pins: Arc::new(pins),
        })
    }
    /// Bounded TLS/HTTP admission. Socket-accepted Gateway identity is checked before routing any body.
    pub async fn serve(
        self,
        listener: tokio::net::TcpListener,
        app: axum::Router,
    ) -> std::io::Result<()> {
        let slots = Arc::new(Semaphore::new(128));
        let mut connections = tokio::task::JoinSet::new();
        loop {
            tokio::select! {
                connection=listener.accept()=>{
                    let (socket,_)=connection?;
                    let Ok(slot)=slots.clone().try_acquire_owned() else {continue;};
                    let acceptor=self.acceptor.clone();let pins=self.pins.clone();let app=app.clone();
                    connections.spawn(async move{
                        let _slot=slot;
                        let Ok(Ok(stream))=tokio::time::timeout(Duration::from_secs(3),acceptor.accept(socket)).await else{return;};
                        let peer=stream.get_ref().1.peer_certificates().and_then(|chain|chain.first());
                        if !peer.is_some_and(|peer|pins.iter().any(|pin|pin.as_slice()==peer.as_ref())) {return;}
                        let service=hyper_util::service::TowerToHyperService::new(app);
                        let mut builder=hyper::server::conn::http1::Builder::new();
                        builder.keep_alive(false).max_buf_size(32768).timer(hyper_util::rt::TokioTimer::new())
                            .header_read_timeout(Duration::from_secs(5));
                        let _=tokio::time::timeout(Duration::from_secs(70),
                            builder.serve_connection(hyper_util::rt::TokioIo::new(stream),service)).await;
                    });
                },
                Some(_)=connections.join_next(),if !connections.is_empty()=>{},
            }
        }
    }
}
