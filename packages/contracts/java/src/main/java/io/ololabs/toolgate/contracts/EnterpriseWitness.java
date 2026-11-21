// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** One complete tenant-scoped path; Team and Agent Group identifiers cannot be mixed between witnesses. A super-admin- prefixed grant identifier can denote a Control-derived live Super Admin entitlement rather than a persisted grant; its Team and current directory revision remain authoritative.
 *
 * @param teamId canonical teamId value
 * @param grantId canonical grantId value
 * @param agentGroupId canonical agentGroupId value
 * @param capabilityId canonical capabilityId value
 * @param delegationId canonical delegationId value
 * @param serviceGrantId canonical serviceGrantId value
 * @param bindingId canonical bindingId value
 * @param provenance canonical provenance value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseWitness(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "teamId", required = false) String teamId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grantId", required = false) String grantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentGroupId", required = false) String agentGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "capabilityId", required = false) String capabilityId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "delegationId", required = false) String delegationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serviceGrantId", required = false) String serviceGrantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingId", required = true) String bindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "provenance", required = true) java.util.List<String> provenance
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param teamId canonical teamId value
     * @param grantId canonical grantId value
     * @param agentGroupId canonical agentGroupId value
     * @param capabilityId canonical capabilityId value
     * @param delegationId canonical delegationId value
     * @param serviceGrantId canonical serviceGrantId value
     * @param bindingId canonical bindingId value
     * @param provenance canonical provenance value
     */
    public EnterpriseWitness {
        java.util.Objects.requireNonNull(bindingId, "bindingId");
        java.util.Objects.requireNonNull(provenance, "provenance");
        provenance = provenance == null ? null : java.util.List.copyOf(provenance);
    }
}
