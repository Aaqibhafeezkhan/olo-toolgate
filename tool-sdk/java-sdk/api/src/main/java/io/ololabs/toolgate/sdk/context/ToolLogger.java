// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

/** The only supported log channel; entries are redacted and size-capped. */
public interface ToolLogger {
    void debug(String message);
    void info(String message);
    void warn(String message);
    void error(String message);
}
