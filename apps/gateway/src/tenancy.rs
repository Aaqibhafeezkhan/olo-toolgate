// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Each Gateway is pinned to one tenant; Host and an expected UUID never select credentials.
use axum::http::{HeaderMap, Uri};
use olo_toolgate_contracts::ErrorCode;

pub fn canonical_uuid(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(i, b)| {
            if [8, 13, 18, 23].contains(&i) {
                b == b'-'
            } else {
                b.is_ascii_digit() || (b'a'..=b'f').contains(&b)
            }
        })
}
pub fn authority(value: &str) -> Result<String, ErrorCode> {
    if value.is_empty()
        || value.len() > 260
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b".-:[]".contains(&b))
    {
        return Err(ErrorCode::Validation);
    }
    let (host, port) = if let Some(rest) = value.strip_prefix('[') {
        let end = rest.find(']').ok_or(ErrorCode::Validation)?;
        let host = &rest[..end];
        let suffix = &rest[end + 1..];
        if host.parse::<std::net::Ipv6Addr>().is_err()
            || (!suffix.is_empty() && !suffix.starts_with(':'))
        {
            return Err(ErrorCode::Validation);
        }
        (
            format!("[{host}]"),
            if suffix.is_empty() {
                None
            } else {
                Some(&suffix[1..])
            },
        )
    } else {
        let (host, port) = value
            .split_once(':')
            .map(|(host, port)| (host, Some(port)))
            .unwrap_or((value, None));
        let host = host.strip_suffix('.').unwrap_or(host);
        if host.bytes().all(|b| b.is_ascii_digit() || b == b'.')
            && host
                .parse::<std::net::Ipv4Addr>()
                .map_or(true, |ip| ip.to_string() != host)
        {
            return Err(ErrorCode::Validation);
        }
        (host.to_owned(), port)
    };
    if port.is_some_and(|port| {
        port.is_empty()
            || port.len() > 5
            || port.starts_with('0')
            || !port.bytes().all(|b| b.is_ascii_digit())
            || port.parse::<u16>().is_err()
    }) {
        return Err(ErrorCode::Validation);
    }
    let normalized = match port {
        Some(port) => format!("{host}:{port}"),
        None => host,
    };
    let url =
        reqwest::Url::parse(&format!("https://{normalized}")).map_err(|_| ErrorCode::Validation)?;
    if url.host_str().is_none()
        || url.port() == Some(0)
        || !url.username().is_empty()
        || url.password().is_some()
        || url.path() != "/"
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(ErrorCode::Validation);
    }
    let host = url.host_str().ok_or(ErrorCode::Validation)?;
    if !host.contains(':')
        && (host.len() > 253
            || host.split('.').any(|label| {
                label.is_empty()
                    || label.len() > 63
                    || label.starts_with('-')
                    || label.ends_with('-')
            }))
    {
        return Err(ErrorCode::Validation);
    }
    Ok(url
        .origin()
        .ascii_serialization()
        .trim_start_matches("https://")
        .to_owned())
}
pub fn check(
    headers: &HeaderMap,
    uri: &Uri,
    hosts: &[String],
    tenant: &str,
) -> Result<(), ErrorCode> {
    let values = headers.get_all("host").iter().collect::<Vec<_>>();
    if values.len() > 1 {
        return Err(ErrorCode::MisdirectedRequest);
    }
    let host = values
        .first()
        .map(|value| value.to_str().map_err(|_| ErrorCode::Validation))
        .transpose()?
        .map(authority)
        .transpose()?;
    let absolute = uri
        .authority()
        .map(|value| authority(value.as_str()))
        .transpose()?;
    if host.is_some() && absolute.is_some() && host != absolute {
        return Err(ErrorCode::MisdirectedRequest);
    }
    let selected = host.or(absolute).ok_or(ErrorCode::Validation)?;
    if !hosts
        .iter()
        .any(|value| authority(value).is_ok_and(|host| host == selected))
    {
        return Err(ErrorCode::TenantUnavailable);
    }
    let expected = headers
        .get_all("toolgate-tenant-id")
        .iter()
        .collect::<Vec<_>>();
    if expected.len() > 1 {
        return Err(ErrorCode::InvalidTenantId);
    }
    if let Some(value) = expected.first() {
        let expected = value.to_str().map_err(|_| ErrorCode::InvalidTenantId)?;
        if !canonical_uuid(expected) {
            return Err(ErrorCode::InvalidTenantId);
        }
        if expected != tenant {
            return Err(ErrorCode::TenantMismatch);
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn authority_is_canonical_and_rejects_ambiguous_hosts_and_ports() {
        assert_eq!(
            authority("TENANT.example.test.:443"),
            Ok("tenant.example.test".into())
        );
        assert_eq!(authority("[::1]:8443"), Ok("[::1]:8443".into()));
        for value in [
            "",
            "tenant:0",
            "tenant:0443",
            "tenant:65536",
            "127.1",
            "0127.0.0.1",
            "tenant..example.test",
            "user@tenant",
            "https://tenant",
            "tenant/path",
            "tenant,evil",
            "::1",
            "[localhost]",
            "tenant%2eexample.test",
        ] {
            assert!(authority(value).is_err(), "{value}");
        }
    }
    #[test]
    fn duplicate_headers_fail_and_unregistered_hosts_never_inspect_the_asserted_tenant() {
        let tenant = "10000000-0000-4000-8000-000000000001";
        let hosts = vec!["tenant.example.test".into()];
        let uri = "/mcp".parse().unwrap();
        let mut headers = HeaderMap::new();
        headers.insert("host", "tenant.example.test".parse().unwrap());
        assert_eq!(check(&headers, &uri, &hosts, tenant), Ok(()));
        headers.append("host", "tenant.example.test".parse().unwrap());
        assert_eq!(
            check(&headers, &uri, &hosts, tenant),
            Err(ErrorCode::MisdirectedRequest)
        );
        headers.remove("host");
        headers.insert("host", "other.example.test".parse().unwrap());
        headers.insert("toolgate-tenant-id", "default".parse().unwrap());
        assert_eq!(
            check(&headers, &uri, &hosts, tenant),
            Err(ErrorCode::TenantUnavailable)
        );
        headers.insert("host", "tenant.example.test".parse().unwrap());
        headers.insert("toolgate-tenant-id", tenant.parse().unwrap());
        headers.append("toolgate-tenant-id", tenant.parse().unwrap());
        assert_eq!(
            check(&headers, &uri, &hosts, tenant),
            Err(ErrorCode::InvalidTenantId)
        );
    }
}
