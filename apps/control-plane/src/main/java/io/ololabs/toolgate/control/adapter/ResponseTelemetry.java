// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/** Logs fixed response metadata; neither paths, query strings, identities nor headers enter logs. */
@Provider
public class ResponseTelemetry implements ContainerResponseFilter {
    @Inject Correlation correlation;
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(ResponseTelemetry.class);
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        // Framework authentication mappers can produce empty responses. Normalize
        // them here as well as application exceptions so the wire error is stable.
        if (response.getStatus() >= 400 && !(response.getEntity() instanceof io.ololabs.toolgate.contracts.ErrorEnvelope)
                && !(response.getEntity() instanceof io.ololabs.toolgate.contracts.ControlErrorDetail)) {
            var code = switch (response.getStatus()) {
                case 401 -> io.ololabs.toolgate.contracts.ErrorCode.UNAUTHORIZED;
                case 403 -> io.ololabs.toolgate.contracts.ErrorCode.FORBIDDEN;
                case 404 -> io.ololabs.toolgate.contracts.ErrorCode.NOT_FOUND;
                case 409 -> io.ololabs.toolgate.contracts.ErrorCode.CONFLICT;
                case 415 -> io.ololabs.toolgate.contracts.ErrorCode.UNSUPPORTED;
                case 503 -> io.ololabs.toolgate.contracts.ErrorCode.DEPENDENCY_UNAVAILABLE;
                default -> response.getStatus() < 500 ? io.ololabs.toolgate.contracts.ErrorCode.VALIDATION : io.ololabs.toolgate.contracts.ErrorCode.INTERNAL;
            };
            response.setEntity(new io.ololabs.toolgate.contracts.ErrorEnvelope(code, correlation.id(), response.getStatus() == 503));
            response.getHeaders().putSingle("Content-Type", "application/json");
        }
        response.getHeaders().putSingle("X-Request-ID", correlation.id());
        response.getHeaders().putSingle("Cache-Control", "no-store");
        var span = io.opentelemetry.api.trace.Span.current().getSpanContext();
        io.opentelemetry.api.trace.Span.current().setAttribute("toolgate.request_id", correlation.id());
        LOG.infof("control_response status=%d request_id=%s trace_id=%s", response.getStatus(), correlation.id(), span.isValid() ? span.getTraceId() : "none");
    }
}
