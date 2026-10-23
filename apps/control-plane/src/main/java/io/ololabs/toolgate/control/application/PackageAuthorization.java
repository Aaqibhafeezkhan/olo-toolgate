// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A package tool's declared permission and resources (OLO M3.7, contract 2.3). Without a declaration the tool keeps the
 * single {@value ServerPackages#ACTION} action on {@code tool://<toolId>}. With one, the tool's action is the declared
 * permission (for example {@code memory.read}) and its resources are built per call from locator templates such as
 * {@code olo://{tenant}/conversation/{conversationId}}: the tenant from the verified context, the ids from required string
 * arguments. Ownership and sharing of the object stay with the service that holds it.
 */
public record PackageAuthorization(String action, List<String> templates) {
    private static final Pattern ACTION = Pattern.compile("[a-z][a-z0-9_-]{0,31}([.][a-z][a-z0-9_-]{0,31}){0,3}");
    private static final Pattern PLACEHOLDER = Pattern.compile("[{]([A-Za-z][A-Za-z0-9_]{0,63})[}]");

    public PackageAuthorization {
        templates = List.copyOf(templates);
    }

    /** The declaration in {@code descriptor}, checked against its input schema; an inconsistent one is incompatible. */
    public static PackageAuthorization of(JsonNode descriptor) {
        var declared = descriptor.path("authorization");
        if (declared.isMissingNode() || declared.isNull()) return new PackageAuthorization(ServerPackages.ACTION, List.of());
        String action = declared.path("action").asText("");
        if (!ACTION.matcher(action).matches() || !declared.path("resources").isArray() || declared.path("resources").isEmpty())
            throw PackageCompatibility.incompatible();
        var schema = descriptor.path("inputSchema");
        var required = new java.util.HashSet<String>();
        schema.path("required").forEach(name -> required.add(name.asText()));
        var templates = new ArrayList<String>();
        for (var resource : declared.path("resources")) {
            String template = resource.asText("");
            int start = template.indexOf("://");
            if (start < 1 || !template.startsWith("{tenant}/", start + 3)) throw PackageCompatibility.incompatible();
            for (var segment : template.substring(start + 3 + "{tenant}/".length()).split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.equals("{tenant}"))
                    throw PackageCompatibility.incompatible();
                var placeholder = PLACEHOLDER.matcher(segment);
                if (placeholder.matches()) {
                    String name = placeholder.group(1);
                    // Each id is a required string argument, so every call names its resource.
                    if (!required.contains(name) || !"string".equals(schema.path("properties").path(name).path("type").asText()))
                        throw PackageCompatibility.incompatible();
                } else if (!ResourceExtraction.OPAQUE_ID.matcher(segment).matches()) {
                    throw PackageCompatibility.incompatible();
                }
            }
            templates.add(template);
        }
        return new PackageAuthorization(action, templates);
    }

    /** Fixed resources: {@code tool://<toolId>} only for tools that declare no resources of their own. */
    public void fixedResources(ArrayNode fixed, String toolId) {
        if (templates.isEmpty()) fixed.addObject().put("kind", "CUSTOM").put("locator", "tool://" + toolId);
    }

    public void locatorTemplates(ObjectNode extractor) {
        if (templates.isEmpty()) return;
        var out = extractor.putArray("locatorTemplates");
        templates.forEach(out::add);
    }
}
