// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical WorkflowProgressKind wire values; unknown values must be rejected. */
public enum WorkflowProgressKind {
    /** Canonical NODE_COMPLETED value. */
    NODE_COMPLETED,
    /** Canonical AGENT_STARTED value. */
    AGENT_STARTED,
    /** Canonical AGENT_COMPLETED value. */
    AGENT_COMPLETED,
    /** Canonical MODEL_STARTED value. */
    MODEL_STARTED,
    /** Canonical MODEL_COMPLETED value. */
    MODEL_COMPLETED,
    /** Canonical TOOL_STARTED value. */
    TOOL_STARTED,
    /** Canonical TOOL_COMPLETED value. */
    TOOL_COMPLETED,
    /** Canonical TOOL_FAILED value. */
    TOOL_FAILED
}
