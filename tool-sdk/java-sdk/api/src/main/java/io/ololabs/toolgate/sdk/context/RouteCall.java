// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

import java.time.Duration;

/** One call on a declared route. */
public interface RouteCall {
    RouteCall pathVar(String name, Object value);
    /** A null value is omitted. */
    RouteCall query(String name, Object value);
    /** The header must be in the route's allowlist. */
    RouteCall header(String name, String value);
    RouteCall body(Object value);
    /** May only shorten the route timeout. */
    RouteCall timeout(Duration timeout);
    <T> T send(Class<T> type);
    RawResponse sendRaw();
}
