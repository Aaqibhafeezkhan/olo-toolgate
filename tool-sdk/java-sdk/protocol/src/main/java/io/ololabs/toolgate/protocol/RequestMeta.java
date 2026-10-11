// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * The per-request {@code _meta} of a 2026-07-28 request. Features are used only when this request declares them.
 *
 * @param protocolVersion the request's protocol version
 * @param clientCapabilities the declared client capabilities
 * @param extensions the extension ids declared in {@code clientCapabilities.extensions}
 * @param transport {@code stdio} or {@code http}
 * @param owner who owns the tasks this request creates: the verified identity, or the connection when there is none
 */
public record RequestMeta(String protocolVersion, JsonNode clientCapabilities, Set<String> extensions, String transport, String owner) {
    public static final String TASKS = "io.modelcontextprotocol/tasks";

    public boolean declares(String extension) {
        return extensions.contains(extension);
    }
}
