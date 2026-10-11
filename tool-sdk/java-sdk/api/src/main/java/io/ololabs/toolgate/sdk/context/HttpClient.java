// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

/** Route client. There is no free-form URL, base URL or host API. */
public interface HttpClient {
    RouteCall route(String routeId);
    /** Convenience form; the destination and template must be literals that match exactly one of the tool's routes. */
    RouteCall get(String destinationId, String pathTemplate);
    RouteCall post(String destinationId, String pathTemplate);
    RouteCall put(String destinationId, String pathTemplate);
    RouteCall patch(String destinationId, String pathTemplate);
    RouteCall delete(String destinationId, String pathTemplate);
    RouteCall head(String destinationId, String pathTemplate);
}
