// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

/** What the executor does when its outbox is full. DROP_* is allowed only for loss-tolerant event types with SKIP_AND_RECORD. */
public enum OverflowPolicy { BLOCK, DROP_OLDEST, DROP_NEWEST }
