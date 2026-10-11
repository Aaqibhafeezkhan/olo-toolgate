// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.orders;

import io.ololabs.toolgate.sdk.annotations.AuthType;
import io.ololabs.toolgate.sdk.annotations.Destination;
import io.ololabs.toolgate.sdk.annotations.HttpAuth;
import io.ololabs.toolgate.sdk.annotations.HttpMethod;
import io.ololabs.toolgate.sdk.annotations.HttpRoute;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;

@ToolPackage(id = "acme.orders", name = "Acme orders", publisher = "Acme")
@Destination(id = "acme-api", hostSetting = "AcmeApiHost", defaultHost = "api.acme.com")
@HttpAuth(id = "acme-key", type = AuthType.API_KEY, header = "X-Api-Key", secret = "ApiKey")
@HttpAuth(id = "acme-oauth", type = AuthType.OAUTH2_CLIENT_CREDENTIALS, secret = "AcmeClient",
        scopes = "refunds:write", audience = "https://api.acme.com")
@HttpRoute(id = "orders.get", destination = "acme-api", method = HttpMethod.GET, path = "/orders/{orderId:[0-9]{6,12}}",
        query = "since", headers = "X-On-Behalf-Of", auth = "acme-key")
@HttpRoute(id = "orders.refund", destination = "acme-api", method = HttpMethod.POST, path = "/orders/{orderId}/refunds",
        headers = "Idempotency-Key", maxRequestBytes = 65536, maxResponseBytes = 65536, timeoutMs = 15000,
        auth = "acme-oauth")
public final class OrdersPackage {}
