// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.shop;

import io.ololabs.toolgate.sdk.annotations.AuthType;
import io.ololabs.toolgate.sdk.annotations.Destination;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.HttpAuth;
import io.ololabs.toolgate.sdk.annotations.HttpMethod;
import io.ololabs.toolgate.sdk.annotations.HttpRoute;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.Secret;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.RawResponse;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.UpstreamError;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Tools that call routes, for the routes HTTP client tests. */
@ToolPackage(id = "acme.shop", name = "Shop", publisher = "Acme")
@Destination(id = "shop-api", hostSetting = "ShopApiHost")
@HttpAuth(id = "shop-key", type = AuthType.API_KEY, header = "X-Api-Key", secret = "ShopKey")
@HttpAuth(id = "shop-basic", type = AuthType.BASIC, secret = "ShopBasic")
@HttpAuth(id = "shop-oauth", type = AuthType.OAUTH2_CLIENT_CREDENTIALS, secret = "ShopClient", scopes = "refunds:write",
        audience = "https://shop.test")
@HttpRoute(id = "orders.get", destination = "shop-api", method = HttpMethod.GET, path = "/orders/{orderId:[0-9]{6,12}}",
        query = "expand", headers = "X-Trace", auth = "shop-key", timeoutMs = 1000, maxResponseBytes = 4096)
@HttpRoute(id = "files.get", destination = "shop-api", method = HttpMethod.GET, path = "/files/{name:[a-z/]+}", auth = "shop-basic")
@HttpRoute(id = "orders.refund", destination = "shop-api", method = HttpMethod.POST, path = "/orders/{orderId:[0-9]{6,12}}/refunds",
        auth = "shop-oauth", timeoutMs = 1000)
@HttpRoute(id = "admin.get", destination = "shop-api", method = HttpMethod.GET, path = "/admin")
public class ShopTools {
    public record Order(String id, String expand, String trace, boolean keyed) {}

    @ServerTool(name = "shop.order", title = "Order", description = "Reads an order.", effect = EffectSafety.READ_ONLY, routes = "orders.get")
    @Secret(name = "ShopKey")
    public Map<String, Object> order(@Param(description = "Order") String id, @Param(description = "How") String how, ToolContext context) {
        var http = context.http();
        switch (how) {
            case "plain" -> {
                return Map.of("order", http.route("orders.get").pathVar("orderId", id).query("expand", "lines").query("expand", null)
                        .header("X-Trace", "t-1").send(Order.class));
            }
            case "convenience" -> {
                return Map.of("order", http.get("shop-api", "/orders/{orderId}").pathVar("orderId", id).send(Order.class));
            }
            case "raw" -> {
                RawResponse r = http.route("orders.get").pathVar("orderId", id).sendRaw();
                return Map.of("status", r.status(), "bytes", r.body().length);
            }
            case "long-timeout" -> {
                return Map.of("order", http.route("orders.get").pathVar("orderId", id).timeout(Duration.ofSeconds(30)).send(Order.class));
            }
            case "undeclared" -> {
                return Map.of("v", http.route("admin.get").send(String.class));
            }
            case "missing-var" -> {
                return Map.of("v", http.route("orders.get").send(String.class));
            }
            case "unlisted-query" -> {
                return Map.of("v", http.route("orders.get").pathVar("orderId", id).query("debug", "1").send(String.class));
            }
            case "caught" -> {
                try {
                    http.route("orders.get").pathVar("orderId", id).send(String.class);
                    return Map.of("caught", "none");
                } catch (UpstreamError e) {
                    return Map.of("caught", e.code(), "status", e.status().orElse(0), "uncertain", e.effectUncertain(),
                            "requestId", e.upstreamRequestId().orElse(""));
                }
            }
            default -> {
                return Map.of("v", http.route("orders.get").pathVar("orderId", id).header(how, "x").send(String.class));
            }
        }
    }

    @ServerTool(name = "shop.file", title = "File", description = "Reads a file.", effect = EffectSafety.READ_ONLY, routes = "files.get")
    @Secret(name = "ShopBasic", type = ValueType.OBJECT)
    public Map<String, Object> file(@Param(description = "Name") String name, ToolContext context) {
        return Map.of("text", context.http().route("files.get").pathVar("name", name).send(String.class));
    }

    @ServerTool(name = "shop.refund", title = "Refund", description = "Refunds an order.", effect = EffectSafety.NON_IDEMPOTENT,
            routes = "orders.refund")
    @Secret(name = "ShopClient", type = ValueType.OBJECT)
    public Map<String, Object> refund(@Param(description = "Order") String id, ToolContext context) {
        return context.http().route("orders.refund").pathVar("orderId", id).body(Map.of("reason", "LATE", "lines", List.of(1, 2)))
                .send(Map.class);
    }

    @ServerTool(name = "shop.refund-later", title = "Refund later", description = "Refunds an order as a task.",
            effect = EffectSafety.NON_IDEMPOTENT, async = true, routes = "orders.refund")
    @Secret(name = "ShopClient", type = ValueType.OBJECT)
    public Map<String, Object> refundLater(@Param(description = "Order") String id, ToolContext context) {
        return refund(id, context);
    }
}
