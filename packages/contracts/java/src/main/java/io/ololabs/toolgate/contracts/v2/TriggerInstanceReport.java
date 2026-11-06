// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Executor snapshot entry for one held instance (plan 8.2).
 *
 * @param instanceId canonical instanceId value
 * @param configRevision canonical configRevision value
 * @param leaseEpoch canonical leaseEpoch value
 * @param phase canonical phase value
 * @param conditions canonical conditions value
 * @param lastEventAt canonical lastEventAt value
 * @param lastReconcileAt canonical lastReconcileAt value
 * @param counters canonical counters value
 * @param outbox canonical outbox value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerInstanceReport(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "configRevision", required = true) Long configRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseEpoch", required = false) Long leaseEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "phase", required = true) TriggerPhase phase,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "conditions", required = true) java.util.List<TriggerCondition> conditions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "lastEventAt", required = false) String lastEventAt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "lastReconcileAt", required = false) String lastReconcileAt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "counters", required = true) TriggerCounters counters,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outbox", required = true) TriggerOutboxStatus outbox
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param instanceId canonical instanceId value
     * @param configRevision canonical configRevision value
     * @param leaseEpoch canonical leaseEpoch value
     * @param phase canonical phase value
     * @param conditions canonical conditions value
     * @param lastEventAt canonical lastEventAt value
     * @param lastReconcileAt canonical lastReconcileAt value
     * @param counters canonical counters value
     * @param outbox canonical outbox value
     */
    public TriggerInstanceReport {
        java.util.Objects.requireNonNull(instanceId, "instanceId");
        java.util.Objects.requireNonNull(configRevision, "configRevision");
        java.util.Objects.requireNonNull(phase, "phase");
        java.util.Objects.requireNonNull(conditions, "conditions");
        conditions = conditions == null ? null : java.util.List.copyOf(conditions);
        java.util.Objects.requireNonNull(counters, "counters");
        java.util.Objects.requireNonNull(outbox, "outbox");
    }
}
