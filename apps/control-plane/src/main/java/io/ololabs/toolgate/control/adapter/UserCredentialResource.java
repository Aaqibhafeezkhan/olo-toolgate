// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.UserCredentials;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 8693 token exchange and session revocation for a trusted platform backend (OLO, M3.4).
 *
 * <p>The caller authenticates to Control with its own service JWT carrying the {@value #ROLE} role; that is the client
 * authentication. Responses are never cached.
 */
@Path("/api/control/v1/oauth") @Produces("application/json") @Blocking
public class UserCredentialResource {
    static final String ROLE = "toolgate-olo-token-exchange";

    @Inject UserCredentialBootstrap bootstrap;
    @Inject VerifiedActors actors;
    @Inject ContractCodec codec;

    @POST @Path("token-exchange") @Consumes("application/x-www-form-urlencoded") @RolesAllowed(ROLE)
    public Response exchange(@FormParam("grant_type") String grantType,
                             @FormParam("subject_token") String subjectToken,
                             @FormParam("subject_token_type") String subjectTokenType,
                             @FormParam("audience") String audience,
                             @FormParam("requested_token_type") String requestedTokenType) {
        var credentials = bootstrap.credentials().orElse(null);
        if (credentials == null) return error(404, "invalid_request", "user credential exchange is disabled");
        try {
            var issued = credentials.exchange(actors.service(ROLE), grantType, subjectToken, subjectTokenType, audience, requestedTokenType);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", issued.accessToken());
            body.put("issued_token_type", UserCredentials.JWT_TOKEN_TYPE);
            body.put("token_type", "Bearer");
            body.put("expires_in", issued.expiresInSeconds());
            return Response.ok(codec.json(body)).header("Cache-Control", "no-store").header("Pragma", "no-cache").build();
        } catch (UserCredentials.Refused refused) {
            return error(400, refused.error(), refused.getMessage());
        }
    }

    /** Body {@code {"subject": "<platform user id>"}}; ends every session of that user. */
    @POST @Path("sessions/revoke") @Consumes("application/json") @RolesAllowed(ROLE)
    public Response revoke(String document) {
        var credentials = bootstrap.credentials().orElse(null);
        if (credentials == null) return error(404, "invalid_request", "user credential exchange is disabled");
        if (document == null || document.length() > 4096) return error(400, "invalid_request", "body too large");
        try {
            var node = (com.fasterxml.jackson.databind.JsonNode) codec.value(document);
            var subject = node.path("subject");
            credentials.revoke(actors.service(ROLE), subject.isTextual() ? subject.asText() : null);
            return Response.noContent().header("Cache-Control", "no-store").build();
        } catch (UserCredentials.Refused refused) {
            return error(400, refused.error(), refused.getMessage());
        }
    }

    private Response error(int status, String error, String description) {
        return Response.status(status).entity(codec.json(Map.of("error", error, "error_description", description)))
                .header("Cache-Control", "no-store").build();
    }
}
