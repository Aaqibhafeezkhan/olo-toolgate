// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.annotations;

/** Whether a business key is required; DEFAULT is true for a NON_IDEMPOTENT destructive tool. */
public enum BusinessKey { DEFAULT, REQUIRED, NOT_REQUIRED }
