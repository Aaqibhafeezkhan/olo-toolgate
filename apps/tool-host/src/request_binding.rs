// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Exact buffered request preparation. A digest is an observation, not an authorization.
use reqwest::{header, Client, Request, Response};
use serde_json::Value;
use serde_json::{json, Map};
use std::collections::BTreeSet;

pub(crate) struct Metadata {
    pub profile_id: String,
    pub profile_digest: String,
    pub route_id: String,
    pub auth_id: Option<String>,
    pub credential_headers: Vec<String>,
}
pub(crate) struct PreparedRequest {
    client: Client,
    request: Request,
    digest: String,
    service_permit_credential: bool,
    manifest: Value,
    effect_key: Option<String>,
}
impl PreparedRequest {
    pub(crate) fn new(
        client: &Client,
        mut request: Request,
        meta: Metadata,
    ) -> Result<Self, String> {
        if meta.profile_id.is_empty()
            || meta.route_id.is_empty()
            || meta.profile_id.len() > 128
            || meta.route_id.len() > 128
            || !meta
                .profile_digest
                .strip_prefix("sha256:")
                .is_some_and(|s| {
                    s.len() == 64
                        && s.bytes()
                            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                })
        {
            return Err("a pinned profile and route are required".into());
        }
        if request.url().fragment().is_some()
            || !request.url().username().is_empty()
            || request.url().password().is_some()
            || !matches!(request.url().scheme(), "https" | "http")
        {
            return Err("invalid bound destination".into());
        }
        let mut pairs: Vec<String> = request
            .url()
            .query()
            .unwrap_or_default()
            .split('&')
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .collect();
        pairs.sort_by(|a, b| a.split('=').next().cmp(&b.split('=').next()));
        let query = pairs.join("&");
        request
            .url_mut()
            .set_query((!query.is_empty()).then_some(query.as_str()));
        // reqwest adds this default during execute(); materialize it before binding.
        if !request.headers().contains_key(header::ACCEPT) {
            request
                .headers_mut()
                .insert(header::ACCEPT, header::HeaderValue::from_static("*/*"));
        }
        let method = request.method().as_str().to_ascii_uppercase();
        *request.method_mut() =
            reqwest::Method::from_bytes(method.as_bytes()).map_err(|_| "invalid bound method")?;
        if request
            .headers()
            .get_all(header::CONTENT_TYPE)
            .iter()
            .count()
            > 1
        {
            return Err("duplicate content type".into());
        }
        let content_type = match request.headers().get(header::CONTENT_TYPE) {
            Some(v) => content_type(v.to_str().map_err(|_| "non-ASCII content type")?)?,
            None => String::new(),
        };
        if !content_type.is_empty() {
            request.headers_mut().insert(
                header::CONTENT_TYPE,
                header::HeaderValue::from_str(&content_type).map_err(|_| "invalid content type")?,
            );
        }
        let credentials: BTreeSet<String> = meta
            .credential_headers
            .iter()
            .map(|s| s.to_ascii_lowercase())
            .collect();
        if credentials.is_empty() != meta.auth_id.is_none()
            || meta.auth_id.as_ref().is_some_and(|s| s.is_empty())
        {
            return Err("credential names require an auth declaration".into());
        }
        for name in &credentials {
            if matches!(
                name.as_str(),
                "host"
                    | "accept"
                    | "cookie"
                    | "content-type"
                    | "content-encoding"
                    | "content-length"
                    | "transfer-encoding"
                    | "connection"
                    | "user-agent"
                    | "traceparent"
                    | "tracestate"
            ) {
                return Err("credential header overlaps request semantics".into());
            }
            header::HeaderName::from_bytes(name.as_bytes())
                .map_err(|_| "invalid credential header name")?;
        }
        if request.headers().contains_key("toolgate-effect-permit")
            || credentials.contains("toolgate-effect-permit")
        {
            return Err("target permit is reserved for the supervisor".into());
        }
        let mut headers = Map::new();
        for name in request.headers().keys() {
            let name = name.as_str();
            if matches!(
                name,
                "authorization" | "proxy-authorization" | "cookie" | "toolgate-service-permit"
            ) && !credentials.contains(name)
            {
                return Err("undeclared credential header".into());
            }
            if credentials.contains(name)
                || matches!(
                    name,
                    "host"
                        | "content-length"
                        | "transfer-encoding"
                        | "connection"
                        | "user-agent"
                        | "traceparent"
                        | "tracestate"
                        | "content-type"
                )
            {
                continue;
            }
            let values: Result<Vec<_>, _> = request
                .headers()
                .get_all(name)
                .iter()
                .map(|v| v.to_str().map(str::to_owned))
                .collect();
            headers.insert(
                name.into(),
                json!(values.map_err(|_| "non-ASCII bound header")?),
            );
        }
        let bytes = match request.body() {
            None => &[][..],
            Some(body) => body
                .as_bytes()
                .ok_or("streaming request bodies cannot be bound")?,
        };
        let manifest = json!({"version":"toolgate-http-request-v1","profileId":meta.profile_id,"profileDigest":meta.profile_digest,
            "routeId":meta.route_id,"scheme":request.url().scheme(),"host":request.url().host_str().ok_or("missing host")?.trim_matches(['[',']']).to_ascii_lowercase(),
            "port":request.url().port_or_known_default().ok_or("missing port")?,"method":method,"path":request.url().path(),"query":query,
            "contentType":content_type,"bodyDigest":toolgate_cli::canonical::sha256_tagged(bytes),"headers":headers,
            "authDeclarationId":meta.auth_id,"credentialHeaders":credentials});
        let digest = crate::digest::jcs(&manifest)?;
        Ok(Self {
            client: client.clone(),
            request,
            digest,
            service_permit_credential: credentials.contains("toolgate-service-permit"),
            manifest,
            effect_key: None,
        })
    }
    /// Logical identity metadata, excluded from request bytes; Control binds it to the immutable effect.
    pub(crate) fn with_effect_key(mut self, key: Option<&str>) -> Result<Self, String> {
        if key.is_some_and(|value| {
            value.is_empty()
                || value.len() > 128
                || !value
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"._:-".contains(&b))
        }) {
            return Err("invalid logical effect key".into());
        }
        self.effect_key = key.map(str::to_owned);
        Ok(self)
    }
    pub(crate) fn effect_key(&self) -> Option<&str> {
        self.effect_key.as_deref()
    }
    /// Inject only a Control grant after exact request binding; worker-supplied tokens are refused.
    pub(crate) fn with_target_permit(
        mut self,
        permit: Option<&olo_toolgate_contracts::v2::SignedEffectGrant>,
    ) -> Result<Self, String> {
        let Some(permit) = permit else {
            return Ok(self);
        };
        if self.request.url().scheme() != "https"
            || self
                .request
                .headers()
                .contains_key("toolgate-effect-permit")
            || permit.jws.is_empty()
            || permit.jws.len() > 32768
        {
            return Err("invalid or substituted target permit".into());
        }
        let mut value =
            header::HeaderValue::from_str(&permit.jws).map_err(|_| "invalid target permit")?;
        value.set_sensitive(true);
        self.request
            .headers_mut()
            .insert("toolgate-effect-permit", value);
        Ok(self)
    }
    /// Only Control's derived target key may be injected after binding; its fixed name is bound by the pinned profile.
    pub(crate) fn with_downstream_key(
        mut self,
        key: Option<&olo_toolgate_contracts::v2::DownstreamEffectKey>,
    ) -> Result<Self, String> {
        let Some(key) = key else {
            return Ok(self);
        };
        let forbidden = [
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "host",
            "content-type",
            "content-length",
            "transfer-encoding",
            "connection",
            "te",
            "upgrade",
            "accept",
            "user-agent",
            "traceparent",
            "tracestate",
            "toolgate-service-permit",
            "toolgate-effect-permit",
            "trailer",
            "keep-alive",
            "proxy-connection",
            "content-encoding",
            "expect",
            "range",
            "content-range",
            "if-match",
            "if-none-match",
            "if-range",
            "if-modified-since",
            "if-unmodified-since",
        ];
        if !key
            .header
            .as_bytes()
            .first()
            .is_some_and(u8::is_ascii_lowercase)
            || key.header.len() > 64
            || forbidden.contains(&key.header.as_str())
            || key.header.starts_with("x-forwarded-")
            || !key
                .header
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
            || key.value.len() != 67
            || !key.value.starts_with("tg-")
            || !key.value[3..]
                .bytes()
                .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
            || self.request.headers().contains_key(&key.header)
        {
            return Err("invalid or substituted target effect key".into());
        }
        let name = header::HeaderName::from_bytes(key.header.as_bytes())
            .map_err(|_| "invalid target header")?;
        let mut value =
            header::HeaderValue::from_str(&key.value).map_err(|_| "invalid target key")?;
        value.set_sensitive(true);
        self.request.headers_mut().insert(name, value);
        Ok(self)
    }
    pub(crate) fn manifest(&self) -> &Value {
        &self.manifest
    }
    pub(crate) fn digest(&self) -> &str {
        &self.digest
    }
    /// Only the excluded service credential may be added after request preparation.
    pub(crate) fn with_service_permit(mut self, jws: &str) -> Result<Self, String> {
        if !self.service_permit_credential
            || self
                .request
                .headers()
                .contains_key("toolgate-service-permit")
        {
            return Err("request is not awaiting a service permit".into());
        }
        let mut value = header::HeaderValue::from_str(jws).map_err(|_| "invalid service permit")?;
        value.set_sensitive(true);
        self.request
            .headers_mut()
            .insert("toolgate-service-permit", value);
        Ok(self)
    }
    pub(crate) fn into_buffered(
        self,
        handler: &str,
    ) -> Result<crate::broker_process::BufferedRequest, &'static str> {
        let manifest =
            serde_json::from_value(self.manifest).map_err(|_| "invalid prepared manifest")?;
        let mut wire =
            crate::broker_process::freeze_request(&self.client, self.request, &manifest, handler)?;
        wire.effect_key = self.effect_key;
        Ok(wire)
    }
    pub(crate) fn body_len(&self) -> usize {
        self.request
            .body()
            .and_then(|body| body.as_bytes())
            .map_or(0, <[u8]>::len)
    }
    pub(crate) fn using_client(mut self, client: Client) -> Self {
        self.client = client;
        self
    }
    pub(crate) fn service_permit_is(&self, permit: &olo_toolgate_contracts::ServicePermit) -> bool {
        let mut values = self
            .request
            .headers()
            .get_all("toolgate-service-permit")
            .iter();
        values
            .next()
            .is_some_and(|value| value.as_bytes() == permit.jws.as_bytes())
            && values.next().is_none()
    }
    pub(crate) async fn send(self) -> Result<Response, reqwest::Error> {
        self.client.execute(self.request).await
    }
}
fn token(s: &str) -> bool {
    !s.is_empty()
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b))
}
fn quoted_value(value: &str) -> bool {
    if !value.starts_with('"') || !value.ends_with('"') || value.len() < 2 {
        return false;
    }
    let mut escaped = false;
    for b in value.as_bytes()[1..value.len() - 1].iter().copied() {
        if b < 32 || b == 127 {
            return false;
        }
        if escaped {
            escaped = false;
        } else if b == b'\\' {
            escaped = true;
        } else if b == b'"' {
            return false;
        }
    }
    !escaped
}
fn content_type(value: &str) -> Result<String, String> {
    if value.len() > 8192 || !value.is_ascii() {
        return Err("invalid content type".into());
    }
    let mut parts = Vec::new();
    let (mut quoted, mut escaped, mut start) = (false, false, 0);
    for (n, c) in value.char_indices() {
        if escaped {
            escaped = false;
            continue;
        }
        if quoted && c == '\\' {
            escaped = true;
            continue;
        }
        if c == '"' {
            quoted = !quoted;
        } else if c == ';' && !quoted {
            parts.push(value[start..n].trim());
            start = n + 1;
        }
    }
    if quoted || escaped {
        return Err("unterminated content type".into());
    }
    parts.push(value[start..].trim());
    let media = parts[0].to_ascii_lowercase();
    let (kind, subtype) = media.split_once('/').ok_or("invalid media type")?;
    if !token(kind) || !token(subtype) {
        return Err("invalid media type".into());
    }
    let mut params = std::collections::BTreeMap::new();
    for part in parts.iter().skip(1) {
        let (name, value) = part
            .split_once('=')
            .ok_or("invalid content type parameter")?;
        let name = name.trim().to_ascii_lowercase();
        let value = value.trim();
        if !token(&name)
            || value.is_empty()
            || (!token(value) && !quoted_value(value))
            || value.bytes().any(|b| b < 32 || b == 127)
        {
            return Err("invalid content type parameter".into());
        }
        let value = if name == "charset" {
            value.to_ascii_lowercase()
        } else {
            value.into()
        };
        if params.insert(name, value).is_some() {
            return Err("duplicate content type parameter".into());
        }
    }
    let mut out = media;
    for (name, value) in params {
        out.push_str(&format!("; {name}={value}"));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn client() -> Client {
        let _ = rustls::crypto::ring::default_provider().install_default();
        Client::builder()
            .no_proxy()
            .retry(reqwest::retry::never())
            .build()
            .unwrap()
    }
    fn metadata() -> Metadata {
        Metadata {
            profile_id: "profile".into(),
            profile_digest: format!("sha256:{}", "a".repeat(64)),
            route_id: "write".into(),
            auth_id: Some("api-key".into()),
            credential_headers: vec!["authorization".into()],
        }
    }
    #[test]
    fn effect_keys_survive_private_buffering_without_changing_request_facts() {
        let first = prepared("https://example.test/send", "{}", "alice", "credential")
            .with_effect_key(Some("charge:7"))
            .unwrap();
        let second = prepared("https://example.test/send", "{}", "alice", "credential")
            .with_effect_key(Some("receipt:7"))
            .unwrap();
        assert_eq!(first.digest(), second.digest());
        assert_eq!(first.manifest(), second.manifest());
        assert_eq!(first.effect_key(), Some("charge:7"));
        let wire = first.into_buffered("handler").unwrap();
        assert_eq!(wire.effect_key.as_deref(), Some("charge:7"));
        for key in ["", "a/b", "a b", "é"] {
            assert!(
                prepared("https://example.test/send", "{}", "alice", "credential")
                    .with_effect_key(Some(key))
                    .is_err()
            );
        }
        assert!(
            prepared("https://example.test/send", "{}", "alice", "credential")
                .with_effect_key(Some(&"x".repeat(129)))
                .is_err()
        );
    }
    fn prepared(url: &str, body: &str, recipient: &str, credential: &str) -> PreparedRequest {
        let client = client();
        let request = client
            .post(url)
            .header("authorization", credential)
            .header("x-recipient", recipient)
            .header("content-type", "Application/JSON; Charset=UTF-8")
            .body(body.to_owned())
            .build()
            .unwrap();
        PreparedRequest::new(&client, request, metadata()).unwrap()
    }
    #[test]
    fn binds_the_exact_owned_transport_without_credential_values() {
        let p = prepared(
            "https://EXAMPLE.test:443/a%20b?z=9&a=2&a=1",
            "{\"x\":1}",
            "alice",
            "secret-one",
        );
        assert_eq!(
            p.request.url().as_str(),
            "https://example.test/a%20b?a=2&a=1&z=9"
        );
        assert_eq!(
            p.request.headers()["content-type"],
            "application/json; charset=utf-8"
        );
        assert!(!p.manifest.to_string().contains("secret-one"));
        assert_eq!(
            p.digest(),
            prepared(
                "https://example.test/a%20b?a=2&a=1&z=9",
                "{\"x\":1}",
                "alice",
                "secret-two"
            )
            .digest()
        );
        for changed in [
            prepared(
                "https://example.test/a%20b?a=1&a=2&z=9",
                "{\"x\":1}",
                "alice",
                "x",
            ),
            prepared(
                "https://example.test/a%20b?a=2&a=1&z=9",
                "{\"x\":2}",
                "alice",
                "x",
            ),
            prepared(
                "https://example.test/a%20b?a=2&a=1&z=9",
                "{\"x\":1}",
                "bob",
                "x",
            ),
        ] {
            assert_ne!(p.digest(), changed.digest());
        }
    }
    #[test]
    fn only_the_excluded_service_credential_can_be_injected_after_binding() {
        let c = client();
        let mut meta = metadata();
        meta.auth_id = Some("toolgate-service-permit:olo-memory".into());
        meta.credential_headers = vec!["toolgate-service-permit".into()];
        let p = PreparedRequest::new(
            &c,
            c.post("https://example.test/write")
                .body("payload")
                .build()
                .unwrap(),
            meta,
        )
        .unwrap();
        let digest = p.digest().to_owned();
        let p = p.with_service_permit("signed-credential").unwrap();
        assert_eq!(p.digest(), digest);
        assert_eq!(
            p.request.headers()["toolgate-service-permit"],
            "signed-credential"
        );
        assert!(!p.manifest.to_string().contains("signed-credential"));
        assert!(p.with_service_permit("second").is_err());
        assert!(
            prepared("https://example.test", "payload", "alice", "secret")
                .with_service_permit("signed")
                .is_err()
        );
    }
    #[test]
    fn content_type_parameters_are_canonical_and_duplicates_are_refused() {
        assert_eq!(
            content_type("Multipart/Form-Data; Charset=UTF-8; boundary=\"A;B\"").unwrap(),
            "multipart/form-data; boundary=\"A;B\"; charset=utf-8"
        );
        for bad in [
            "text/plain; charset=a; CHARSET=b",
            "text/plain; boundary=\"unterminated",
            "text/plain; x=a b",
            "text/plain; x=\"a\r\nb\"",
        ] {
            assert!(content_type(bad).is_err());
        }
    }
    #[test]
    fn rejects_undeclared_credentials_and_url_authority_changes() {
        let client = client();
        let mut meta = metadata();
        meta.auth_id = None;
        meta.credential_headers.clear();
        assert!(PreparedRequest::new(
            &client,
            client
                .get("https://example.test")
                .header("authorization", "secret")
                .build()
                .unwrap(),
            meta
        )
        .is_err());
        assert!(PreparedRequest::new(
            &client,
            Request::new(
                reqwest::Method::GET,
                "https://user:pass@example.test/".parse().unwrap()
            ),
            metadata()
        )
        .is_err());
    }
    #[test]
    fn shared_http_binding_vectors_match_the_prepared_wire_request() {
        let data: Value = serde_json::from_str(include_str!(
            "../../../tests/fixtures/security/http-request-binding-v1.json"
        ))
        .unwrap();
        for vector in data["vectors"].as_array().unwrap() {
            let p = prepared(
                vector["url"].as_str().unwrap(),
                vector["body"].as_str().unwrap(),
                vector["recipient"].as_str().unwrap(),
                "fixture-auth-value",
            );
            assert_eq!(p.manifest, vector["manifest"], "{}", vector["name"]);
            assert_eq!(p.digest(), vector["digest"].as_str().unwrap());
        }
    }
    #[tokio::test]
    async fn sends_the_prepared_bytes_once_when_the_peer_drops_after_the_body() {
        use std::time::Duration;
        use tokio::io::AsyncReadExt;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let peer = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut data = Vec::new();
            let mut buf = [0u8; 1024];
            while !data.ends_with(b"payload") {
                let n = tokio::time::timeout(Duration::from_secs(2), socket.read(&mut buf))
                    .await
                    .unwrap()
                    .unwrap();
                assert!(n > 0);
                data.extend_from_slice(&buf[..n]);
                assert!(data.len() < 16384);
            }
            drop(socket);
            let replayed = tokio::time::timeout(Duration::from_millis(250), listener.accept())
                .await
                .is_ok();
            (String::from_utf8(data).unwrap(), replayed)
        });
        let p = prepared(
            &format!("http://{address}/items?z=3&a=2&a=1"),
            "payload",
            "alice",
            "fixture-value",
        );
        let digest = p.digest().to_owned();
        let manifest = p.manifest().clone();
        let key = olo_toolgate_contracts::v2::DownstreamEffectKey {
            header: "idempotency-key".into(),
            value: format!("tg-{}", "a".repeat(64)),
        };
        let p = p.with_downstream_key(Some(&key)).unwrap();
        assert_eq!(p.digest(), digest);
        assert_eq!(p.manifest(), &manifest);
        assert!(p
            .request
            .headers()
            .get("idempotency-key")
            .unwrap()
            .is_sensitive());
        assert!(p.send().await.is_err());
        let (wire, replayed) = peer.await.unwrap();
        assert!(!replayed);
        assert!(wire.starts_with("POST /items?a=2&a=1&z=3 HTTP/1.1\r\n"));
        assert!(wire.contains("content-type: application/json; charset=utf-8\r\n"));
        assert!(wire.ends_with("payload"));
        assert_eq!(wire.matches("idempotency-key:").count(), 1);
        assert!(wire
            .lines()
            .any(|line| line == format!("idempotency-key: {}", key.value)));
    }
    #[test]
    fn target_keys_cannot_replace_protocol_headers_or_caller_values() {
        for header in [
            "authorization",
            "content-encoding",
            "if-match",
            "trailer",
            "proxy-connection",
            "1-key",
        ] {
            let key = olo_toolgate_contracts::v2::DownstreamEffectKey {
                header: header.into(),
                value: format!("tg-{}", "a".repeat(64)),
            };
            assert!(
                prepared("https://example.test/send", "{}", "alice", "credential")
                    .with_downstream_key(Some(&key))
                    .is_err()
            );
        }
        let mut forged = prepared("https://example.test/send", "{}", "alice", "credential");
        forged.request.headers_mut().insert(
            "idempotency-key",
            header::HeaderValue::from_static("worker-choice"),
        );
        let key = olo_toolgate_contracts::v2::DownstreamEffectKey {
            header: "idempotency-key".into(),
            value: format!("tg-{}", "a".repeat(64)),
        };
        assert!(forged.with_downstream_key(Some(&key)).is_err());
    }
    #[test]
    fn target_permit_is_supervisor_only_sensitive_and_never_changes_bound_bytes() {
        let permit = olo_toolgate_contracts::v2::SignedEffectGrant {
            jws: "fixture.signed.grant".into(),
        };
        let p = prepared("https://example.test/send", "{}", "alice", "credential");
        let digest = p.digest().to_owned();
        let manifest = p.manifest().clone();
        let p = p.with_target_permit(Some(&permit)).unwrap();
        assert_eq!(p.digest(), digest);
        assert_eq!(p.manifest(), &manifest);
        assert_eq!(p.request.headers()["toolgate-effect-permit"], permit.jws);
        assert!(p.request.headers()["toolgate-effect-permit"].is_sensitive());
        assert!(p.with_target_permit(Some(&permit)).is_err());
        assert!(
            prepared("http://127.0.0.1/send", "{}", "alice", "credential")
                .with_target_permit(Some(&permit))
                .is_err()
        );
        let client = client();
        assert!(PreparedRequest::new(
            &client,
            client
                .post("https://example.test/send")
                .header("toolgate-effect-permit", "worker-selected")
                .build()
                .unwrap(),
            metadata()
        )
        .is_err());
        let mut meta = metadata();
        meta.credential_headers
            .push("toolgate-effect-permit".into());
        assert!(PreparedRequest::new(
            &client,
            client.post("https://example.test/send").build().unwrap(),
            meta
        )
        .is_err());
    }
}
