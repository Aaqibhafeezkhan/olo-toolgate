// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.orders;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.JwtClaim;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.Secret;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.ToolContext;
import java.util.List;

/** Descriptor vector tool-02. */
@Global(name = "TimeoutSeconds", type = ValueType.INT, defaultValue = "10")
@Secret(name = "ApiKey")
@JwtClaim(name = "UserName", claim = "preferred_username")
public class LookupTools {
    public enum Status { OPEN, SHIPPED, CANCELLED }

    public record Item(String sku, int quantity) {}

    public record Order(String orderId, Status status, List<Item> items) {}

    @ServerTool(name = "orders.lookup", title = "Look up an order", description = "Returns status and items for one order.",
            useWhen = "The user asks about a specific order number.", effect = EffectSafety.READ_ONLY,
            routes = "orders.get", errors = @ErrorDecl(code = "ORDER_NOT_FOUND", description = "No such order"))
    public Order lookup(@Param(description = "Order number", pattern = "^[0-9]{6,12}$") String orderId,
            @Param(description = "Only events after this epoch second", required = false) Integer since,
            ToolContext context) {
        return context.http().route("orders.get").pathVar("orderId", orderId).query("since", since).send(Order.class);
    }
}
