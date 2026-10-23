// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Reviewed immutable versioned extraction definition; changing it invalidates operation bindings.
 *
 * @param id canonical id value
 * @param name canonical name value
 * @param enabled canonical enabled value
 * @param revision canonical revision value
 * @param extractorKind canonical extractorKind value
 * @param version canonical version value
 * @param fields canonical fields value
 * @param fixedResources canonical fixedResources value
 * @param maxResources canonical maxResources value
 * @param amountPointer canonical amountPointer value
 * @param operationPointer canonical operationPointer value
 * @param locatorTemplates canonical locatorTemplates value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlResourceExtractor(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enabled", required = true) Boolean enabled,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "extractorKind", required = true) EnterpriseExtractorKind extractorKind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fields", required = true) java.util.List<ExtractorField> fields,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fixedResources", required = true) java.util.List<ResourceDescriptor> fixedResources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxResources", required = true) Long maxResources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "amountPointer", required = false) String amountPointer,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operationPointer", required = false) String operationPointer,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "locatorTemplates", required = false) java.util.List<String> locatorTemplates
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param name canonical name value
     * @param enabled canonical enabled value
     * @param revision canonical revision value
     * @param extractorKind canonical extractorKind value
     * @param version canonical version value
     * @param fields canonical fields value
     * @param fixedResources canonical fixedResources value
     * @param maxResources canonical maxResources value
     * @param amountPointer canonical amountPointer value
     * @param operationPointer canonical operationPointer value
     * @param locatorTemplates canonical locatorTemplates value
     */
    public ControlResourceExtractor {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(enabled, "enabled");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(extractorKind, "extractorKind");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(fields, "fields");
        fields = fields == null ? null : java.util.List.copyOf(fields);
        java.util.Objects.requireNonNull(fixedResources, "fixedResources");
        fixedResources = fixedResources == null ? null : java.util.List.copyOf(fixedResources);
        java.util.Objects.requireNonNull(maxResources, "maxResources");
        locatorTemplates = locatorTemplates == null ? null : java.util.List.copyOf(locatorTemplates);
    }
}
