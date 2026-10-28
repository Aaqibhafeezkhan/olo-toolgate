// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What answers route calls in test mode, in place of the network. For {@code toolgate-sdk-testing}; the request and
 * reply shapes are the recorded exchange format of spec/authoring/testing.md.
 */
@FunctionalInterface
public interface TestExchanges {
    /**
     * Answers one route call.
     *
     * @param request {@code route}, {@code method}, {@code destination}, {@code variables}, {@code query},
     *     {@code headers}, {@code auth} and the body as {@code body} (JSON), {@code bodyText} or {@code bodyBase64}
     * @return the reply ({@code status}, {@code headers}, a body, or {@code failure}), or null when nothing was recorded
     *     for this call, which fails it
     */
    JsonNode exchange(ObjectNode request);
}
