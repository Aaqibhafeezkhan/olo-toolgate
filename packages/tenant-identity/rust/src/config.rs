// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
use crate::{Failure, Result};
pub fn origin(value: &str) -> Result<String> {
    let url = reqwest::Url::parse(value).map_err(|_| Failure::Validation)?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/"
    {
        return Err(Failure::Validation);
    }
    let host = url.host_str().ok_or(Failure::Validation)?;
    if host == "169.254.169.254"
        || host == "metadata.google.internal"
        || host == "0.0.0.0"
        || host == "::"
    {
        return Err(Failure::Validation);
    }
    Ok(url.as_str().trim_end_matches('/').to_owned())
}
