// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

/**
 * The reviewed reaction to an event the executor or Control rejects terminally. HALT: nothing after it is
 * checkpointed and the instance fails, so a fixed version replays it. SKIP_AND_RECORD: a loss record replaces it.
 */
public enum TerminalRejectionPolicy { HALT, SKIP_AND_RECORD }
