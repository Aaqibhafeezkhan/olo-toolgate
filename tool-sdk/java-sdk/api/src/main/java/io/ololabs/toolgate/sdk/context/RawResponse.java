// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

import java.util.List;
import java.util.Map;

/** A response with its bytes and headers. */
public interface RawResponse {
    int status();
    Map<String, List<String>> headers();
    byte[] body();
}
