// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

/** How a route authenticates (http.md §5). */
public enum AuthType { API_KEY, BASIC, OAUTH2_CLIENT_CREDENTIALS, TOKEN_EXCHANGE, CONNECTION, CUSTOM }
