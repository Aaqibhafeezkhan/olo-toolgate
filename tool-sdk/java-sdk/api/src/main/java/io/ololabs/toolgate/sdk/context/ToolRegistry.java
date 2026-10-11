// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

import java.util.List;

/** A {@link java.util.ServiceLoader} provider of handler instances, for classes without a public no-argument constructor. */
public interface ToolRegistry {
    List<Object> handlers();
}
