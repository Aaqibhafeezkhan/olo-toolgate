// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** CloudEvents 1.0 JSON envelope written by the supervisor; authors never set source, type, dataschema or tg* attributes (plan 6.4).
 *
 * @param specversion canonical specversion value
 * @param id canonical id value
 * @param source canonical source value
 * @param type canonical type value
 * @param subject canonical subject value
 * @param time canonical time value
 * @param datacontenttype canonical datacontenttype value
 * @param dataschema canonical dataschema value
 * @param data canonical data value
 * @param tgleaseepoch canonical tgleaseepoch value
 * @param tgsequence canonical tgsequence value
 * @param tgkind canonical tgkind value
 * @param traceparent canonical traceparent value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerCloudEvent(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "specversion", required = true) String specversion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) String source,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "type", required = true) String type,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subject", required = false) String subject,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "time", required = false) String time,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "datacontenttype", required = true) String datacontenttype,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dataschema", required = false) String dataschema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "data", required = true) com.fasterxml.jackson.databind.JsonNode data,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tgleaseepoch", required = true) Long tgleaseepoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tgsequence", required = true) Long tgsequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tgkind", required = true) TriggerEventKind tgkind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "traceparent", required = false) String traceparent
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param specversion canonical specversion value
     * @param id canonical id value
     * @param source canonical source value
     * @param type canonical type value
     * @param subject canonical subject value
     * @param time canonical time value
     * @param datacontenttype canonical datacontenttype value
     * @param dataschema canonical dataschema value
     * @param data canonical data value
     * @param tgleaseepoch canonical tgleaseepoch value
     * @param tgsequence canonical tgsequence value
     * @param tgkind canonical tgkind value
     * @param traceparent canonical traceparent value
     */
    public TriggerCloudEvent {
        java.util.Objects.requireNonNull(specversion, "specversion");
        if (specversion != null && !java.util.Objects.equals(specversion, "1.0")) throw new IllegalArgumentException("specversion must be 1.0");
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(datacontenttype, "datacontenttype");
        if (datacontenttype != null && !java.util.Objects.equals(datacontenttype, "application/json")) throw new IllegalArgumentException("datacontenttype must be application/json");
        java.util.Objects.requireNonNull(data, "data");
        java.util.Objects.requireNonNull(tgleaseepoch, "tgleaseepoch");
        java.util.Objects.requireNonNull(tgsequence, "tgsequence");
        java.util.Objects.requireNonNull(tgkind, "tgkind");
    }
}
