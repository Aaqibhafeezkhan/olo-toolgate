// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

/** Effect safety of a tool (execution.md §4). */
public enum EffectSafety { READ_ONLY, IDEMPOTENT, NON_IDEMPOTENT, UNKNOWN }
