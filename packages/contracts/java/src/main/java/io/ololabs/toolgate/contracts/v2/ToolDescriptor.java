// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Semantic tool descriptor, generation 1. Identical in standalone and governed mode.
 *
 * @param descriptorGeneration canonical descriptorGeneration value
 * @param toolId canonical toolId value
 * @param mcpName canonical mcpName value
 * @param kind canonical kind value
 * @param title canonical title value
 * @param description canonical description value
 * @param useWhen canonical useWhen value
 * @param inputSchema canonical inputSchema value
 * @param outputSchema canonical outputSchema value
 * @param errors canonical errors value
 * @param effect canonical effect value
 * @param businessKey canonical businessKey value
 * @param execution canonical execution value
 * @param reconcileTool canonical reconcileTool value
 * @param tiers canonical tiers value
 * @param routes canonical routes value
 * @param destinations canonical destinations value
 * @param httpAuth canonical httpAuth value
 * @param tcp canonical tcp value
 * @param variables canonical variables value
 * @param permissions canonical permissions value
 * @param toolsets canonical toolsets value
 * @param authorization canonical authorization value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolDescriptor(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "descriptorGeneration", required = true) Long descriptorGeneration,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mcpName", required = true) String mcpName,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) ToolKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "title", required = true) String title,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "description", required = true) String description,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "useWhen", required = false) String useWhen,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "inputSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> inputSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outputSchema", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> outputSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "errors", required = true) java.util.List<ErrorDeclaration> errors,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effect", required = true) ToolEffect effect,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "businessKey", required = true) BusinessKeySettings businessKey,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "execution", required = true) ExecutionSettings execution,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reconcileTool", required = false) String reconcileTool,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tiers", required = false) java.util.List<RuntimeTier> tiers,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routes", required = true) java.util.List<RouteDeclaration> routes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "destinations", required = true) java.util.List<DestinationDeclaration> destinations,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "httpAuth", required = true) java.util.List<HttpAuthDeclaration> httpAuth,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tcp", required = true) java.util.List<TcpDeclaration> tcp,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "variables", required = true) ToolVariables variables,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "permissions", required = true) java.util.List<Permission> permissions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolsets", required = true) java.util.List<String> toolsets,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorization", required = false) ToolAuthorization authorization
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param descriptorGeneration canonical descriptorGeneration value
     * @param toolId canonical toolId value
     * @param mcpName canonical mcpName value
     * @param kind canonical kind value
     * @param title canonical title value
     * @param description canonical description value
     * @param useWhen canonical useWhen value
     * @param inputSchema canonical inputSchema value
     * @param outputSchema canonical outputSchema value
     * @param errors canonical errors value
     * @param effect canonical effect value
     * @param businessKey canonical businessKey value
     * @param execution canonical execution value
     * @param reconcileTool canonical reconcileTool value
     * @param tiers canonical tiers value
     * @param routes canonical routes value
     * @param destinations canonical destinations value
     * @param httpAuth canonical httpAuth value
     * @param tcp canonical tcp value
     * @param variables canonical variables value
     * @param permissions canonical permissions value
     * @param toolsets canonical toolsets value
     * @param authorization canonical authorization value
     */
    public ToolDescriptor {
        java.util.Objects.requireNonNull(descriptorGeneration, "descriptorGeneration");
        if (descriptorGeneration != null && !java.util.Objects.equals(descriptorGeneration, 1L)) throw new IllegalArgumentException("descriptorGeneration must be 1");
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(mcpName, "mcpName");
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(title, "title");
        java.util.Objects.requireNonNull(description, "description");
        java.util.Objects.requireNonNull(inputSchema, "inputSchema");
        inputSchema = inputSchema == null ? null : java.util.Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? null : java.util.Map.copyOf(outputSchema);
        java.util.Objects.requireNonNull(errors, "errors");
        errors = errors == null ? null : java.util.List.copyOf(errors);
        java.util.Objects.requireNonNull(effect, "effect");
        java.util.Objects.requireNonNull(businessKey, "businessKey");
        java.util.Objects.requireNonNull(execution, "execution");
        tiers = tiers == null ? null : java.util.List.copyOf(tiers);
        java.util.Objects.requireNonNull(routes, "routes");
        routes = routes == null ? null : java.util.List.copyOf(routes);
        java.util.Objects.requireNonNull(destinations, "destinations");
        destinations = destinations == null ? null : java.util.List.copyOf(destinations);
        java.util.Objects.requireNonNull(httpAuth, "httpAuth");
        httpAuth = httpAuth == null ? null : java.util.List.copyOf(httpAuth);
        java.util.Objects.requireNonNull(tcp, "tcp");
        tcp = tcp == null ? null : java.util.List.copyOf(tcp);
        java.util.Objects.requireNonNull(variables, "variables");
        java.util.Objects.requireNonNull(permissions, "permissions");
        permissions = permissions == null ? null : java.util.List.copyOf(permissions);
        java.util.Objects.requireNonNull(toolsets, "toolsets");
        toolsets = toolsets == null ? null : java.util.List.copyOf(toolsets);
    }
}
