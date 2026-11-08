// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A routed event as one reader may see it (plan 12.2 rule 5): the envelope always; data and subject only when the reader's clearance covers the event's label.
 *
 * @param specversion canonical specversion value
 * @param id canonical id value
 * @param source canonical source value
 * @param type canonical type value
 * @param subject canonical subject value
 * @param time canonical time value
 * @param data canonical data value
 * @param tgclassification canonical tgclassification value
 * @param tgredacted canonical tgredacted value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerViewEvent(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "specversion", required = true) String specversion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) String source,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "type", required = true) String type,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subject", required = false) String subject,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "time", required = false) String time,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "data", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> data,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tgclassification", required = true) TriggerClassification tgclassification,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tgredacted", required = true) Boolean tgredacted
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param specversion canonical specversion value
     * @param id canonical id value
     * @param source canonical source value
     * @param type canonical type value
     * @param subject canonical subject value
     * @param time canonical time value
     * @param data canonical data value
     * @param tgclassification canonical tgclassification value
     * @param tgredacted canonical tgredacted value
     */
    public TriggerViewEvent {
        java.util.Objects.requireNonNull(specversion, "specversion");
        if (specversion != null && !java.util.Objects.equals(specversion, "1.0")) throw new IllegalArgumentException("specversion must be 1.0");
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(type, "type");
        data = data == null ? null : java.util.Map.copyOf(data);
        java.util.Objects.requireNonNull(tgclassification, "tgclassification");
        java.util.Objects.requireNonNull(tgredacted, "tgredacted");
    }
}
