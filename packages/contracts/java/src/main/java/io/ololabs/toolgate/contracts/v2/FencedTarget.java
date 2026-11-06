// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Independently reviewed target identity and qualified target implementation. This declaration grants no authority by itself.
 *
 * @param targetId canonical targetId value
 * @param targetClass canonical targetClass value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record FencedTarget(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "targetId", required = true) String targetId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "targetClass", required = true) FencedTargetClass targetClass
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param targetId canonical targetId value
     * @param targetClass canonical targetClass value
     */
    public FencedTarget {
        java.util.Objects.requireNonNull(targetId, "targetId");
        java.util.Objects.requireNonNull(targetClass, "targetClass");
    }
}
