// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Control-issued user credentials (M3.4): pinned-kid RS256 verification and the HUMAN context they produce.
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use olo_toolgate_contracts::v2::{CredentialKind, RequestMode};
use olo_toolgate_gateway::{
    auth::{Authenticator, Credential},
    digest,
    user_credential::Verifier,
    validation::Contracts,
};
use rsa::pkcs8::{EncodePublicKey, LineEnding};
use rsa::signature::{SignatureEncoding, Signer};
use serde_json::{json, Value};
use std::sync::OnceLock;

const TENANT: &str = "10000000-0000-4000-8000-000000000001";
const STATIC: &str = "enterprise-test-credential-00000000000000000000000000000000";
const NOW_S: u64 = 1_800_000_000;
const NOW: u64 = NOW_S * 1000;

fn key() -> &'static rsa::RsaPrivateKey {
    static KEY: OnceLock<rsa::RsaPrivateKey> = OnceLock::new();
    KEY.get_or_init(|| rsa::RsaPrivateKey::new(&mut rand::thread_rng(), 2048).unwrap())
}
fn other_key() -> &'static rsa::RsaPrivateKey {
    static KEY: OnceLock<rsa::RsaPrivateKey> = OnceLock::new();
    KEY.get_or_init(|| rsa::RsaPrivateKey::new(&mut rand::thread_rng(), 2048).unwrap())
}
fn pem(key: &rsa::RsaPrivateKey) -> Vec<u8> {
    key.to_public_key()
        .to_public_key_pem(LineEnding::LF)
        .unwrap()
        .into_bytes()
}
fn verifier() -> Verifier {
    Verifier::new(
        "toolgate-control",
        "toolgate-gateway",
        vec![("credential-1".into(), pem(key()))],
    )
    .unwrap()
}
fn claims() -> Value {
    json!({"iss":"toolgate-control","aud":"toolgate-gateway","sub":"user-42","tid":TENANT,"sep":3,"bid":"binding-olo",
        "iat":NOW_S,"exp":NOW_S+300,"jti":"0b8b7c1e-1111-4222-8333-444455556666"})
}
fn header() -> Value {
    json!({"alg":"RS256","typ":"toolgate-user-credential+jws","kid":"credential-1"})
}
fn sign_with(key: &rsa::RsaPrivateKey, header: &Value, claims: &Value) -> String {
    let input = format!(
        "{}.{}",
        URL_SAFE_NO_PAD.encode(header.to_string()),
        URL_SAFE_NO_PAD.encode(claims.to_string())
    );
    let signer = rsa::pkcs1v15::SigningKey::<rsa::sha2::Sha256>::new(key.clone());
    format!(
        "{input}.{}",
        URL_SAFE_NO_PAD.encode(signer.sign(input.as_bytes()).to_bytes())
    )
}
fn sign(header: &Value, claims: &Value) -> String {
    sign_with(key(), header, claims)
}

#[test]
fn verified_credential_becomes_a_human_context_bound_to_its_digest() {
    let token = sign(&header(), &claims());
    let (context, expires) = verifier().verify(&token, "req-1".into(), NOW).unwrap();
    assert_eq!(expires, (NOW_S + 300) * 1000);
    assert_eq!(context.mode, RequestMode::Human);
    assert_eq!(context.credential_kind, CredentialKind::Jwt);
    assert_eq!(context.tenant_id, TENANT);
    assert_eq!(context.issuer.as_deref(), Some("toolgate-control"));
    assert_eq!(context.user_id.as_deref(), Some("user-42"));
    assert_eq!(context.session_epoch, Some(3));
    assert_eq!(context.binding_id.as_deref(), Some("binding-olo"));
    assert_eq!(
        context.credential_sha256.as_deref(),
        Some(digest(token.as_bytes()).as_str())
    );
    assert_eq!(context.expires_at_unix_ms, Some(expires));
    assert!(
        context.agent_id.is_none()
            && context.workload_binding_id.is_none()
            && context.chain.is_empty()
    );
    assert_eq!(context.request_id, "req-1");
}

#[test]
fn refuses_every_header_signature_and_claim_defect() {
    let v = verifier();
    let refused = |token: String| assert!(v.verify(&token, "r".into(), NOW).is_none());
    let mut h = header();
    h["kid"] = json!("unknown");
    refused(sign(&h, &claims()));
    let mut h = header();
    h["typ"] = json!("JWT");
    refused(sign(&h, &claims()));
    let mut h = header();
    h["alg"] = json!("none");
    refused(sign(&h, &claims()));
    let mut h = header();
    h["jku"] = json!("https://attacker.invalid/keys");
    refused(sign(&h, &claims()));
    refused(sign_with(other_key(), &header(), &claims()));
    let good = sign(&header(), &claims());
    let (head, rest) = good.split_once('.').unwrap();
    let (_, signature) = rest.split_once('.').unwrap();
    let mut forged = claims();
    forged["sub"] = json!("admin");
    refused(format!(
        "{head}.{}.{signature}",
        URL_SAFE_NO_PAD.encode(forged.to_string())
    ));
    for (field, value) in [
        ("iss", json!("someone-else")),
        ("aud", json!("another-gateway")),
        ("tid", json!("not-a-tenant")),
        ("sep", json!(0)),
        ("sub", json!("")),
        ("exp", json!(NOW_S)),
        ("exp", json!(NOW_S + 301)),
        ("iat", json!(NOW_S + 60)),
    ] {
        let mut c = claims();
        c[field] = value;
        refused(sign(&header(), &c));
    }
    let mut extra = claims();
    extra["scope"] = json!("admin");
    refused(sign(&header(), &extra));
    let mut missing = claims();
    missing.as_object_mut().unwrap().remove("bid");
    refused(sign(&header(), &missing));
    refused(format!("{good}.extra"));
    refused("a".repeat(5000));
}

#[test]
fn authenticator_accepts_user_credentials_only_when_configured_and_keeps_static_tokens() {
    let contracts = Contracts::new().unwrap();
    let context: olo_toolgate_contracts::v2::RequestContext = serde_json::from_value(json!({"requestId":"operation","tenantId":TENANT,"mode":"SERVICE","agentId":"agent-demo",
        "workloadBindingId":"workload","chain":[],"credentialEpoch":1,"credentialKind":"STATIC_TOKEN","bindingId":"binding",
        "credentialSha256":digest(STATIC.as_bytes())}))
    .unwrap();
    let credential = || Credential {
        token_sha256: digest(STATIC.as_bytes()),
        context: context.clone(),
        expires_at_unix_ms: 4102444800000,
    };
    let token = sign(&header(), &claims());
    let plain = Authenticator::new(vec![credential()], &contracts, NOW).unwrap();
    assert!(plain.authenticate(&token, "r".into(), NOW).is_none());
    let users = Authenticator::new(vec![credential()], &contracts, NOW)
        .unwrap()
        .with_user_credentials(verifier());
    assert_eq!(
        users.authenticate(&token, "r".into(), NOW).unwrap().0.mode,
        RequestMode::Human
    );
    assert_eq!(
        users.authenticate(STATIC, "r".into(), NOW).unwrap().0.mode,
        RequestMode::Service
    );
}

#[test]
fn settings_require_distinct_pinned_rsa_keys() {
    assert!(Verifier::new("toolgate-control", "toolgate-gateway", vec![]).is_err());
    assert!(Verifier::new(
        "toolgate-control",
        "toolgate-gateway",
        vec![("k".into(), pem(key())), ("k".into(), pem(other_key()))]
    )
    .is_err());
    assert!(Verifier::new(
        "toolgate-control",
        "toolgate-gateway",
        vec![("k".into(), b"not a key".to_vec())]
    )
    .is_err());
}

const AGENT: &str = "agent-on-behalf-of-credential-000000000000000000000000000000";

fn on_behalf_of() -> Credential {
    Credential {
        token_sha256: digest(AGENT.as_bytes()),
        context: serde_json::from_value(json!({"requestId":"operation","tenantId":TENANT,"mode":"DELEGATED",
            "agentId":"agent-planner","workloadBindingId":"workload-planner","chain":[],"credentialEpoch":2,
            "credentialKind":"STATIC_TOKEN","bindingId":"binding-agents","credentialSha256":digest(AGENT.as_bytes())}))
        .unwrap(),
        expires_at_unix_ms: 4102444800000,
    }
}

#[test]
fn delegated_calls_combine_the_verified_user_with_the_agent_workload() {
    let contracts = Contracts::new().unwrap();
    let auth = Authenticator::new(vec![on_behalf_of()], &contracts, NOW)
        .unwrap()
        .with_user_credentials(verifier());
    let token = sign(&header(), &claims());
    let (context, expires) = auth
        .authenticate_delegated(&token, AGENT, "req-9".into(), NOW)
        .unwrap();
    assert_eq!(context.mode, RequestMode::Delegated);
    assert_eq!(context.credential_kind, CredentialKind::Jwt);
    assert_eq!(context.issuer.as_deref(), Some("toolgate-control"));
    assert_eq!(context.user_id.as_deref(), Some("user-42"));
    assert_eq!(context.session_epoch, Some(3));
    assert_eq!(context.agent_id.as_deref(), Some("agent-planner"));
    assert_eq!(
        context.workload_binding_id.as_deref(),
        Some("workload-planner")
    );
    assert_eq!(context.credential_epoch, Some(2));
    assert_eq!(context.binding_id.as_deref(), Some("binding-agents"));
    assert_eq!(
        context.credential_sha256.as_deref(),
        Some(digest(AGENT.as_bytes()).as_str()),
        "Control checks the agent's workload credential digest"
    );
    assert_eq!(expires, (NOW_S + 300) * 1000);
}

#[test]
fn an_on_behalf_of_token_never_acts_alone_or_with_a_forged_user() {
    let contracts = Contracts::new().unwrap();
    let auth = Authenticator::new(vec![on_behalf_of()], &contracts, NOW)
        .unwrap()
        .with_user_credentials(verifier());
    assert!(auth.authenticate(AGENT, "r".into(), NOW).is_none());
    assert!(auth
        .authenticate_delegated(AGENT, AGENT, "r".into(), NOW)
        .is_none());
    let forged = sign_with(other_key(), &header(), &claims());
    assert!(auth
        .authenticate_delegated(&forged, AGENT, "r".into(), NOW)
        .is_none());
    let mut foreign = claims();
    foreign["tid"] = json!("10000000-0000-4000-8000-000000000002");
    assert!(auth
        .authenticate_delegated(&sign(&header(), &foreign), AGENT, "r".into(), NOW)
        .is_none());
    let plain = Authenticator::new(vec![on_behalf_of()], &contracts, NOW).unwrap();
    assert!(plain
        .authenticate_delegated(&sign(&header(), &claims()), AGENT, "r".into(), NOW)
        .is_none());
}

#[test]
fn a_fixed_user_delegated_credential_cannot_be_combined_with_another_user() {
    let contracts = Contracts::new().unwrap();
    let mut fixed = on_behalf_of();
    fixed.context.user_id = Some("user-7".into());
    fixed.context.session_epoch = Some(1);
    let auth = Authenticator::new(vec![fixed], &contracts, NOW)
        .unwrap()
        .with_user_credentials(verifier());
    assert!(auth
        .authenticate_delegated(&sign(&header(), &claims()), AGENT, "r".into(), NOW)
        .is_none());
    let mut half = on_behalf_of();
    half.context.user_id = Some("user-7".into());
    assert!(Authenticator::new(vec![half], &contracts, NOW).is_err());
}
