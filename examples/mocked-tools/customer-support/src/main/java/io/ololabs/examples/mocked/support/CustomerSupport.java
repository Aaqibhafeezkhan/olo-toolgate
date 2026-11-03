// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.support;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ModelValidation;
import io.ololabs.toolgate.sdk.annotations.Training;
import io.ololabs.toolgate.sdk.annotations.TrainingPrompt;
import io.ololabs.toolgate.sdk.annotations.TrainingValues;
import io.ololabs.toolgate.sdk.annotations.ValuePhrasing;
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
    @Training(
            phrasings = {
                "Look up customer {idOrEmail}",
                "Who is {idOrEmail}?",
                "What tier is {idOrEmail} on?",
                "Find the customer with email {idOrEmail}",
                "Which orders does {idOrEmail} have?",
                "How long has {idOrEmail} been a customer?",
                "Who is customer {idOrEmail}?",
                "Look up {idOrEmail}",
                "Customer profile for {idOrEmail}",
                "What tier is {idOrEmail}?"
            },
            negatives = {
                "How do I handle an angry customer?",
                "What is customer lifetime value?"
            },
            parameters = @TrainingValues(name = "idOrEmail",
                    examples = {
                        "C-501",
                        "C-502",
                        "C-517",
                        "C-530",
                        "jane.doe@example.com",
                        "sam@example.org"
                    }),
            prompts = {
                @TrainingPrompt(prompt = "Find the customer with email jane.doe@example.com", arguments = "{\"idOrEmail\":\"jane.doe@example.com\"}"),
                @TrainingPrompt(prompt = "Find a customer with id or email C-502", arguments = "{\"idOrEmail\":\"C-502\"}"),
                @TrainingPrompt(prompt = "Which orders does C-530 have?", arguments = "{\"idOrEmail\":\"C-530\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "who's sam.lee@example.org in our system?", arguments = "{\"idOrEmail\":\"sam.lee@example.org\"}"),
                @TrainingPrompt(prompt = "How long has C-511 been a customer?", arguments = "{\"idOrEmail\":\"C-511\"}")
            },
            negatives = {
                "What makes a loyalty programme work?"
            })
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
    @Training(
            phrasings = {
                "Where is order {orderId}?",
                "What's the status of {orderId}?",
                "Tracking number for {orderId}?",
                "Is order {orderId} late?",
                "What's in order {orderId}?",
                "Which carrier has {orderId}?",
                "My order {orderId} hasn't arrived",
                "Show the items in {orderId}",
                "Has {orderId} shipped yet?",
                "Status of {orderId}"
            },
            negatives = {
                "How long does standard shipping usually take?",
                "What does 'in transit' mean?"
            },
            parameters = @TrainingValues(name = "orderId",
                    examples = {
                        "O-9002",
                        "O-9001",
                        "O-9015",
                        "O-9040"
                    }),
            prompts = {
                @TrainingPrompt(prompt = "Has O-9002 shipped yet?", arguments = "{\"orderId\":\"O-9002\"}"),
                @TrainingPrompt(prompt = "Tracking number for O-9040?", arguments = "{\"orderId\":\"O-9040\"}"),
                @TrainingPrompt(prompt = "Which carrier has O-9015?", arguments = "{\"orderId\":\"O-9015\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "where's my package, order O-9033", arguments = "{\"orderId\":\"O-9033\"}"),
                @TrainingPrompt(prompt = "What's in order O-9008 and when did it last move?", arguments = "{\"orderId\":\"O-9008\"}")
            },
            negatives = {
                "Do couriers deliver on Sundays?"
            })
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
    @Training(
            phrasings = {
                "Can {orderId} be refunded? The item is {reason}",
                "Is order {orderId} eligible for a refund because of {reason}?",
                "Customer wants money back on {orderId}: {reason}",
                "Check refund eligibility for {orderId}, reason {reason}",
                "How much can we refund on {orderId} if it was {reason}?",
                "Can {orderId} be refunded? Reason: {reason}",
                "Is {orderId} eligible for a refund, it was {reason}",
                "Refund check for {orderId}, {reason}"
            },
            negatives = "What's a fair refund policy for an online shop?",
            parameters = {
                @TrainingValues(name = "orderId",
                    examples = {
                        "O-9002",
                        "O-9001",
                        "O-9015",
                        "O-9040"
                    }),
                @TrainingValues(name = "reason",
                    phrasings = {
                        @ValuePhrasing(value = "DAMAGED", phrasings = {
                            "damaged",
                            "broken on arrival",
                            "arrived damaged"
                        }),
                        @ValuePhrasing(value = "NOT_RECEIVED", phrasings = {
                            "never received",
                            "not delivered",
                            "it never arrived"
                        }),
                        @ValuePhrasing(value = "WRONG_ITEM", phrasings = {
                            "the wrong item",
                            "not what they ordered",
                            "wrong item sent"
                        }),
                        @ValuePhrasing(value = "CHANGED_MIND", phrasings = {
                            "a change of mind",
                            "they changed their mind",
                            "no longer wanted"
                        }),
                        @ValuePhrasing(value = "LATE", phrasings = {
                            "late",
                            "delivered too late",
                            "much later than promised"
                        })
                    })
            },
            prompts = {
                @TrainingPrompt(prompt = "Can O-9002 be refunded? It arrived broken", arguments = "{\"orderId\":\"O-9002\",\"reason\":\"DAMAGED\"}"),
                @TrainingPrompt(prompt = "Is O-9001 eligible for a refund, it never arrived", arguments = "{\"orderId\":\"O-9001\",\"reason\":\"NOT_RECEIVED\"}"),
                @TrainingPrompt(prompt = "Refund check for O-9010, customer changed their mind", arguments = "{\"orderId\":\"O-9010\",\"reason\":\"CHANGED_MIND\"}"),
                @TrainingPrompt(prompt = "O-9020 came late, can we refund it?", arguments = "{\"orderId\":\"O-9020\",\"reason\":\"LATE\"}"),
                @TrainingPrompt(prompt = "They sent the wrong item for O-9004, refund possible?", arguments = "{\"orderId\":\"O-9004\",\"reason\":\"WRONG_ITEM\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "customer says O-9011 showed up smashed, do they get money back?", arguments = "{\"orderId\":\"O-9011\",\"reason\":\"DAMAGED\"}"),
                @TrainingPrompt(prompt = "O-9006 never got delivered, how much can we refund?", arguments = "{\"orderId\":\"O-9006\",\"reason\":\"NOT_RECEIVED\"}")
            },
            negatives = {
                "What is a typical refund policy for online stores?"
            })
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
            errors = @ErrorDecl(code = "NOT_FOUND", description = "No such customer or order."), reconciliation = Reconciliation.MANUAL)
    @Example(arguments = "{\"customerId\":\"C-501\",\"category\":\"DELIVERY\",\"subject\":\"Desk delayed\",\"description\":\"O-9002 is late\","
            + "\"reference\":\"chat-1\"}", expectedOutput = "{\"ticketId\":\"TCK-eaeb9111\",\"queue\":\"logistics\",\"firstResponseHours\":4}")
    @Training(
            phrasings = {
                "Open a {category} ticket for {customerId}: {subject}. {description} (ref {reference})",
                "Create a support ticket for {customerId}, category {category}, subject {subject}, details {description}, conversation {reference}",
                "Log a {category} issue for customer {customerId} titled {subject}: {description}. Reference {reference}",
                "File a ticket: customer {customerId}, {category}, {subject} - {description}, id {reference}"
            },
            negatives = {
                "How do I write a good support ticket?",
                "What's a reasonable SLA for support tickets?"
            },
            parameters = {
                @TrainingValues(name = "customerId",
                    examples = {
                        "C-501",
                        "C-502",
                        "C-517",
                        "C-530"
                    }),
                @TrainingValues(name = "subject",
                    examples = {
                        "Parcel not delivered",
                        "Refund request",
                        "Cannot log in",
                        "Item broken",
                        "Change delivery address"
                    }),
                @TrainingValues(name = "description",
                    examples = {
                        "The parcel shows delivered but nothing arrived",
                        "Customer wants a refund for a cracked mug",
                        "Password reset email never arrives",
                        "Wants to know when the jacket is back in stock"
                    }),
                @TrainingValues(name = "reference",
                    examples = {
                        "case-4471",
                        "req-20261031-01",
                        "conv-88213",
                        "ticket-flow-17",
                        "chat-5520",
                        "REQ-3307"
                    }),
                @TrainingValues(name = "category",
                    phrasings = {
                        @ValuePhrasing(value = "DELIVERY", phrasings = {
                            "delivery",
                            "shipping"
                        }),
                        @ValuePhrasing(value = "REFUND", phrasings = "refund"),
                        @ValuePhrasing(value = "PRODUCT", phrasings = "product"),
                        @ValuePhrasing(value = "ACCOUNT", phrasings = {
                            "account",
                            "login"
                        }),
                        @ValuePhrasing(value = "OTHER", phrasings = {
                            "general",
                            "other"
                        })
                    })
            },
            prompts = {
                @TrainingPrompt(prompt = "Create a support ticket for C-530, category shipping, subject \"Late parcel\", details \"Still not here after 10 days\", conversation chat-5520", arguments = "{\"customerId\":\"C-530\",\"category\":\"DELIVERY\",\"subject\":\"Late parcel\",\"description\":\"Still not here after 10 days\",\"reference\":\"chat-5520\"}"),
                @TrainingPrompt(prompt = "Log a login issue for customer C-502 titled Cannot sign in: \"Password reset email never arrives\". Reference chat-5521", arguments = "{\"customerId\":\"C-502\",\"category\":\"ACCOUNT\",\"subject\":\"Cannot sign in\",\"description\":\"Password reset email never arrives\",\"reference\":\"chat-5521\"}"),
                @TrainingPrompt(prompt = "Open a refund ticket for C-501, subject Refund request, details Charged twice, ref case-4471", arguments = "{\"customerId\":\"C-501\",\"category\":\"REFUND\",\"subject\":\"Refund request\",\"description\":\"Charged twice\",\"reference\":\"case-4471\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "raise a product ticket for C-544: subject Blender noisy, details Makes a grinding sound, ref chat-9001", arguments = "{\"customerId\":\"C-544\",\"category\":\"PRODUCT\",\"subject\":\"Blender noisy\",\"description\":\"Makes a grinding sound\",\"reference\":\"chat-9001\"}")
            },
            negatives = {
                "How should I word a support ticket?"
            })
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
