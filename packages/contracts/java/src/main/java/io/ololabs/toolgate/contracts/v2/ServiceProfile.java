// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Service profile. Its digest and revision are pinned in the permit.
 *
 * @param profileId canonical profileId value
 * @param revision canonical revision value
 * @param packageId canonical packageId value
 * @param destinations canonical destinations value
 * @param routes canonical routes value
 * @param privateRanges canonical privateRanges value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServiceProfile(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileId", required = true) String profileId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "destinations", required = true) java.util.List<ServiceDestination> destinations,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routes", required = true) java.util.List<RouteDeclaration> routes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "privateRanges", required = true) java.util.List<PrivateRangeApproval> privateRanges
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param profileId canonical profileId value
     * @param revision canonical revision value
     * @param packageId canonical packageId value
     * @param destinations canonical destinations value
     * @param routes canonical routes value
     * @param privateRanges canonical privateRanges value
     */
    public ServiceProfile {
        java.util.Objects.requireNonNull(profileId, "profileId");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(destinations, "destinations");
        destinations = destinations == null ? null : java.util.List.copyOf(destinations);
        java.util.Objects.requireNonNull(routes, "routes");
        routes = routes == null ? null : java.util.List.copyOf(routes);
        java.util.Objects.requireNonNull(privateRanges, "privateRanges");
        privateRanges = privateRanges == null ? null : java.util.List.copyOf(privateRanges);
    }
}
