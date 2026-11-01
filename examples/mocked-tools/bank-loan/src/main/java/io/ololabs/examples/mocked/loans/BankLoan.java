// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.loans;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.Training;
import io.ololabs.toolgate.sdk.annotations.TrainingValues;
import io.ololabs.toolgate.sdk.annotations.ValuePhrasing;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * A mocked retail loan desk: applicant profiles, credit reports, an underwriting decision with reasons, an EMI offer,
 * and recording the decision. All people and numbers are fictional and fixed, so an agent flow (look up, evaluate,
 * explain, record) can be built and demonstrated without a core-banking system. Replace {@link Data} with calls to
 * your real systems and keep the tool contracts.
 *
 * <p>Applicants: A-1001 (strong, approved), A-1002 (high debt, referred), A-1003 (recent default, declined),
 * A-1004 (new self-employed, referred).
 */
@ToolPackage(id = "examples.loans", name = "Bank loan approval (mocked)", publisher = "ToolGate Examples",
        description = "Mocked loan origination: applicants, credit reports, underwriting decisions and EMI offers.")
public class BankLoan {
    public record Applicant(
            @Param(description = "Applicant id") String applicantId,
            @Param(description = "Full name") String name,
            @Param(description = "SALARIED or SELF_EMPLOYED") String employmentType,
            @Param(description = "Employer or business") String employer,
            @Param(description = "Years in current employment") double yearsEmployed,
            @Param(description = "Net monthly income") double monthlyIncome,
            @Param(description = "Existing monthly debt payments") double monthlyDebt,
            @Param(description = "Currency") String currency) {}

    public record CreditReport(
            @Param(description = "Applicant id") String applicantId,
            @Param(description = "Credit score, 300 to 900") int score,
            @Param(description = "EXCELLENT, GOOD, FAIR or POOR") String band,
            @Param(description = "Open credit lines") int openAccounts,
            @Param(description = "Revolving credit used, percent") int utilizationPercent,
            @Param(description = "Hard inquiries in the last 6 months") int recentInquiries,
            @Param(description = "Payments 30+ days late in the last 24 months") int latePayments,
            @Param(description = "True when a loan defaulted in the last 3 years") boolean recentDefault) {}

    static final class Data {
        static final Map<String, Applicant> APPLICANTS = Map.of(
                "A-1001", new Applicant("A-1001", "Priya Sharma", "SALARIED", "Northwind Analytics", 6.0, 9500, 1200, "USD"),
                "A-1002", new Applicant("A-1002", "Daniel Okafor", "SALARIED", "Contoso Logistics", 3.5, 6200, 2300, "USD"),
                "A-1003", new Applicant("A-1003", "Marta Lindqvist", "SALARIED", "Fabrikam Retail", 2.0, 4800, 900, "USD"),
                "A-1004", new Applicant("A-1004", "Kenji Watanabe", "SELF_EMPLOYED", "Watanabe Design Studio", 0.8, 7100, 400, "USD"));
        static final Map<String, CreditReport> REPORTS = Map.of(
                "A-1001", new CreditReport("A-1001", 782, "EXCELLENT", 5, 18, 1, 0, false),
                "A-1002", new CreditReport("A-1002", 668, "FAIR", 9, 64, 4, 1, false),
                "A-1003", new CreditReport("A-1003", 541, "POOR", 3, 91, 2, 5, true),
                "A-1004", new CreditReport("A-1004", 721, "GOOD", 2, 22, 0, 0, false));
    }

    static final String UNKNOWN = "No applicant has that id. Mocked applicants are A-1001 to A-1004.";

    @ServerTool(name = "applicant", title = "Get applicant",
            description = "Returns a loan applicant's profile: employment, income, existing debt payments.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "APPLICANT_NOT_FOUND", description = "No applicant has that id."))
    @Example(arguments = "{\"applicantId\":\"A-1001\"}", expectedOutput = "{\"applicantId\":\"A-1001\",\"name\":\"Priya Sharma\","
            + "\"employmentType\":\"SALARIED\",\"employer\":\"Northwind Analytics\",\"yearsEmployed\":6.0,\"monthlyIncome\":9500.0,"
            + "\"monthlyDebt\":1200.0,\"currency\":\"USD\"}")
    @Example(arguments = "{\"applicantId\":\"A-9999\"}", expectedError = "APPLICANT_NOT_FOUND")
    @Training(
            phrasings = {
                "Show me applicant {applicantId}'s profile",
                "What does {applicantId} earn?",
                "Pull up loan applicant {applicantId}",
                "Employment and income for {applicantId}?",
                "What existing debt payments does {applicantId} have?",
                "Who is applicant {applicantId}?",
                "Look up {applicantId}"
            },
            negatives = {
                "What documents do I need to apply for a loan?",
                "How is debt-to-income ratio calculated?"
            },
            parameters = @TrainingValues(name = "applicantId",
                    examples = {
                        "A-1001",
                        "A-1002",
                        "A-1003",
                        "A-1017",
                        "A-1024"
                    }))
    public Applicant applicant(@Param(description = "Applicant id, such as A-1001", maxLength = 20) String applicantId,
            ToolContext context) {
        return find(Data.APPLICANTS, applicantId);
    }

    @ServerTool(name = "credit-report", title = "Get credit report",
            description = "Returns the applicant's credit bureau summary: score, band, utilisation, late payments, defaults.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "APPLICANT_NOT_FOUND", description = "No applicant has that id."))
    @Example(arguments = "{\"applicantId\":\"A-1003\"}", expectedOutput = "{\"applicantId\":\"A-1003\",\"score\":541,\"band\":\"POOR\","
            + "\"openAccounts\":3,\"utilizationPercent\":91,\"recentInquiries\":2,\"latePayments\":5,\"recentDefault\":true}")
    @Training(
            phrasings = {
                "What's the credit score of {applicantId}?",
                "Get the credit report for {applicantId}",
                "Any late payments or defaults for {applicantId}?",
                "Credit bureau summary for applicant {applicantId}",
                "How much of their credit does {applicantId} use?",
                "Check {applicantId}'s credit"
            },
            negatives = {
                "How can I improve my credit score?",
                "What is a good credit utilisation?"
            },
            parameters = @TrainingValues(name = "applicantId",
                    examples = {
                        "A-1001",
                        "A-1002",
                        "A-1003",
                        "A-1017",
                        "A-1024"
                    }))
    public CreditReport creditReport(@Param(description = "Applicant id, such as A-1001", maxLength = 20) String applicantId,
            ToolContext context) {
        return find(Data.REPORTS, applicantId);
    }

    public record Decision(
            @Param(description = "APPROVE, REFER (to a human underwriter) or DECLINE") String decision,
            @Param(description = "Why, one reason per line, most important first") List<String> reasons,
            @Param(description = "Annual interest rate offered, percent; 0 when declined") double annualRatePercent,
            @Param(description = "Monthly instalment for the requested amount") double monthlyInstalment,
            @Param(description = "Debt-to-income ratio including the new instalment, percent") double debtToIncomePercent,
            @Param(description = "Largest amount that keeps debt-to-income at 40% for this term") double maxApprovableAmount) {}

    @ServerTool(name = "evaluate", title = "Evaluate a loan",
            description = "Underwrites a requested loan for an applicant with the bank's (mocked) rules and returns"
                    + " APPROVE, REFER or DECLINE with reasons, rate, instalment, debt-to-income and the most that could"
                    + " be approved.",
            useWhen = "Use it after reading the applicant and credit report, then explain the reasons to the user.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "APPLICANT_NOT_FOUND", description = "No applicant has that id."))
    @Example(arguments = "{\"applicantId\":\"A-1003\",\"amount\":10000,\"termMonths\":24,\"purpose\":\"PERSONAL\"}",
            expectedOutput = "{\"decision\":\"DECLINE\",\"reasons\":[\"A loan defaulted in the last 3 years.\","
                    + "\"Credit score 541 is below the minimum of 600.\"],\"annualRatePercent\":0.0,\"monthlyInstalment\":0.0,"
                    + "\"debtToIncomePercent\":18.75,\"maxApprovableAmount\":0.0}")
    @Training(
            phrasings = {
                "Can {applicantId} get {amount} over {termMonths} months for {purpose}?",
                "Underwrite {amount} for {applicantId}, {termMonths} months, {purpose}",
                "Evaluate a {purpose} loan of {amount} for {applicantId} over {termMonths} months",
                "Should we approve {applicantId} for {amount} ({purpose}, {termMonths} months)?",
                "Run the loan rules: applicant {applicantId}, {amount}, {termMonths} months, purpose {purpose}",
                "{applicantId} wants {amount} for {purpose}, paid back over {termMonths} months. Approve, refer or decline?"
            },
            negatives = {
                "What's the difference between a secured and an unsecured loan?",
                "Is it better to take a 3 or 5 year car loan?"
            },
            parameters = {
                @TrainingValues(name = "applicantId",
                    examples = {
                        "A-1001",
                        "A-1002",
                        "A-1003",
                        "A-1017",
                        "A-1024"
                    }),
                @TrainingValues(name = "amount",
                    examples = {
                        "5000",
                        "12000",
                        "25000",
                        "40000",
                        "150000",
                        "8000"
                    }),
                @TrainingValues(name = "termMonths",
                    examples = {
                        "12",
                        "24",
                        "36",
                        "48",
                        "60",
                        "120",
                        "240"
                    }),
                @TrainingValues(name = "purpose",
                    phrasings = {
                        @ValuePhrasing(value = "PERSONAL", phrasings = {
                            "a personal loan",
                            "personal use"
                        }),
                        @ValuePhrasing(value = "HOME_IMPROVEMENT", phrasings = {
                            "home improvement",
                            "a kitchen renovation",
                            "fixing up the house"
                        }),
                        @ValuePhrasing(value = "VEHICLE", phrasings = {
                            "a car",
                            "a vehicle",
                            "buying a new car"
                        }),
                        @ValuePhrasing(value = "EDUCATION", phrasings = {
                            "education",
                            "tuition",
                            "a master's degree"
                        }),
                        @ValuePhrasing(value = "DEBT_CONSOLIDATION", phrasings = {
                            "debt consolidation",
                            "consolidating credit cards"
                        })
                    })
            })
    public Decision evaluate(
            @Param(description = "Applicant id", maxLength = 20) String applicantId,
            @Param(description = "Requested amount", min = "1000", max = "1000000") double amount,
            @Param(description = "Term in months", min = "6", max = "360") int termMonths,
            @Param(description = "What the loan is for", enumValues = {"PERSONAL", "HOME_IMPROVEMENT", "VEHICLE", "EDUCATION", "DEBT_CONSOLIDATION"})
            String purpose,
            ToolContext context) {
        Applicant a = find(Data.APPLICANTS, applicantId);
        CreditReport c = find(Data.REPORTS, applicantId);
        double rate = rateFor(c.score());
        double emi = rate == 0 ? 0 : emi(amount, rate, termMonths);
        double dti = round(100 * (a.monthlyDebt() + emi) / a.monthlyIncome());
        double maxEmi = Math.max(0, 0.40 * a.monthlyIncome() - a.monthlyDebt());
        double maxAmount = rate == 0 ? 0 : Math.floor(principalFor(maxEmi, rate, termMonths) / 100) * 100;

        List<String> decline = new ArrayList<>();
        List<String> refer = new ArrayList<>();
        if (c.recentDefault()) decline.add("A loan defaulted in the last 3 years.");
        if (c.score() < 600) decline.add("Credit score " + c.score() + " is below the minimum of 600.");
        if (rate > 0 && dti > 50) decline.add("Debt-to-income would be " + dti + "%, above the 50% limit.");
        if (c.score() >= 600 && c.score() < 680) refer.add("Credit score " + c.score() + " is in the FAIR band (600-679).");
        if (rate > 0 && dti > 40 && dti <= 50) refer.add("Debt-to-income would be " + dti + "%, above the 40% guideline.");
        if (c.utilizationPercent() > 60) refer.add("Revolving utilisation is " + c.utilizationPercent() + "%.");
        if (a.yearsEmployed() < 1) refer.add("Less than a year in current employment (" + a.yearsEmployed() + " years).");
        if (a.employmentType().equals("SELF_EMPLOYED")) refer.add("Self-employed income needs two years of tax returns.");

        if (!decline.isEmpty()) {
            return new Decision("DECLINE", decline, 0, 0, round(100 * a.monthlyDebt() / a.monthlyIncome()), 0);
        }
        if (!refer.isEmpty()) return new Decision("REFER", refer, rate, emi, dti, maxAmount);
        return new Decision("APPROVE", List.of("Credit score " + c.score() + " (" + c.band() + ").",
                "Debt-to-income would be " + dti + "%, within the 40% guideline.",
                a.yearsEmployed() + " years with " + a.employer() + "."), rate, emi, dti, maxAmount);
    }

    public record Offer(
            @Param(description = "Monthly instalment (EMI)") double monthlyInstalment,
            @Param(description = "Total interest over the term") double totalInterest,
            @Param(description = "Total of all instalments") double totalPayable,
            @Param(description = "The first instalments: month, interest, principal, balance after") List<Instalment> firstInstalments) {}

    public record Instalment(
            @Param(description = "Month number") int month,
            @Param(description = "Interest part") double interest,
            @Param(description = "Principal part") double principal,
            @Param(description = "Balance after the payment") double balance) {}

    @ServerTool(name = "offer", title = "Calculate an offer",
            description = "Calculates the monthly instalment (EMI), total interest and the first instalments of an"
                    + " amortising loan.", effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"amount\":100000,\"termMonths\":12,\"annualRatePercent\":10,\"show\":1}",
            expectedOutput = "{\"monthlyInstalment\":8791.59,\"totalInterest\":5499.08,\"totalPayable\":105499.08,"
                    + "\"firstInstalments\":[{\"month\":1,\"interest\":833.33,\"principal\":7958.26,\"balance\":92041.74}]}")
    @Training(
            phrasings = {
                "What's the EMI on {amount} over {termMonths} months at {annualRatePercent}%?",
                "Monthly payment for {amount} at {annualRatePercent} percent for {termMonths} months",
                "Calculate the instalment: {amount}, {annualRatePercent}% a year, {termMonths} months",
                "How much interest on {amount} over {termMonths} months at {annualRatePercent}%? Show the first {show} instalments",
                "Amortisation for {amount} at {annualRatePercent}% over {termMonths} months, first {show} payments",
                "If I borrow {amount} for {termMonths} months at {annualRatePercent}%, what do I pay monthly?"
            },
            defaultPhrasings = "EMI for {amount} over {termMonths} months at {annualRatePercent}%?",
            negatives = {
                "What does EMI stand for?",
                "Are interest rates going to fall next year?"
            },
            parameters = {
                @TrainingValues(name = "amount",
                    examples = {
                        "5000",
                        "10000",
                        "25000",
                        "250000",
                        "1500000",
                        "75000"
                    }),
                @TrainingValues(name = "termMonths",
                    examples = {
                        "12",
                        "24",
                        "36",
                        "60",
                        "84",
                        "180",
                        "240",
                        "360"
                    }),
                @TrainingValues(name = "annualRatePercent",
                    examples = {
                        "6.5",
                        "8.9",
                        "10.25",
                        "12",
                        "14.5",
                        "3.75"
                    }),
                @TrainingValues(name = "show",
                    examples = {
                        "0",
                        "1",
                        "5",
                        "6",
                        "12"
                    })
            })
    public Offer offer(
            @Param(description = "Loan amount", min = "1", max = "100000000") double amount,
            @Param(description = "Term in months", min = "1", max = "480") int termMonths,
            @Param(description = "Annual interest rate, percent", min = "0", max = "60") double annualRatePercent,
            @Param(description = "How many first instalments to list", defaultValue = "3", min = "0", max = "24") int show,
            ToolContext context) {
        double emi = annualRatePercent == 0 ? round(amount / termMonths) : emi(amount, annualRatePercent, termMonths);
        double monthly = annualRatePercent / 12 / 100;
        double balance = amount;
        List<Instalment> first = new ArrayList<>();
        for (int m = 1; m <= Math.min(show, termMonths); m++) {
            double interest = round(balance * monthly);
            double principal = round(emi - interest);
            balance = round(balance - principal);
            first.add(new Instalment(m, interest, principal, balance));
        }
        double total = round(emi * termMonths);
        return new Offer(emi, round(total - amount), total, first);
    }

    public record Recorded(
            @Param(description = "Decision record id") String decisionId,
            @Param(description = "Applicant id") String applicantId,
            @Param(description = "The decision recorded") String decision,
            @Param(description = "Amount") double amount,
            @Param(description = "What happens next") String nextStep) {}

    @ServerTool(name = "record-decision", title = "Record a decision",
            description = "Records the final loan decision for an applicant (mocked: nothing is stored). Recording again"
                    + " with the same reference returns the first record.",
            useWhen = "Use it only after the user confirms the decision.",
            effect = EffectSafety.NON_IDEMPOTENT, businessKeyArgument = "reference",
            errors = @ErrorDecl(code = "APPLICANT_NOT_FOUND", description = "No applicant has that id."), reconciliation = Reconciliation.MANUAL)
    @Example(arguments = "{\"applicantId\":\"A-1001\",\"decision\":\"APPROVE\",\"amount\":25000,\"reference\":\"case-77\"}",
            expectedOutput = "{\"decisionId\":\"LD-f706ae1c\",\"applicantId\":\"A-1001\",\"decision\":\"APPROVE\",\"amount\":25000.0,"
                    + "\"nextStep\":\"Offer letter queued for e-signature.\"}")
    @Training(
            phrasings = {
                "Record {decision} for {applicantId}, {amount}, reference {reference}",
                "Log the final decision for {applicantId}: {decision} at {amount} (ref {reference})",
                "We {decision} {applicantId} for {amount}. Record it under {reference}",
                "Save the decision {decision} on applicant {applicantId}, amount {amount}, case {reference}"
            },
            negatives = "How do I tell a customer their loan was declined?",
            parameters = {
                @TrainingValues(name = "applicantId",
                    examples = {
                        "A-1001",
                        "A-1002",
                        "A-1003",
                        "A-1017",
                        "A-1024"
                    }),
                @TrainingValues(name = "amount",
                    examples = {
                        "0",
                        "5000",
                        "12000",
                        "25000",
                        "40000"
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
                @TrainingValues(name = "decision",
                    phrasings = {
                        @ValuePhrasing(value = "APPROVE", phrasings = {
                            "APPROVE",
                            "approved",
                            "approve"
                        }),
                        @ValuePhrasing(value = "REFER", phrasings = {
                            "REFER",
                            "referred",
                            "refer to a human underwriter"
                        }),
                        @ValuePhrasing(value = "DECLINE", phrasings = {
                            "DECLINE",
                            "declined",
                            "decline"
                        })
                    })
            })
    public Recorded recordDecision(
            @Param(description = "Applicant id", maxLength = 20) String applicantId,
            @Param(description = "The decision", enumValues = {"APPROVE", "REFER", "DECLINE"}) String decision,
            @Param(description = "Amount decided on", min = "0", max = "1000000") double amount,
            @Param(description = "Your case or request id; a repeat with the same reference is not recorded twice",
                    minLength = 1, maxLength = 64) String reference,
            ToolContext context) {
        find(Data.APPLICANTS, applicantId);
        String next = switch (decision) {
            case "APPROVE" -> "Offer letter queued for e-signature.";
            case "REFER" -> "Case placed in the underwriting queue.";
            default -> "Adverse action notice queued to the applicant.";
        };
        return new Recorded("LD-" + shortHash(reference), applicantId, decision, amount, next);
    }

    // ---- helpers ----

    static <T> T find(Map<String, T> data, String id) {
        T value = data.get(id.trim().toUpperCase(java.util.Locale.ROOT));
        if (value == null) throw new ToolBusinessError("APPLICANT_NOT_FOUND", UNKNOWN);
        return value;
    }

    static double rateFor(int score) {
        if (score >= 750) return 8.5;
        if (score >= 700) return 10.0;
        if (score >= 600) return 12.5;
        return 0;
    }

    static double emi(double principal, double annualRate, int months) {
        double r = annualRate / 12 / 100;
        double f = Math.pow(1 + r, months);
        return round(principal * r * f / (f - 1));
    }

    static double principalFor(double emi, double annualRate, int months) {
        double r = annualRate / 12 / 100;
        double f = Math.pow(1 + r, months);
        return emi * (f - 1) / (r * f);
    }

    static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    static String shortHash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
