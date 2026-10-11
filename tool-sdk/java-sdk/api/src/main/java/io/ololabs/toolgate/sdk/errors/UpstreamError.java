// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.errors;

import java.util.Optional;

/** A route call failed: UPSTREAM_4XX, UPSTREAM_5XX, UPSTREAM_TIMEOUT or UPSTREAM_CONNECTION. */
public final class UpstreamError extends ToolError {
    private static final long serialVersionUID = 1L;
    private final String route;
    private final Integer status;
    private final String upstreamRequestId;
    private final boolean effectUncertain;
    private final String body;

    public UpstreamError(String code, String route, Integer status, String upstreamRequestId, boolean effectUncertain, String body) {
        super(code, "Upstream call on route " + route + " failed: " + code, null);
        this.route = route;
        this.status = status;
        this.upstreamRequestId = upstreamRequestId;
        this.effectUncertain = effectUncertain;
        this.body = body;
    }

    public String route() { return route; }
    public Optional<Integer> status() { return Optional.ofNullable(status); }
    public Optional<String> upstreamRequestId() { return Optional.ofNullable(upstreamRequestId); }
    /** True when the request was fully sent and no complete response arrived. */
    public boolean effectUncertain() { return effectUncertain; }
    public Optional<String> body() { return Optional.ofNullable(body); }
}
