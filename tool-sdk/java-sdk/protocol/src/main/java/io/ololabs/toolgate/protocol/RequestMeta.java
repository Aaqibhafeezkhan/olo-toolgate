// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The per-request {@code _meta} of a 2026-07-28 request. Features are used only when this request declares them.
 *
 * @param protocolVersion the request's protocol version
 * @param clientCapabilities the declared client capabilities
 * @param extensions the extension ids declared in {@code clientCapabilities.extensions}
 * @param transport {@code stdio} or {@code http}
 * @param owner who owns the tasks this request creates: the verified identity, or the connection when there is none
 * @param progressToken the request's {@code _meta.progressToken}, or null
 * @param notifications where notifications about this request go (progress), ahead of its response
 * @param identity the caller adapter A4 verified, or null (stdio, development, or HTTP without a verifier)
 * @param inputResponses the MRTR answers of this {@code tools/call}: those its verified {@code requestState} carries
 *     from earlier rounds, then its own {@code inputResponses}; empty on a first round
 */
public record RequestMeta(String protocolVersion, JsonNode clientCapabilities, Set<String> extensions, String transport, String owner,
        VerifiedIdentity identity, JsonNode progressToken, Consumer<ObjectNode> notifications, ObjectNode inputResponses) {
    public static final String TASKS = "io.modelcontextprotocol/tasks";

    public boolean declares(String extension) {
        return extensions.contains(extension);
    }

    /** Whether {@code clientCapabilities} declares a capability such as {@code elicitation}, {@code sampling} or {@code roots}. */
    public boolean declaresCapability(String capability) {
        return clientCapabilities.path(capability).isObject();
    }

    RequestMeta withInputResponses(ObjectNode responses) {
        return new RequestMeta(protocolVersion, clientCapabilities, extensions, transport, owner, identity, progressToken, notifications, responses);
    }
}
