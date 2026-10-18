# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Mirrors the Java processor fixture OrdersPackage."""

from toolgate import AuthType, HttpMethod, destination, http_auth, http_route, tool_package

tool_package(id="acme.orders", name="Acme orders", publisher="Acme")

destination(id="acme-api", host_setting="AcmeApiHost", default_host="api.acme.com")
http_auth(id="acme-key", type=AuthType.API_KEY, header="X-Api-Key", secret="ApiKey")
http_auth(id="acme-oauth", type=AuthType.OAUTH2_CLIENT_CREDENTIALS, secret="AcmeClient",
          scopes="refunds:write", audience="https://api.acme.com")
http_route(id="orders.get", destination="acme-api", method=HttpMethod.GET, path="/orders/{orderId:[0-9]{6,12}}",
           query="since", headers="X-On-Behalf-Of", auth="acme-key")
http_route(id="orders.refund", destination="acme-api", method=HttpMethod.POST,
           path="/orders/{orderId:[0-9]{6,12}}/refunds", headers="Idempotency-Key", max_request_bytes=65536,
           max_response_bytes=65536, timeout_ms=15000, auth="acme-oauth")
