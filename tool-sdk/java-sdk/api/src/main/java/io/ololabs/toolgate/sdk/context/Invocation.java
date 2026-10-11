// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

import java.time.Instant;
import java.util.Optional;

/** The current invocation. */
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
}
