// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Declared global.
 *
 * @param name canonical name value
 * @param type canonical type value
 * @param required canonical required value
 * @param default_ canonical default value
 * @param sensitive canonical sensitive value
 * @param description canonical description value
 * @param minimum canonical minimum value
 * @param maximum canonical maximum value
 * @param minLength canonical minLength value
 * @param maxLength canonical maxLength value
 * @param pattern canonical pattern value
 * @param enum_ canonical enum value
 * @param format canonical format value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record GlobalDeclaration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "type", required = true) ValueType type,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "required", required = true) Boolean required,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "default", required = false) com.fasterxml.jackson.databind.JsonNode default_,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sensitive", required = true) Boolean sensitive,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "description", required = false) String description,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "minimum", required = false) Double minimum,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maximum", required = false) Double maximum,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "minLength", required = false) Long minLength,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxLength", required = false) Long maxLength,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "pattern", required = false) String pattern,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enum", required = false) java.util.List<String> enum_,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "format", required = false) String format
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param name canonical name value
     * @param type canonical type value
     * @param required canonical required value
     * @param default_ canonical default value
     * @param sensitive canonical sensitive value
     * @param description canonical description value
     * @param minimum canonical minimum value
     * @param maximum canonical maximum value
     * @param minLength canonical minLength value
     * @param maxLength canonical maxLength value
     * @param pattern canonical pattern value
     * @param enum_ canonical enum value
     * @param format canonical format value
     */
    public GlobalDeclaration {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(required, "required");
        java.util.Objects.requireNonNull(sensitive, "sensitive");
        enum_ = enum_ == null ? null : java.util.List.copyOf(enum_);
    }
}
