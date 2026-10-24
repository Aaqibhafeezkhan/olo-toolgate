// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.ServiceAudience;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.security.cert.X509Certificate;

/** Executor minting requires a direct device certificate; service endpoints require dedicated service roles. */
@Path("/api/control/v1/access/service-permits") @Produces("application/json") @Blocking
public class ServicePermitResource {
    @Inject PostgresStore store; @Inject ContractCodec codec; @Inject EnterpriseOperations operations;
    @Inject VerifiedActors actors; @Inject TenantRequest tenant; @Inject EndpointService endpoints;
    @Inject io.vertx.ext.web.RoutingContext routing;
    private ServicePermits permits(){return new ServicePermits(store,codec,operations);}
    private Response reply(Store.Reply r){return Response.status(r.status()).header("Cache-Control","no-store").entity(r.body()).build();}
    private DirectoryService.Actor service(String audience){
        if(!java.util.Set.of("olo-memory","olo-rag").contains(audience))throw ManagementAccess.denied();
        return actors.service("toolgate-service-"+audience);
    }
    @POST @Path("issue") @Consumes("application/json") @jakarta.annotation.security.PermitAll
    public Response issue(String body){
        try {
            if(!routing.request().isSSL())throw ManagementAccess.denied();
            var cert=(X509Certificate)routing.request().sslSession().getPeerCertificates()[0];
            String key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(cert.getPublicKey().getEncoded()));
            return reply(permits().issue(tenant.tenant(),tx->endpoints.authenticate(tx,cert,System.currentTimeMillis()).deviceId(),key,body));
        } catch(java.security.GeneralSecurityException|javax.net.ssl.SSLPeerUnverifiedException invalid){throw ManagementAccess.denied();}
    }
    @POST @Path("{audience}/consume") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response consume(@PathParam("audience") String audience,String body){return reply(permits().consume(service(audience),ServiceAudience.valueOf(audience.replace('-','_')),body));}
    @GET @Path("{audience}/epoch") @io.quarkus.security.Authenticated
    public Response epoch(@PathParam("audience") String audience){return reply(permits().epoch(service(audience)));}
    @GET @Path("{audience}/jwks") @io.quarkus.security.Authenticated
    public Response keys(@PathParam("audience") String audience){service(audience);return Response.ok(codec.json(permits().keys())).header("Cache-Control","no-store").build();}
}
