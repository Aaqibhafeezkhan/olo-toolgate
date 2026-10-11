// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

/** How the business key is forwarded upstream. */
public enum IdempotencyScope { BUSINESS_KEY, PER_INVOCATION }
