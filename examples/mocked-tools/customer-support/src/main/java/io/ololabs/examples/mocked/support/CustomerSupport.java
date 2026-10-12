// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.support;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A mocked e-commerce support desk: customers, order tracking, refund eligibility under a written policy, and support
 * tickets. Fictional, fixed data (the store's "today" is 2026-10-12), so a support agent can be built and tested
 * end to end without a CRM or order system.
 *
 * <p>Customers C-501 (gold) and C-502 (standard). Orders: O-9001 delivered, O-9002 in transit and late, O-9003
 * delivered 45 days ago (outside the refund window), O-9004 delivered with a damaged item.
 */
@ToolPackage(id = "examples.support", name = "Customer support (mocked)", publisher = "ToolGate Examples",
        description = "Mocked customer support: customers, order tracking, refund eligibility and tickets.")
public class CustomerSupport {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    static final int REFUND_WINDOW_DAYS = 30;

    public record Customer(
            @Param(description = "Customer id") String customerId,
            @Param(description = "Name") String name,
            @Param(description = "Email") String email,
            @Param(description = "STANDARD, SILVER or GOLD") String tier,
            @Param(description = "Customer since, ISO 8601") String since,
            @Param(description = "Orders placed") int orders,
            @Param(description = "Their order ids, newest first") List<String> orderIds) {}

    public record Item(
            @Param(description = "Product") String product,
            @Param(description = "Quantity") int quantity,
            @Param(description = "Price each") double price) {}

    public record Order(
            @Param(description = "Order id") String orderId,
            @Param(description = "Customer id") String customerId,
            @Param(description = "PROCESSING, SHIPPED, IN_TRANSIT, DELIVERED or CANCELLED") String status,
            @Param(description = "Items") List<Item> items,
            @Param(description = "Order total") double total,
            @Param(description = "Ordered on, ISO 8601") String orderedOn,
            @Param(description = "Carrier") String carrier,
            @Param(description = "Tracking number") String tracking,
            @Param(description = "Promised delivery date, ISO 8601") String promisedBy,
            @Param(description = "Delivered on, ISO 8601, or empty") String deliveredOn,
            @Param(description = "Latest carrier event") String lastEvent) {}

    static final class Data {
        static final Map<String, Customer> CUSTOMERS = Map.of(
                "C-501", new Customer("C-501", "Ana Costa", "ana.costa@example.com", "GOLD", "2019-05-11", 42,
                        List.of("O-9004", "O-9002", "O-9001")),
                "C-502", new Customer("C-502", "Ben Carter", "ben.carter@example.net", "STANDARD", "2025-12-01", 3,
                        List.of("O-9003")));
        static final Map<String, Order> ORDERS = Map.of(
                "O-9001", new Order("O-9001", "C-501", "DELIVERED", List.of(new Item("Noise-cancelling headphones", 1, 199.00)),
                        199.00, "2026-09-28", "UPS", "1Z999AA10123456784", "2026-10-02", "2026-10-01", "Delivered, front door"),
                "O-9002", new Order("O-9002", "C-501", "IN_TRANSIT", List.of(new Item("Standing desk", 1, 549.00),
                        new Item("Desk mat", 2, 24.50)), 598.00, "2026-10-03", "DHL", "JD014600006281234567", "2026-10-09", "",
                        "Held at regional hub (weather delay), 2026-10-10"),
                "O-9003", new Order("O-9003", "C-502", "DELIVERED", List.of(new Item("Coffee grinder", 1, 89.90)),
                        89.90, "2026-08-24", "USPS", "9400111899223197428490", "2026-08-29", "2026-08-28", "Delivered, mailbox"),
                "O-9004", new Order("O-9004", "C-501", "DELIVERED", List.of(new Item("Ceramic vase set", 1, 75.00)),
                        75.00, "2026-10-04", "UPS", "1Z999AA10123456799", "2026-10-08", "2026-10-07", "Delivered, reception"));
    }

    static ToolBusinessError notFound(String what) {
        return new ToolBusinessError("NOT_FOUND", "No " + what + ". Mocked customers are C-501 and C-502; orders O-9001 to O-9004.");
    }

    @ServerTool(name = "customer", title = "Find a customer",
            description = "Finds a customer by id or email and returns their tier, tenure and order ids.",
            effect = EffectSafety.READ_ONLY, errors = @ErrorDecl(code = "NOT_FOUND", description = "No such customer or order."))
    @Example(arguments = "{\"idOrEmail\":\"BEN.CARTER@example.net\"}", expectedOutput = "{\"customerId\":\"C-502\",\"name\":\"Ben Carter\","
            + "\"email\":\"ben.carter@example.net\",\"tier\":\"STANDARD\",\"since\":\"2025-12-01\",\"orders\":3,\"orderIds\":[\"O-9003\"]}")
    public Customer customer(@Param(description = "Customer id (C-501) or email", maxLength = 200, sensitive = true) String idOrEmail,
            ToolContext context) {
        String key = idOrEmail.trim().toLowerCase(Locale.ROOT);
        return Data.CUSTOMERS.values().stream()
                .filter(c -> c.customerId().equalsIgnoreCase(key) || c.email().equals(key))
                .findFirst().orElseThrow(() -> notFound("customer with that id or email"));
    }

    public record OrderStatus(
            @Param(description = "The order") Order order,
            @Param(description = "True when it is past its promised date and not delivered") boolean late,
            @Param(description = "Days late; 0 when on time") long daysLate) {}

    @ServerTool(name = "order", title = "Track an order",
            description = "Returns an order's items, status, carrier, tracking number and latest event, and whether it is late.",
            effect = EffectSafety.READ_ONLY, errors = @ErrorDecl(code = "NOT_FOUND", description = "No such customer or order."))
    public OrderStatus order(@Param(description = "Order id, such as O-9002", maxLength = 20) String orderId, ToolContext context) {
        Order o = findOrder(orderId);
        LocalDate promised = LocalDate.parse(o.promisedBy());
        LocalDate arrived = o.deliveredOn().isEmpty() ? TODAY : LocalDate.parse(o.deliveredOn());
        long late = Math.max(0, ChronoUnit.DAYS.between(promised, arrived));
        return new OrderStatus(o, late > 0 && o.deliveredOn().isEmpty(), late);
    }

    public record RefundDecision(
            @Param(description = "True when the order qualifies") boolean eligible,
            @Param(description = "Amount that can be refunded") double amount,
            @Param(description = "ORIGINAL_PAYMENT, STORE_CREDIT or NONE") String method,
            @Param(description = "Whether the item must be sent back first") boolean returnRequired,
            @Param(description = "The policy rule applied") String policy) {}

    @ServerTool(name = "refund-eligibility", title = "Check refund eligibility",
            description = "Checks whether an order qualifies for a refund under the store's (mocked) policy, and for how much.",
            useWhen = "Use it before promising a customer any refund.",
            effect = EffectSafety.READ_ONLY, errors = @ErrorDecl(code = "NOT_FOUND", description = "No such customer or order."))
    @Example(arguments = "{\"orderId\":\"O-9003\",\"reason\":\"CHANGED_MIND\"}", expectedOutput = "{\"eligible\":false,\"amount\":0.0,"
            + "\"method\":\"NONE\",\"returnRequired\":false,\"policy\":\"Change-of-mind returns are accepted within 30 days of delivery;"
            + " this order was delivered 45 days ago.\"}")
    public RefundDecision refundEligibility(
            @Param(description = "Order id", maxLength = 20) String orderId,
            @Param(description = "Why the customer wants a refund", enumValues = {"DAMAGED", "NOT_RECEIVED", "WRONG_ITEM", "CHANGED_MIND", "LATE"})
            String reason,
            ToolContext context) {
        Order o = findOrder(orderId);
        boolean gold = Data.CUSTOMERS.get(o.customerId()).tier().equals("GOLD");
        long sinceDelivery = o.deliveredOn().isEmpty() ? 0 : ChronoUnit.DAYS.between(LocalDate.parse(o.deliveredOn()), TODAY);
        return switch (reason) {
            case "DAMAGED", "WRONG_ITEM" -> o.deliveredOn().isEmpty()
                    ? new RefundDecision(false, 0, "NONE", false, "Damaged or wrong items are refunded once delivered.")
                    : new RefundDecision(true, o.total(), "ORIGINAL_PAYMENT", false,
                            "Damaged or wrong items are refunded in full without a return; a photo is enough.");
            case "NOT_RECEIVED", "LATE" -> {
                long late = order(orderId, null).daysLate();
                if (!o.deliveredOn().isEmpty()) {
                    yield new RefundDecision(false, 0, "NONE", false, "The carrier shows this order delivered; open a carrier trace instead.");
                }
                if (late >= 3) yield new RefundDecision(true, o.total(), "ORIGINAL_PAYMENT", false,
                        "Orders 3 or more days past their promised date can be refunded; the parcel is then recalled.");
                yield new RefundDecision(gold && late > 0, gold && late > 0 ? round(o.total() * 0.10) : 0,
                        gold && late > 0 ? "STORE_CREDIT" : "NONE", false,
                        "Under 3 days late: gold customers get 10% store credit as an apology; otherwise wait for delivery.");
            }
            default -> sinceDelivery <= REFUND_WINDOW_DAYS && !o.deliveredOn().isEmpty()
                    ? new RefundDecision(true, o.total(), "ORIGINAL_PAYMENT", true,
                            "Change-of-mind returns are accepted within 30 days of delivery, item unused.")
                    : new RefundDecision(false, 0, "NONE", false, "Change-of-mind returns are accepted within 30 days of delivery;"
                            + " this order was delivered " + sinceDelivery + " days ago.");
        };
    }

    public record Ticket(
            @Param(description = "Ticket id") String ticketId,
            @Param(description = "Queue it was routed to") String queue,
            @Param(description = "First response due within, hours") int firstResponseHours) {}

    @ServerTool(name = "create-ticket", title = "Create a support ticket",
            description = "Opens a support ticket for a customer (mocked: nothing is stored). A repeat with the same"
                    + " reference returns the first ticket.",
            effect = EffectSafety.NON_IDEMPOTENT, businessKeyArgument = "reference",
            errors = @ErrorDecl(code = "NOT_FOUND", description = "No such customer or order."))
    @Example(arguments = "{\"customerId\":\"C-501\",\"category\":\"DELIVERY\",\"subject\":\"Desk delayed\",\"description\":\"O-9002 is late\","
            + "\"reference\":\"chat-1\"}", expectedOutput = "{\"ticketId\":\"TCK-eaeb9111\",\"queue\":\"logistics\",\"firstResponseHours\":4}")
    public Ticket createTicket(
            @Param(description = "Customer id", maxLength = 20) String customerId,
            @Param(description = "Category", enumValues = {"DELIVERY", "REFUND", "PRODUCT", "ACCOUNT", "OTHER"}) String category,
            @Param(description = "Subject", minLength = 1, maxLength = 200) String subject,
            @Param(description = "What the customer needs", minLength = 1, maxLength = 4000) String description,
            @Param(description = "Your conversation or request id; a repeat with the same reference opens one ticket",
                    minLength = 1, maxLength = 64) String reference,
            ToolContext context) {
        Customer c = Data.CUSTOMERS.get(customerId.trim().toUpperCase(Locale.ROOT));
        if (c == null) throw notFound("customer with that id");
        String queue = switch (category) {
            case "DELIVERY" -> "logistics";
            case "REFUND" -> "billing";
            case "ACCOUNT" -> "accounts";
            default -> "general";
        };
        return new Ticket("TCK-" + shortHash(reference), queue, c.tier().equals("GOLD") ? 4 : 24);
    }

    static Order findOrder(String id) {
        Order o = Data.ORDERS.get(id.trim().toUpperCase(Locale.ROOT));
        if (o == null) throw notFound("order with that id");
        return o;
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static String shortHash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
