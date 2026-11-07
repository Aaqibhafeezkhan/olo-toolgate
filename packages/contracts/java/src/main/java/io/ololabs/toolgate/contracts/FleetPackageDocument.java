// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. A package carries at least one tool or trigger.
 *
 * @param formatVersion canonical formatVersion value
 * @param packageId canonical packageId value
 * @param version canonical version value
 * @param platforms canonical platforms value
 * @param architectures canonical architectures value
 * @param minimumClientVersion canonical minimumClientVersion value
 * @param runtimes canonical runtimes value
 * @param tools canonical tools value
 * @param selfTests canonical selfTests value
 * @param triggers canonical triggers value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record FleetPackageDocument(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "formatVersion", required = true) Long formatVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "platforms", required = true) java.util.List<ClientPlatform> platforms,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "architectures", required = true) java.util.List<FleetArchitecture> architectures,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "minimumClientVersion", required = true) String minimumClientVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runtimes", required = true) java.util.List<ManagedRuntime> runtimes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) java.util.List<LocalToolRegistration> tools,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "selfTests", required = true) java.util.List<FleetSelfTest> selfTests,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggers", required = false) java.util.List<FleetTriggerRegistration> triggers
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param formatVersion canonical formatVersion value
     * @param packageId canonical packageId value
     * @param version canonical version value
     * @param platforms canonical platforms value
     * @param architectures canonical architectures value
     * @param minimumClientVersion canonical minimumClientVersion value
     * @param runtimes canonical runtimes value
     * @param tools canonical tools value
     * @param selfTests canonical selfTests value
     * @param triggers canonical triggers value
     */
    public FleetPackageDocument {
        java.util.Objects.requireNonNull(formatVersion, "formatVersion");
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(platforms, "platforms");
        platforms = platforms == null ? null : java.util.List.copyOf(platforms);
        java.util.Objects.requireNonNull(architectures, "architectures");
        architectures = architectures == null ? null : java.util.List.copyOf(architectures);
        java.util.Objects.requireNonNull(minimumClientVersion, "minimumClientVersion");
        java.util.Objects.requireNonNull(runtimes, "runtimes");
        runtimes = runtimes == null ? null : java.util.List.copyOf(runtimes);
        java.util.Objects.requireNonNull(tools, "tools");
        tools = tools == null ? null : java.util.List.copyOf(tools);
        java.util.Objects.requireNonNull(selfTests, "selfTests");
        selfTests = selfTests == null ? null : java.util.List.copyOf(selfTests);
        triggers = triggers == null ? null : java.util.List.copyOf(triggers);
    }
}
