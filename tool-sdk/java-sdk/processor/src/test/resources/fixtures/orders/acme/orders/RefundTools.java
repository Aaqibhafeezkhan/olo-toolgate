// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.orders;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Idempotency;
import io.ololabs.toolgate.sdk.annotations.KeyNamespace;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.Reconcile;
import io.ololabs.toolgate.sdk.annotations.Secret;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.ToolContext;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Descriptor vector tool-03: non-ASCII names and UTF-16 key order. */
@Secret(name = "AcmeClient", type = ValueType.OBJECT)
public class RefundTools {
    public enum Reason { DAMAGED, LATE, ÉCHANGE }

    public record Refund(boolean zeta, String état, @Param(description = "", min = "0") long €amount,
            @Param(name = "😀note", description = "") String note, String Ａlpha) {}

    @ServerTool(name = "orders.refund", title = "Refund an order",
            description = "Refunds an order in full. Café orders are refunded to the original card; the amount is in €.",
            effect = EffectSafety.NON_IDEMPOTENT, destructive = true, async = true, stateless = true, maxConcurrency = 4,
            routes = "orders.refund", businessKeyNamespace = KeyNamespace.TENANT, businessKeyArgument = "refundKey",
            errors = {
                @ErrorDecl(code = "PSP_BUSY", description = "The payment provider asked us to retry", retryable = true),
                @ErrorDecl(code = "ALREADY_REFUNDED", description = "The order was already refunded")
            },
            toolsets = "payments", reconciliation = Reconciliation.DOWNSTREAM_IDEMPOTENCY)
    @Idempotency(header = "Idempotency-Key")
    public CompletionStage<Refund> refund(@Param(description = "Order number", pattern = "^[0-9]{6,12}$") String orderId,
            @Param(description = "Reason shown to the customer") Reason reason,
            @Param(description = "Business key for this refund", minLength = 8, maxLength = 64) String refundKey,
            ToolContext context) {
        return CompletableFuture.completedFuture(context.http().route("orders.refund").pathVar("orderId", orderId)
                .body(Map.of("reason", reason)).send(Refund.class));
    }

    @ServerTool(name = "orders.refund-status", title = "Check a refund", description = "Reconciles an uncertain refund.",
            effect = EffectSafety.READ_ONLY)
    @Reconcile(tool = "orders.refund")
    public Map<String, Object> refundStatus(@Param(description = "Order number") String orderId) {
        return Map.of();
    }
}
