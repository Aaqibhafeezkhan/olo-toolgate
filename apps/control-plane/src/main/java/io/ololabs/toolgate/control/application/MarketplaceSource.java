// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** The configured Marketplace origin, over HTTPS. Control builds every path itself from validated identifiers, so
 *  nothing the Marketplace returns chooses where Control connects. Redirects are never followed. */
public interface MarketplaceSource {
    /** A response: its status, its media type without parameters (lowercase, "" when absent) and its body. */
    record Response(int status, String mediaType, byte[] body) {}

    /** GETs {@code path} (absolute, starting with "/") on the configured origin. A body over {@code maxBytes} throws a
     *  413 validation {@link Failure}; a transport failure or timeout throws {@link Failure#unavailable}. */
    Response get(String path, String accept, long maxBytes);
}
