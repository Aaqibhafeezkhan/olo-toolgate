// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.fraud;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A mocked card-fraud desk: alerts, transactions, an explainable risk score, and freezing a card. Fictional, fixed
 * data. The score is a small set of named rules rather than a black box, so an analyst agent can explain exactly why
 * a transaction looks risky.
 *
 * <p>Account ACC-77 (card ending 4242, normally spends in Lisbon). Transactions: T-1 a usual grocery payment, T-2 a
 * large electronics purchase in another country from a new device minutes after T-1, T-3 one of several rapid gift
 * card purchases.
 */
@ToolPackage(id = "examples.fraud", name = "Fraud detection (mocked)", publisher = "ToolGate Examples",
        description = "Mocked card fraud: alerts, transactions, explainable risk scores and card freezes.")
public class FraudDetection {
    public record Transaction(
            @Param(description = "Transaction id") String transactionId,
            @Param(description = "Account") String accountId,
            @Param(description = "Card, last 4 digits") String cardLast4,
            @Param(description = "Amount") double amount,
            @Param(description = "Currency") String currency,
            @Param(description = "Merchant") String merchant,
            @Param(description = "Merchant category") String category,
            @Param(description = "Country, ISO 3166 alpha-2") String country,
            @Param(description = "When, ISO 8601") String at,
            @Param(description = "True when the device has not been seen on this account before") boolean newDevice,
            @Param(description = "Card transactions in the 10 minutes before this one") int priorTenMinutes) {}

    record Profile(String homeCountry, double averageAmount) {}

    static final class Data {
        static final Map<String, Profile> PROFILES = Map.of("ACC-77", new Profile("PT", 64.0));
        static final Map<String, Transaction> TRANSACTIONS = Map.of(
                "T-1", new Transaction("T-1", "ACC-77", "4242", 58.30, "EUR", "Pingo Doce", "GROCERY", "PT", "2026-10-12T09:41:00Z", false, 0),
                "T-2", new Transaction("T-2", "ACC-77", "4242", 2399.00, "EUR", "TechnoMax Online", "ELECTRONICS", "RO", "2026-10-12T09:52:00Z", true, 1),
                "T-3", new Transaction("T-3", "ACC-77", "4242", 200.00, "EUR", "GiftCardHub", "GIFT_CARDS", "RO", "2026-10-12T09:58:00Z", true, 4));
    }

    public record Alerts(@Param(description = "Transactions flagged for review, riskiest first") List<Alert> alerts) {}

    public record Alert(
            @Param(description = "Transaction id") String transactionId,
            @Param(description = "Risk score, 0 to 100") int score,
            @Param(description = "Merchant and amount") String summary) {}

    @ServerTool(name = "alerts", title = "Fraud alerts",
            description = "Lists transactions whose risk score is at or above a threshold, riskiest first.",
            effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"minScore\":70}", expectedOutput = "{\"alerts\":[{\"transactionId\":\"T-3\",\"score\":100,"
            + "\"summary\":\"GiftCardHub, 200.00 EUR\"},{\"transactionId\":\"T-2\",\"score\":75,\"summary\":\"TechnoMax Online, 2399.00 EUR\"}]}")
    public Alerts alerts(@Param(description = "Lowest score to include", defaultValue = "60", min = "0", max = "100") int minScore,
            ToolContext context) {
        return new Alerts(Data.TRANSACTIONS.values().stream()
                .map(t -> new Alert(t.transactionId(), score(t).score(), t.merchant() + ", " + String.format(Locale.ROOT, "%.2f", t.amount()) + " " + t.currency()))
                .filter(a -> a.score() >= minScore)
                .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                .toList());
    }

    @ServerTool(name = "transaction", title = "Get a transaction",
            description = "Returns a card transaction's details: amount, merchant, category, country, device and velocity.",
            effect = EffectSafety.READ_ONLY, errors = @ErrorDecl(code = "NOT_FOUND", description = "No transaction or account has that id."))
    public Transaction transaction(@Param(description = "Transaction id, such as T-2", maxLength = 20) String transactionId, ToolContext context) {
        return find(transactionId);
    }

    public record Signal(
            @Param(description = "Rule that fired") String rule,
            @Param(description = "Points it added") int points,
            @Param(description = "What was seen") String detail) {}

    public record RiskScore(
            @Param(description = "Transaction id") String transactionId,
            @Param(description = "Risk score, 0 to 100") int score,
            @Param(description = "LOW, MEDIUM, HIGH or CRITICAL") String level,
            @Param(description = "The rules that fired, biggest first") List<Signal> signals,
            @Param(description = "APPROVE, STEP_UP_AUTH (ask the customer to confirm), HOLD or FREEZE_CARD") String recommendedAction) {}

    @ServerTool(name = "risk-score", title = "Score a transaction",
            description = "Scores a transaction's fraud risk from named rules (amount against the customer's average,"
                    + " foreign country, new device, velocity, high-risk category) and recommends an action.",
            useWhen = "Use it to explain why a transaction was flagged before deciding what to do.",
            effect = EffectSafety.READ_ONLY, errors = @ErrorDecl(code = "NOT_FOUND", description = "No transaction or account has that id."))
    @Example(arguments = "{\"transactionId\":\"T-1\"}", expectedOutput = "{\"transactionId\":\"T-1\",\"score\":0,\"level\":\"LOW\","
            + "\"signals\":[],\"recommendedAction\":\"APPROVE\"}")
    public RiskScore riskScore(@Param(description = "Transaction id", maxLength = 20) String transactionId, ToolContext context) {
        return score(find(transactionId));
    }

    static RiskScore score(Transaction t) {
        Profile p = Data.PROFILES.get(t.accountId());
        List<Signal> signals = new ArrayList<>();
        double ratio = t.amount() / p.averageAmount();
        if (ratio >= 10) signals.add(new Signal("AMOUNT_SPIKE", 30, String.format(Locale.ROOT, "%.0fx the customer's average of %.2f", ratio, p.averageAmount())));
        else if (ratio >= 3) signals.add(new Signal("AMOUNT_ABOVE_USUAL", 15, String.format(Locale.ROOT, "%.1fx the customer's average", ratio)));
        if (!t.country().equals(p.homeCountry())) signals.add(new Signal("FOREIGN_COUNTRY", 25, t.country() + ", customer usually in " + p.homeCountry()));
        if (t.newDevice()) signals.add(new Signal("NEW_DEVICE", 20, "Device not seen on this account before"));
        if (t.priorTenMinutes() >= 3) signals.add(new Signal("VELOCITY", 25, t.priorTenMinutes() + " card transactions in the previous 10 minutes"));
        if (List.of("GIFT_CARDS", "CRYPTO", "MONEY_TRANSFER").contains(t.category())) signals.add(new Signal("HIGH_RISK_CATEGORY", 25, t.category()));
        signals.sort((a, b) -> Integer.compare(b.points(), a.points()));
        int score = Math.min(100, signals.stream().mapToInt(Signal::points).sum());
        String level = score >= 90 ? "CRITICAL" : score >= 70 ? "HIGH" : score >= 40 ? "MEDIUM" : "LOW";
        String action = switch (level) {
            case "CRITICAL" -> "FREEZE_CARD";
            case "HIGH" -> "HOLD";
            case "MEDIUM" -> "STEP_UP_AUTH";
            default -> "APPROVE";
        };
        return new RiskScore(t.transactionId(), score, level, signals, action);
    }

    public record Freeze(
            @Param(description = "Case id") String caseId,
            @Param(description = "Card, last 4 digits") String cardLast4,
            @Param(description = "FROZEN") String status,
            @Param(description = "What the customer is told") String customerNotice) {}

    @ServerTool(name = "freeze-card", title = "Freeze a card",
            description = "Freezes a card so new transactions are declined (mocked: nothing changes) and opens a fraud"
                    + " case. A repeat with the same reference returns the first case.",
            useWhen = "Use it for CRITICAL risk, or when the analyst or customer confirms fraud.",
            effect = EffectSafety.NON_IDEMPOTENT, businessKeyArgument = "reference",
            errors = @ErrorDecl(code = "NOT_FOUND", description = "No transaction or account has that id."), reconciliation = Reconciliation.MANUAL)
    @Example(arguments = "{\"transactionId\":\"T-404\",\"reason\":\"x\",\"reference\":\"r\"}", expectedError = "NOT_FOUND")
    public Freeze freezeCard(
            @Param(description = "The suspicious transaction", maxLength = 20) String transactionId,
            @Param(description = "Why", minLength = 1, maxLength = 500) String reason,
            @Param(description = "Your case or alert id; a repeat with the same reference freezes once", minLength = 1, maxLength = 64)
            String reference,
            ToolContext context) {
        Transaction t = find(transactionId);
        return new Freeze("FC-" + shortHash(reference), t.cardLast4(), "FROZEN",
                "We've paused your card ending " + t.cardLast4() + " after unusual activity. Please confirm recent purchases in the app.");
    }

    static Transaction find(String id) {
        Transaction t = Data.TRANSACTIONS.get(id.trim().toUpperCase(Locale.ROOT));
        if (t == null) throw new ToolBusinessError("NOT_FOUND", "No transaction has that id. Mocked transactions are T-1 to T-3.");
        return t;
    }

    static String shortHash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
