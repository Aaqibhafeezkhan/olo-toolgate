// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

import io.ololabs.toolgate.sdk.errors.PlatformError;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The current invocation.
 *
 * <p>In governed execution the principal (tenant, user, agent, delegation chain, operation and resources) is the one
 * Control authorized and signed into the permit; the Tool Host verifies the permit and supplies it outside the
 * arguments, so no argument can change it. Standalone runs have no verified principal: those accessors are empty.
 */
public interface Invocation {
    String invocationId();
    String toolId();
    Optional<String> agentId();
    Optional<String> userId();
    Optional<Long> catalogRevision();
    Instant deadline();
    Optional<String> businessKey();
    int attempt();
    InvocationMode mode();
    /** The tenant the permit was issued for. */
    Optional<String> tenantId();
    /** The authorized action, as the tool declares it. */
    Optional<String> operation();
    /** The resources Control authorized for this call; a scoped tool acts on nothing else. */
    List<Resource> resources();
    /** The agents a delegated call passed through, outermost first; empty for direct calls. */
    List<String> delegationChain();

    /** This attempt's execution ID; a retry gets a new one. Empty in standalone runs. */
    default Optional<String> executionId() { return Optional.empty(); }
    /** The caller's tool-call ID, as the caller declared it: for correlation only, never trust it. */
    default Optional<String> toolCallId() { return Optional.empty(); }
    /** The caller's session ID, as the caller declared it: for correlation only, never trust it. */
    default Optional<String> sessionId() { return Optional.empty(); }
    /** The caller's agent-run ID, as the caller declared it: for correlation only, never trust it. */
    default Optional<String> agentRunId() { return Optional.empty(); }
    /** The W3C trace parent for calls this tool makes downstream; see {@link #traceHeaders()}. */
    default Optional<String> traceparent() { return Optional.empty(); }
    /** W3C trace headers for a downstream request. They carry no user, session or tool identifiers. */
    default java.util.Map<String, String> traceHeaders() { return java.util.Map.of(); }

    /**
     * The verified user, for tools that act on a user's data. Refuses when there is none, including every standalone
     * run, so such a tool never falls back to acting without a user.
     */
    default String requireUser() {
        return userId().filter(user -> tenantId().isPresent())
                .orElseThrow(() -> new PlatformError(PlatformError.Code.CLAIMS_UNAVAILABLE, "This tool requires a verified user"));
    }

    /** One authorized resource. */
    record Resource(String kind, String locator) {}
}
