// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

/** How the SDK drives a trigger (ADR 058). POLL: the runtime calls {@code check()} on an interval. PUSH: the author's listener emits. */
public enum TriggerMode { POLL, PUSH }
