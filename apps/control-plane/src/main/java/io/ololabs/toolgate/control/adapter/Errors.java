// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.contracts.ErrorEnvelope;
import io.ololabs.toolgate.control.application.Failure;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Stable errors without exception text, request payloads or credential material. */
@Provider
public class Errors implements ExceptionMapper<Throwable> {
    @Inject Correlation correlation;
    public Response toResponse(Throwable error) {
        int status = 500; ErrorCode code = ErrorCode.INTERNAL;
        if (error instanceof Failure failure) { status = failure.status(); code = failure.code(); }
        else if (error instanceof io.quarkus.security.UnauthorizedException || error instanceof io.quarkus.security.AuthenticationFailedException) { status = 401; code = ErrorCode.UNAUTHORIZED; }
        else if (error instanceof io.quarkus.security.ForbiddenException) { status = 403; code = ErrorCode.FORBIDDEN; }
        else if (error instanceof IllegalArgumentException || error instanceof com.fasterxml.jackson.core.JacksonException) { status = 400; code = ErrorCode.VALIDATION; }
        else if (error instanceof jakarta.ws.rs.WebApplicationException web) {
            status = web.getResponse().getStatus();
            code = switch (status) { case 401 -> ErrorCode.UNAUTHORIZED; case 403 -> ErrorCode.FORBIDDEN; case 404 -> ErrorCode.NOT_FOUND; case 415 -> ErrorCode.UNSUPPORTED; default -> status < 500 ? ErrorCode.VALIDATION : ErrorCode.INTERNAL; };
        }
        var origin = java.util.Arrays.stream(error.getStackTrace())
            .filter(frame -> frame.getClassName().startsWith("io.ololabs.toolgate.control.") && !frame.getClassName().endsWith(".Failure"))
            .findFirst().map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber()).orElse("framework");
        org.jboss.logging.Logger.getLogger(Errors.class).infof("control_failure code=%s request_id=%s origin=%s", code, correlation.id(), origin);
        if (error instanceof Failure failure && failure.detail() != null) return Response.status(status).type("application/json").entity(new io.ololabs.toolgate.contracts.ControlErrorDetail(failure.detail(), failure.ids().isEmpty() ? null : failure.ids(), correlation.id())).build();
        var response = Response.status(status).type("application/json").entity(new ErrorEnvelope(code, correlation.id(), status == 423 || status == 503));
        if (status == 401) response.header("WWW-Authenticate", "Bearer"); return response.build();
    }
}
