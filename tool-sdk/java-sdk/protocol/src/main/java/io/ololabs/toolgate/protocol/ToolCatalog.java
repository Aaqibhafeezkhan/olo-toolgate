// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** What the protocol core serves. The runtime implements it; the core owns framing, versions and errors. */
public interface ToolCatalog {
    /** Server name and version for {@code io.modelcontextprotocol/serverInfo}. */
    ObjectNode serverInfo();

    /** Extra members of the {@code server/discover} result {@code _meta}, such as descriptor digests. */
    ObjectNode discoverMeta();

    /** The {@code tools} array for this request's capabilities. */
    JsonNode tools(RequestMeta meta);

    /**
     * Runs one {@code tools/call}. Returns the result object without {@code resultType}, or a {@code CreateTaskResult}
     * with {@code resultType: "task"}; throws {@link ProtocolError} for an unknown tool, malformed parameters or a
     * missing capability.
     */
    ObjectNode call(String name, JsonNode arguments, RequestMeta meta);

    /** The Tasks extension (adapter A2) this catalog serves, or null when it serves none. */
    default Tasks tasks() {
        return null;
    }
}
