// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

/**
 * How ToolGate settles a call of this tool whose outcome is unknown, for example after a timeout during the effect
 * (OLO M5.4). A tool with side effects ({@link EffectSafety#IDEMPOTENT} or {@link EffectSafety#NON_IDEMPOTENT}) must
 * declare one; a {@link EffectSafety#READ_ONLY} tool declares none. A tool whose effect is {@link EffectSafety#UNKNOWN}
 * and declares none is settled {@link #MANUAL}ly. An unknown outcome is never resent blindly.
 */
public enum Reconciliation {
    /** Not declared. */
    UNDECLARED,
    /**
     * Resend with the same downstream idempotency key; the target system deduplicates. Needs {@link Idempotency} or an
     * {@link EffectSafety#IDEMPOTENT} effect.
     */
    DOWNSTREAM_IDEMPOTENCY,
    /** Look the effect up with this tool's {@link Reconcile} tool, which reports whether it took place. */
    STATUS_LOOKUP,
    /** Neither is possible: the call stays unknown until an operator decides. */
    MANUAL
}
