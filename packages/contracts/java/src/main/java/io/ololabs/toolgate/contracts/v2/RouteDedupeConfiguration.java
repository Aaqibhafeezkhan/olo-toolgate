// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tool configuration review binds the exact declaration to an immutable profile/route. Direct or self-reviewed metadata is insufficient.
 *
 * @param profileDigest canonical profileDigest value
 * @param routeId canonical routeId value
 * @param guarantee canonical guarantee value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RouteDedupeConfiguration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routeId", required = true) String routeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "guarantee", required = true) TargetDedupeGuarantee guarantee
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param profileDigest canonical profileDigest value
     * @param routeId canonical routeId value
     * @param guarantee canonical guarantee value
     */
    public RouteDedupeConfiguration {
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(routeId, "routeId");
        java.util.Objects.requireNonNull(guarantee, "guarantee");
    }
}
