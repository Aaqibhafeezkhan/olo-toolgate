// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.ledger;

import io.ololabs.toolgate.sdk.annotations.Destination;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.HttpMethod;
import io.ololabs.toolgate.sdk.annotations.HttpRoute;
import io.ololabs.toolgate.sdk.annotations.Idempotency;
import io.ololabs.toolgate.sdk.annotations.IdempotencyScope;
import io.ololabs.toolgate.sdk.annotations.KeyNamespace;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.InputRequest;
import io.ololabs.toolgate.sdk.context.ToolContext;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Tools that need business keys, for the reservation tests. */
@ToolPackage(id = "acme.ledger", name = "Ledger", publisher = "Acme")
@Destination(id = "ledger-api", hostSetting = "LedgerApiHost")
@HttpRoute(id = "payments.create", destination = "ledger-api", method = HttpMethod.POST, path = "/payments", headers = "Idempotency-Key")
public class LedgerTools {
    /** How many times each handler body ran, across the test. */
    public static final Map<String, AtomicInteger> RUNS = new java.util.concurrent.ConcurrentHashMap<>();

    private static int ran(String tool) {
        return RUNS.computeIfAbsent(tool, k -> new AtomicInteger()).incrementAndGet();
    }

    public record Answer(String ok) {}

    @ServerTool(name = "ledger.refund", title = "Refund", description = "Refunds once per key.", effect = EffectSafety.NON_IDEMPOTENT,
            destructive = true, businessKeyArgument = "refundKey", reconciliation = Reconciliation.MANUAL)
    public Map<String, Object> refund(@Param(description = "Order") String orderId, @Param(description = "Key") String refundKey,
            ToolContext context) {
        return Map.of("run", ran("refund"), "orderId", orderId, "key", context.invocation().businessKey().orElse(""));
    }

    @ServerTool(name = "ledger.transfer", title = "Transfer", description = "Tenant-wide key.", effect = EffectSafety.NON_IDEMPOTENT,
            destructive = true, businessKeyNamespace = KeyNamespace.TENANT, businessKeyArgument = "ref", reconciliation = Reconciliation.MANUAL)
    public Map<String, Object> transfer(@Param(description = "Ref") String ref) {
        return Map.of("run", ran("transfer"));
    }

    @ServerTool(name = "ledger.expense", title = "Expense", description = "Per user.", effect = EffectSafety.NON_IDEMPOTENT,
            destructive = true, businessKeyNamespace = KeyNamespace.DELEGATED_USER, businessKeyArgument = "ref", reconciliation = Reconciliation.MANUAL)
    public Map<String, Object> expense(@Param(description = "Ref") String ref) {
        return Map.of("run", ran("expense"));
    }

    @ServerTool(name = "ledger.pay", title = "Pay", description = "Forwards its key upstream.", effect = EffectSafety.NON_IDEMPOTENT,
            destructive = true, businessKeyArgument = "ref", routes = "payments.create", reconciliation = Reconciliation.DOWNSTREAM_IDEMPOTENCY)
    @Idempotency(header = "Idempotency-Key", scope = IdempotencyScope.BUSINESS_KEY)
    public Map<String, Object> pay(@Param(description = "Ref") String ref, ToolContext context) {
        return context.http().route("payments.create").body(Map.of("amount", 5)).send(Map.class);
    }

    @ServerTool(name = "ledger.charge", title = "Charge", description = "Forwards a per-invocation key.", effect = EffectSafety.IDEMPOTENT,
            routes = "payments.create", reconciliation = Reconciliation.DOWNSTREAM_IDEMPOTENCY)
    @Idempotency(header = "Idempotency-Key", scope = IdempotencyScope.PER_INVOCATION)
    public Map<String, Object> charge(ToolContext context) {
        return context.http().route("payments.create").body(Map.of("amount", 1)).send(Map.class);
    }

    @ServerTool(name = "ledger.void", title = "Void", description = "Key only in the header.", effect = EffectSafety.NON_IDEMPOTENT,
            destructive = true, reconciliation = Reconciliation.MANUAL)
    public Map<String, Object> voidIt(ToolContext context) {
        return Map.of("run", ran("void"), "key", context.invocation().businessKey().orElse(""));
    }

    @ServerTool(name = "ledger.slow", title = "Slow", description = "Runs a while.", effect = EffectSafety.NON_IDEMPOTENT,
            destructive = true, businessKeyArgument = "ref", reconciliation = Reconciliation.MANUAL)
    public Map<String, Object> slow(@Param(description = "Ref") String ref) throws InterruptedException {
        Thread.sleep(800);
        return Map.of("run", ran("slow"));
    }

    @ServerTool(name = "ledger.export", title = "Export", description = "A task per key.", effect = EffectSafety.NON_IDEMPOTENT,
            destructive = true, async = true, businessKeyArgument = "ref", reconciliation = Reconciliation.MANUAL)
    public Map<String, Object> export(@Param(description = "Ref") String ref) throws InterruptedException {
        Thread.sleep(300);
        return Map.of("run", ran("export"));
    }

    @ServerTool(name = "ledger.confirm", title = "Confirm", description = "Asks first.", effect = EffectSafety.NON_IDEMPOTENT,
            destructive = true, businessKeyArgument = "ref", reconciliation = Reconciliation.MANUAL)
    public Map<String, Object> confirm(@Param(description = "Ref") String ref, ToolContext context) {
        Answer a = context.requestInput(InputRequest.of("Sure?", Map.of("type", "object", "properties", Map.of("ok", Map.of("type", "string")),
                "required", java.util.List.of("ok")), Answer.class));
        return Map.of("run", ran("confirm"), "ok", a.ok());
    }
}
