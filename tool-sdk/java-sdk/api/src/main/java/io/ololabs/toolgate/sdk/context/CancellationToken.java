// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

/** Cooperative cancellation. */
public interface CancellationToken {
    boolean isCancellationRequested();
    /** Throws the platform CANCELLED error when cancellation was requested. */
    void throwIfCancellationRequested();
    void onCancel(Runnable callback);
}
