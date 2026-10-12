// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.claims;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A mocked insurance claims desk (first notice of loss): look up a policy, check a claim, assess whether a loss is
 * covered and what it would pay, and file a claim. Fictional, fixed data.
 *
 * <p>Policies: POL-2001 home (covers storm, fire, theft, burst pipes; excludes flood), POL-2002 motor (collision,
 * theft, glass), POL-2003 a home policy that lapsed on 2026-06-30. Claim CLM-3001 is open on POL-2002.
 */
@ToolPackage(id = "examples.claims", name = "Insurance claims (mocked)", publisher = "ToolGate Examples",
        description = "Mocked insurance claims: policies, claim status, coverage assessment and filing a claim.")
public class InsuranceClaims {
    public record Policy(
            @Param(description = "Policy id") String policyId,
            @Param(description = "Policy holder") String holder,
            @Param(description = "HOME or MOTOR") String product,
            @Param(description = "ACTIVE or LAPSED") String status,
            @Param(description = "Cover starts, ISO date") String startDate,
            @Param(description = "Cover ends, ISO date") String endDate,
            @Param(description = "Perils covered") List<String> covered,
            @Param(description = "Perils excluded") List<String> excluded,
            @Param(description = "Most the policy pays per claim") double limit,
            @Param(description = "Amount the holder pays first on each claim") double deductible,
            @Param(description = "Currency") String currency) {}

    public record Claim(
            @Param(description = "Claim id") String claimId,
            @Param(description = "Policy id") String policyId,
            @Param(description = "Peril") String peril,
            @Param(description = "Incident date") String incidentDate,
            @Param(description = "OPEN, IN_REVIEW, APPROVED, PAID or REJECTED") String status,
            @Param(description = "Adjuster assigned") String adjuster,
            @Param(description = "Documents still missing") List<String> missingDocuments,
            @Param(description = "Next step") String nextStep) {}

    static final class Data {
        static final Map<String, Policy> POLICIES = Map.of(
                "POL-2001", new Policy("POL-2001", "Maria Okafor", "HOME", "ACTIVE", "2026-01-15", "2027-01-14",
                        List.of("STORM", "FIRE", "THEFT", "WATER_BURST"), List.of("FLOOD", "WEAR_AND_TEAR"), 250000, 1000, "USD"),
                "POL-2002", new Policy("POL-2002", "Daniel Kim", "MOTOR", "ACTIVE", "2026-03-01", "2027-02-28",
                        List.of("COLLISION", "THEFT", "GLASS"), List.of("RACING", "WEAR_AND_TEAR"), 40000, 500, "USD"),
                "POL-2003", new Policy("POL-2003", "Lena Fischer", "HOME", "LAPSED", "2025-07-01", "2026-06-30",
                        List.of("STORM", "FIRE", "THEFT"), List.of("FLOOD"), 180000, 1500, "USD"));
        static final Map<String, Claim> CLAIMS = Map.of(
                "CLM-3001", new Claim("CLM-3001", "POL-2002", "COLLISION", "2026-09-28", "IN_REVIEW", "J. Alvarez",
                        List.of("Repair estimate"), "Upload the garage's repair estimate so the adjuster can approve."));
        static final Map<String, List<String>> DOCUMENTS = Map.of(
                "STORM", List.of("Photos of the damage", "Repair estimate"),
                "FIRE", List.of("Fire service report", "Photos of the damage", "Repair estimate"),
                "THEFT", List.of("Police report number", "Proof of ownership or receipts"),
                "WATER_BURST", List.of("Plumber's report", "Photos of the damage"),
                "COLLISION", List.of("Photos of the vehicles", "Other driver's details", "Repair estimate"),
                "GLASS", List.of("Photo of the damage"));
        /** Claims at or under this payout skip the adjuster. */
        static final double FAST_TRACK_LIMIT = 5000;
    }

    @ServerTool(name = "policy", title = "Get a policy",
            description = "Returns a policy: holder, product, status, cover dates, covered and excluded perils, limit and deductible.",
            effect = EffectSafety.READ_ONLY, errors = @ErrorDecl(code = "POLICY_NOT_FOUND", description = "No policy has that id."))
    @Example(arguments = "{\"policyId\":\"POL-9999\"}", expectedError = "POLICY_NOT_FOUND")
    public Policy policy(@Param(description = "Policy id, such as POL-2001", maxLength = 20) String policyId, ToolContext context) {
        return findPolicy(policyId);
    }

    @ServerTool(name = "claim", title = "Get claim status",
            description = "Returns a claim's status, adjuster, missing documents and next step.",
            effect = EffectSafety.READ_ONLY, errors = @ErrorDecl(code = "CLAIM_NOT_FOUND", description = "No claim has that id."))
    @Example(arguments = "{\"claimId\":\"CLM-3001\"}", expectedOutput = "{\"claimId\":\"CLM-3001\",\"policyId\":\"POL-2002\","
            + "\"peril\":\"COLLISION\",\"incidentDate\":\"2026-09-28\",\"status\":\"IN_REVIEW\",\"adjuster\":\"J. Alvarez\","
            + "\"missingDocuments\":[\"Repair estimate\"],\"nextStep\":\"Upload the garage's repair estimate so the adjuster can approve.\"}")
    public Claim claim(@Param(description = "Claim id, such as CLM-3001", maxLength = 20) String claimId, ToolContext context) {
        Claim c = Data.CLAIMS.get(claimId.trim().toUpperCase(Locale.ROOT));
        if (c == null) throw new ToolBusinessError("CLAIM_NOT_FOUND", "No claim has that id. The mocked claim is CLM-3001.");
        return c;
    }

    public record Assessment(
            @Param(description = "Policy id") String policyId,
            @Param(description = "Peril assessed") String peril,
            @Param(description = "True when the policy covers this loss") boolean covered,
            @Param(description = "Why it is or is not covered") String reason,
            @Param(description = "Loss claimed") double lossAmount,
            @Param(description = "Deductible taken off") double deductible,
            @Param(description = "Estimated payout: the loss up to the limit, less the deductible; 0 when not covered") double payout,
            @Param(description = "Documents the claim will need") List<String> requiredDocuments,
            @Param(description = "True when the payout is small enough to settle without an adjuster") boolean fastTrack) {}

    @ServerTool(name = "assess", title = "Assess coverage",
            description = "Checks whether a loss is covered by a policy on the incident date and estimates the payout"
                    + " (the loss up to the policy limit, less the deductible) and the documents needed.",
            useWhen = "Use it before filing, to tell the holder whether a claim is worth making and what to gather.",
            effect = EffectSafety.READ_ONLY, errors = @ErrorDecl(code = "POLICY_NOT_FOUND", description = "No policy has that id."))
    @Example(arguments = "{\"policyId\":\"POL-2001\",\"peril\":\"storm\",\"lossAmount\":4200,\"incidentDate\":\"2026-10-01\"}",
            expectedOutput = "{\"policyId\":\"POL-2001\",\"peril\":\"STORM\",\"covered\":true,\"reason\":\"STORM is covered by this HOME policy.\","
                    + "\"lossAmount\":4200.0,\"deductible\":1000.0,\"payout\":3200.0,"
                    + "\"requiredDocuments\":[\"Photos of the damage\",\"Repair estimate\"],\"fastTrack\":true}")
    public Assessment assess(
            @Param(description = "Policy id", maxLength = 20) String policyId,
            @Param(description = "What happened: STORM, FIRE, THEFT, WATER_BURST, FLOOD, COLLISION, GLASS ...", maxLength = 30) String peril,
            @Param(description = "Estimated loss", min = "1", max = "10000000") double lossAmount,
            @Param(description = "When it happened, ISO date such as 2026-10-01", maxLength = 10) String incidentDate,
            ToolContext context) {
        Policy p = findPolicy(policyId);
        String key = peril.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        LocalDate when = date(incidentDate);
        String reason = null;
        if (when.isBefore(LocalDate.parse(p.startDate())) || when.isAfter(LocalDate.parse(p.endDate()))) {
            reason = "The incident date is outside the policy's cover (" + p.startDate() + " to " + p.endDate() + ").";
        } else if (p.excluded().contains(key)) {
            reason = key + " is excluded by this policy.";
        } else if (!p.covered().contains(key)) {
            reason = key + " is not a peril this " + p.product() + " policy covers.";
        }
        List<String> docs = Data.DOCUMENTS.getOrDefault(key, List.of("Description of the loss", "Photos"));
        if (reason != null) return new Assessment(p.policyId(), key, false, reason, lossAmount, 0, 0, List.of(), false);
        double payout = Math.max(0, Math.min(lossAmount, p.limit()) - p.deductible());
        return new Assessment(p.policyId(), key, true, key + " is covered by this " + p.product() + " policy.", lossAmount,
                p.deductible(), payout, docs, payout > 0 && payout <= Data.FAST_TRACK_LIMIT);
    }

    public record Filed(
            @Param(description = "New claim id") String claimId,
            @Param(description = "OPEN") String status,
            @Param(description = "True when it will settle without an adjuster") boolean fastTrack,
            @Param(description = "Documents to upload") List<String> requiredDocuments,
            @Param(description = "What happens next") String nextStep) {}

    @ServerTool(name = "file-claim", title = "File a claim",
            description = "Files a claim against a policy (mocked: nothing is stored) after checking cover. A repeat with"
                    + " the same reference returns the first claim instead of filing twice.",
            useWhen = "Use it once the holder confirms they want to claim, after assess says the loss is covered.",
            effect = EffectSafety.NON_IDEMPOTENT, businessKeyArgument = "reference",
            errors = {
                @ErrorDecl(code = "POLICY_NOT_FOUND", description = "No policy has that id."),
                @ErrorDecl(code = "NOT_COVERED", description = "The policy does not cover this loss; the message says why.")
            })
    @Example(arguments = "{\"policyId\":\"POL-2003\",\"peril\":\"FIRE\",\"lossAmount\":9000,\"incidentDate\":\"2026-09-01\","
            + "\"description\":\"Kitchen fire\",\"reference\":\"fnol-1\"}", expectedError = "NOT_COVERED")
    public Filed fileClaim(
            @Param(description = "Policy id", maxLength = 20) String policyId,
            @Param(description = "What happened, as for assess", maxLength = 30) String peril,
            @Param(description = "Estimated loss", min = "1", max = "10000000") double lossAmount,
            @Param(description = "When it happened, ISO date", maxLength = 10) String incidentDate,
            @Param(description = "What happened, in the holder's words", minLength = 1, maxLength = 2000) String description,
            @Param(description = "Your conversation or case id; a repeat with the same reference files once", minLength = 1, maxLength = 64)
            String reference,
            ToolContext context) {
        Assessment a = assess(policyId, peril, lossAmount, incidentDate, context);
        if (!a.covered()) throw new ToolBusinessError("NOT_COVERED", a.reason());
        String next = a.fastTrack()
                ? "Upload the documents; small claims are usually paid within 5 working days."
                : "An adjuster will call within 2 working days to arrange an inspection.";
        return new Filed("CLM-" + shortHash(reference), "OPEN", a.fastTrack(), a.requiredDocuments(), next);
    }

    static Policy findPolicy(String id) {
        Policy p = Data.POLICIES.get(id.trim().toUpperCase(Locale.ROOT));
        if (p == null) throw new ToolBusinessError("POLICY_NOT_FOUND", "No policy has that id. Mocked policies are POL-2001 to POL-2003.");
        return p;
    }

    static LocalDate date(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new ToolInputError("incidentDate must be an ISO date such as 2026-10-01.");
        }
    }

    static String shortHash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
