// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

/**
 * What the source can replay after downtime, which decides what ToolGate can promise about completeness. Shown at
 * review and in the definition. EVENT_LOG: every event the source still retains is recovered from the last checkpoint.
 * STATE_SNAPSHOT: the current state is recovered, not every intermediate event. NON_REPLAYABLE: best effort.
 */
public enum SourceReplay { EVENT_LOG, STATE_SNAPSHOT, NON_REPLAYABLE }
