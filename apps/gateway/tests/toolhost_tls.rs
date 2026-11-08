// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Actual Gateway client and Tool Host TLS listener; no application authority is fabricated by this transport fixture.
use olo_toolgate_contracts::v2::{Dispatch, RefusalReason};
use olo_toolgate_gateway::toolhost::{Dispatched, ToolHostRoute, ToolHosts};
use rcgen::{
    BasicConstraints, Certificate, CertificateParams, DnType, ExtendedKeyUsagePurpose, IsCa,
    Issuer, KeyPair, KeyUsagePurpose,
};
use serde_json::{json, Value};
use std::{
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Arc,
    },
};
const TENANT: &str = "10000000-0000-4000-8000-000000000001";
struct Area(PathBuf);
impl Area {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!(
            "toolgate-inbound-tls-{}-{}",
            std::process::id(),
            rand::random::<u128>()
        ));
        std::fs::create_dir(&root).unwrap();
        Self(root)
    }
    fn pem(&self, name: &str, bytes: &str, secret: bool) -> PathBuf {
        let path = self.0.join(name);
        std::fs::write(&path, bytes).unwrap();
        #[cfg(unix)]
        if secret {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        }
        path
    }
}
impl Drop for Area {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
struct Identity {
    cert: Certificate,
    key: KeyPair,
    params: CertificateParams,
}
fn ca() -> Identity {
    let key = KeyPair::generate().unwrap();
    let mut params = CertificateParams::new(Vec::<String>::new()).unwrap();
    params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    params.key_usages = vec![KeyUsagePurpose::KeyCertSign, KeyUsagePurpose::CrlSign];
    Identity {
        cert: params.self_signed(&key).unwrap(),
        key,
        params,
    }
}
fn leaf(ca: &Identity, tenant: &str, server: bool) -> Identity {
    let key = KeyPair::generate().unwrap();
    let mut params = CertificateParams::new(vec!["localhost".into()]).unwrap();
    params
        .distinguished_name
        .push(DnType::OrganizationalUnitName, tenant);
    params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
    params.extended_key_usages = vec![if server {
        ExtendedKeyUsagePurpose::ServerAuth
    } else {
        ExtendedKeyUsagePurpose::ClientAuth
    }];
    Identity {
        cert: params
            .signed_by(&key, &Issuer::from_params(&ca.params, &ca.key))
            .unwrap(),
        key,
        params,
    }
}
async fn server(
    settings: &tool_host::dispatch_tls::Config,
    hits: Arc<AtomicUsize>,
    reject_push: Arc<AtomicBool>,
) -> (String, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let dispatch_hits = hits.clone();
    let app = axum::Router::new().route(
        "/v1/dispatch",
        axum::routing::post(move |axum::Json(_body): axum::Json<Value>| {
            let hits = dispatch_hits.clone();
            async move {
                hits.fetch_add(1, Ordering::SeqCst);
                (
                    [("Content-Type", "application/x-ndjson")],
                    format!(
                        "{}\n",
                        json!({"kind":"REFUSED","invocationId":"inv-tls","reason":"PERMIT_INVALID"})
                    ),
                )
            }
        }),
    );
    let app = app.route(
        "/v1/kills",
        axum::routing::post(move |axum::Json(_body): axum::Json<Value>| {
            let hits = hits.clone();
            let reject_push = reject_push.clone();
            async move {
                use axum::response::IntoResponse;
                hits.fetch_add(1, Ordering::SeqCst);
                if reject_push.swap(false, Ordering::SeqCst) {
                    return axum::http::StatusCode::SERVICE_UNAVAILABLE.into_response();
                }
                axum::Json(json!({"authorizationEpoch":1,"deliveryPath":"PUSH"})).into_response()
            }
        }),
    );
    let tls = tool_host::dispatch_tls::Listener::open(settings, TENANT).unwrap();
    let task = tokio::spawn(async move {
        tls.serve(listener, app).await.unwrap();
    });
    (format!("https://localhost:{port}"), task)
}
fn fixture() -> Dispatch {
    let value: Value = serde_json::from_str(include_str!(
        "../../../tests/fixtures/contracts/v2/valid.json"
    ))
    .unwrap();
    serde_json::from_value(value["Dispatch"].clone()).unwrap()
}
fn route(url: &str, ca: &Path, client: &Path, key: &Path, pin: &Path) -> ToolHostRoute {
    serde_json::from_value(json!({"hostId":"host-1","url":url,"tls":{
        "clientCertificatePath":client,"clientKeyPath":key,"caCertificatePath":ca,"serverCertificates":[pin]
    }})).unwrap()
}
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn gateway_identity_server_pins_and_tenant_bound_host_acceptance_precede_dispatch_body() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let area = Area::new();
    let authority = ca();
    let server_identity = leaf(&authority, TENANT, true);
    let gateway = leaf(&authority, TENANT, false);
    let other = leaf(&authority, TENANT, false);
    let foreign = leaf(&authority, "20000000-0000-4000-8000-000000000001", false);
    let ca_path = area.pem("ca.pem", &authority.cert.pem(), false);
    let server_cert = area.pem("server.pem", &server_identity.cert.pem(), false);
    let server_key = area.pem("server.key", &server_identity.key.serialize_pem(), true);
    let client = area.pem("gateway.pem", &gateway.cert.pem(), false);
    let key = area.pem("gateway.key", &gateway.key.serialize_pem(), true);
    let other_cert = area.pem("other.pem", &other.cert.pem(), false);
    let other_key = area.pem("other.key", &other.key.serialize_pem(), true);
    let foreign_cert = area.pem("foreign.pem", &foreign.cert.pem(), false);
    let foreign_key = area.pem("foreign.key", &foreign.key.serialize_pem(), true);
    let settings = tool_host::dispatch_tls::Config {
        certificate_path: server_cert.clone(),
        private_key_path: server_key,
        gateway_ca_path: ca_path.clone(),
        gateway_certificates: vec![client.clone()],
    };
    let hits = Arc::new(AtomicUsize::new(0));
    let reject_push = Arc::new(AtomicBool::new(false));
    let (url, server_task) = server(&settings, hits.clone(), reject_push.clone()).await;
    let dispatch = fixture();
    let deadline = || olo_toolgate_gateway::unix_ms().unwrap() + 3000;
    let no_identity = reqwest::Client::builder()
        .no_proxy()
        .add_root_certificate(
            reqwest::Certificate::from_pem(authority.cert.pem().as_bytes()).unwrap(),
        )
        .build()
        .unwrap();
    assert!(no_identity
        .post(format!("{url}/v1/dispatch"))
        .header("X-Forwarded-Client-Cert", "forged")
        .header("Content-Type", "application/json")
        .body(serde_json::to_vec(&dispatch).unwrap())
        .send()
        .await
        .is_err());
    for bad in [
        route(&url, &ca_path, &other_cert, &other_key, &server_cert),
        route(&url, &ca_path, &foreign_cert, &foreign_key, &server_cert),
        route(&url, &ca_path, &client, &key, &ca_path),
    ] {
        assert!(ToolHosts::new(std::slice::from_ref(&bad), TENANT)
            .dispatch(&url, &dispatch, "inv-tls", deadline())
            .await
            .is_err());
        assert_eq!(hits.load(Ordering::SeqCst), 0);
        ToolHosts::new(std::slice::from_ref(&bad), TENANT)
            .push_revocations(b"{}", 1)
            .await;
        assert_eq!(hits.load(Ordering::SeqCst), 0);
    }
    let good = route(&url, &ca_path, &client, &key, &server_cert);
    good.validate().unwrap();
    let hosts = ToolHosts::new(std::slice::from_ref(&good), TENANT);
    assert!(matches!(
        hosts
            .dispatch(&url, &dispatch, "inv-tls", deadline())
            .await
            .unwrap(),
        Dispatched::Refused(RefusalReason::PermitInvalid)
    ));
    assert_eq!(hits.load(Ordering::SeqCst), 1);
    reject_push.store(true, Ordering::SeqCst);
    assert!(!hosts.push_revocations(b"{}", 1).await);
    assert_eq!(hits.load(Ordering::SeqCst), 2);
    assert!(hosts.push_revocations(b"{}", 1).await); // retry unrecorded receipt
    assert_eq!(hits.load(Ordering::SeqCst), 3);
    assert!(hosts.push_revocations(b"{}", 1).await); // unchanged accepted state
    assert_eq!(hits.load(Ordering::SeqCst), 3);
    assert!(!hosts.push_revocations(b"{}", 2).await); // stale acknowledgement
    assert!(!hosts.push_revocations(b"{}", 2).await); // refusal was not cached
    assert_eq!(hits.load(Ordering::SeqCst), 5);
    assert!(hosts
        .dispatch(
            "https://unconfigured.example.test",
            &dispatch,
            "inv-tls",
            deadline()
        )
        .await
        .is_err());
    assert_eq!(hits.load(Ordering::SeqCst), 5);
    server_task.abort();
    let _ = server_task.await;
    // A newly valid server leaf under the same CA and hostname still cannot satisfy the old leaf pin.
    let replacement = leaf(&authority, TENANT, true);
    let mut changed = settings.clone();
    changed.certificate_path = area.pem("replacement.pem", &replacement.cert.pem(), false);
    changed.private_key_path = area.pem("replacement.key", &replacement.key.serialize_pem(), true);
    let (url, task) = server(&changed, hits.clone(), reject_push.clone()).await;
    let stale_pin = route(&url, &ca_path, &client, &key, &server_cert);
    assert!(ToolHosts::new(&[stale_pin], TENANT)
        .dispatch(&url, &dispatch, "inv-tls", deadline())
        .await
        .is_err());
    assert_eq!(hits.load(Ordering::SeqCst), 5);
    task.abort();
    let _ = task.await;
    let mut wrong_purpose = settings.clone();
    wrong_purpose.gateway_certificates = vec![server_cert];
    assert!(tool_host::dispatch_tls::Listener::open(&wrong_purpose, TENANT).is_err());
    let mut wrong_tenant = settings;
    wrong_tenant.gateway_certificates = vec![foreign_cert];
    assert!(tool_host::dispatch_tls::Listener::open(&wrong_tenant, TENANT).is_err());
}
#[test]
fn plaintext_routes_require_explicit_local_development_and_never_accept_tls_identity() {
    for url in [
        "http://remote.example.test",
        "http://localhost/?query=1",
        "http://user:pass@localhost",
        "http://localhost/#fragment",
    ] {
        let route: ToolHostRoute = serde_json::from_value(
            json!({"hostId":"host-1","url":url,"developmentLoopbackHttp":true}),
        )
        .unwrap();
        assert!(route.validate().is_err());
    }
    let mut route: ToolHostRoute =
        serde_json::from_value(json!({"hostId":"host-1","url":"http://127.0.0.1:8095"})).unwrap();
    assert!(route.validate().is_err());
    route.development_loopback_http = true;
    route.validate().unwrap();
}
