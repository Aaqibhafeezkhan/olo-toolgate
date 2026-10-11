// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.orders;

import io.ololabs.toolgate.sdk.annotations.AuthType;
import io.ololabs.toolgate.sdk.annotations.Destination;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.HttpAuth;
import io.ololabs.toolgate.sdk.annotations.HttpMethod;
import io.ololabs.toolgate.sdk.annotations.HttpRoute;
import io.ololabs.toolgate.sdk.annotations.JwtClaim;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.Secret;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import java.util.Map;

/** Tools for the ToolHarness tests. */
@ToolPackage(id = "acme.orders", name = "Orders", publisher = "Acme")
@Destination(id = "orders-api", hostSetting = "OrdersApiHost")
@HttpAuth(id = "orders-key", type = AuthType.API_KEY, header = "X-Api-Key", secret = "ApiKey")
@HttpRoute(id = "orders.get", destination = "orders-api", method = HttpMethod.GET, path = "/orders/{orderId:[0-9]+}",
        query = "expand", auth = "orders-key")
@HttpRoute(id = "orders.cancel", destination = "orders-api", method = HttpMethod.POST, path = "/orders/{orderId:[0-9]+}/cancel",
        requestContentTypes = "application/json")
@Global(name = "Limit", type = ValueType.INT, defaultValue = "3")
@Secret(name = "ApiKey")
public class OrderTools {
    public record Order(String id, String status) {}

    @ServerTool(name = "orders.get", title = "Order", description = "Reads an order.", effect = EffectSafety.READ_ONLY,
            routes = "orders.get", errors = @ErrorDecl(code = "NOT_FOUND", description = "No such order"))
    @Example(arguments = "{\"orderId\":\"7\"}", expectedOutput = "{\"id\":\"7\",\"status\":\"OPEN\",\"limit\":3}",
            exchanges = "exchanges/order-7.json")
    @Example(arguments = "{\"orderId\":\"8\"}", expectedError = "NOT_FOUND", exchanges = "exchanges/order-7.json")
    @Example(arguments = "{\"orderId\":\"9\"}")
    public Map<String, Object> get(@Param(description = "Order id") String orderId, ToolContext context) {
        if (orderId.equals("8")) throw new ToolBusinessError("NOT_FOUND", "No order 8");
        Order order = context.http().get("orders-api", "/orders/{orderId}").pathVar("orderId", orderId).send(Order.class);
        return Map.of("id", order.id(), "status", order.status(), "limit", OrderToolsVars.global(context).limit());
    }

    @ServerTool(name = "orders.cancel", title = "Cancel", description = "Cancels an order.", effect = EffectSafety.IDEMPOTENT,
            routes = "orders.cancel", async = true)
    @JwtClaim(name = "sub", claim = "sub")
    public Map<String, Object> cancel(@Param(description = "Order id") String orderId, ToolContext context) {
        context.http().route("orders.cancel").pathVar("orderId", orderId).body(Map.of("by", OrderToolsVars.jwt(context).sub())).send(Void.class);
        return Map.of("cancelled", orderId);
    }
}
