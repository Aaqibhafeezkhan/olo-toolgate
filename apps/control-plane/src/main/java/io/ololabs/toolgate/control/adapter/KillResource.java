// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/** Human review and direct device-certificate delivery have distinct authentication boundaries. */
@Path("/api/control/v1") @Produces("application/json") @Blocking @PermitAll
public class KillResource {
    @Inject PostgresStore store;@Inject ContractCodec codec;@Inject EnterpriseOperations operations;
    @Inject FleetBootstrap fleet;@Inject VerifiedActors actors;@Inject EndpointService endpoints;
    @Inject TenantRequest request;@Inject RoutingContext routing;
    private KillSwitch service(){return new KillSwitch(store,codec,operations,()->fleet.killCrypto());}
    private static Response reply(Store.Reply value){return Response.status(value.status()).header("Cache-Control","no-store")
        .header("ETag","\""+value.revision()+"\"").entity(value.body()).build();}
    private static String body(String value){if(value==null||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw Failure.validation();return value;}
    private Ids.TenantId tenant(){return request.tenant();}
    private java.util.function.Function<Store.Session,EndpointDeviceRecord> device(){
        if(!routing.request().isSSL())throw unauthorized();
        java.security.cert.X509Certificate peer;
        try{peer=(java.security.cert.X509Certificate)routing.request().sslSession().getPeerCertificates()[0];}
        catch(Exception invalid){throw unauthorized();}
        return tx->endpoints.authenticate(tx,peer,System.currentTimeMillis());
    }
    private static Failure unauthorized(){return new Failure(ErrorCode.UNAUTHORIZED,401,"Direct device certificate required");}
    @GET @Path("kill-events/status") @io.quarkus.security.Authenticated
    public Response overview(){return reply(service().overview(actors.management()));}
    @GET @Path("gateways/kills/keys") @jakarta.annotation.security.RolesAllowed("toolgate-relay-gateway")
    public Response gatewayKeys(){return reply(service().gatewayKeys(actors.service("toolgate-relay-gateway")));}
    @GET @Path("gateways/kills") @jakarta.annotation.security.RolesAllowed("toolgate-relay-gateway")
    public Response gatewayState(){return reply(service().gatewayState(actors.service("toolgate-relay-gateway")));}
    @POST @Path("gateways/kills/acknowledgements") @Consumes("application/json") @jakarta.annotation.security.RolesAllowed("toolgate-relay-gateway")
    public Response gatewayAcknowledge(String document){return reply(service().gatewayAcknowledge(actors.service("toolgate-relay-gateway"),body(document)));}
    @GET @Path("kill-events") @io.quarkus.security.Authenticated
    public Response state(){return reply(service().state(actors.management()));}
    @POST @Path("kill-events") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response proposeKill(@HeaderParam("Idempotency-Key")String key,String document){return reply(service().propose(actors.management(),"KILL",body(document),key));}
    @POST @Path("kill-events/recoveries") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response proposeRecovery(@HeaderParam("Idempotency-Key")String key,String document){return reply(service().propose(actors.management(),"RECOVERY",body(document),key));}
    @GET @Path("kill-events/proposals") @io.quarkus.security.Authenticated
    public Response proposals(@QueryParam("after")String after,@QueryParam("limit")@DefaultValue("50")int limit){return reply(service().proposals(actors.management(),after,limit));}
    @GET @Path("kill-events/proposals/{id}") @io.quarkus.security.Authenticated
    public Response proposal(@PathParam("id")String id){return reply(service().read(actors.management(),id));}
    @POST @Path("kill-events/proposals/{id}/decisions") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response decide(@PathParam("id")String id,@HeaderParam("Idempotency-Key")String key,String document){return reply(service().decide(actors.management(),id,body(document),key));}
    @GET @Path("endpoints/kills/keys")
    public Response nativeKeys(){var peer=device();return reply(service().nativeKeys(tenant(),peer));}
    @GET @Path("endpoints/kills")
    public Response nativeState(){var peer=device();return reply(service().nativeState(tenant(),peer));}
    @POST @Path("endpoints/kills/acknowledgements") @Consumes("application/json")
    public Response nativeAcknowledge(String document){var peer=device();return reply(service().nativeAcknowledge(tenant(),peer,body(document)));}
    @GET @Path("tool-hosts/kills/keys")
    public Response hostKeys(){var peer=device();return reply(service().hostKeys(tenant(),peer));}
    @GET @Path("tool-hosts/kills")
    public Response hostState(){var peer=device();return reply(service().hostState(tenant(),peer));}
    @GET @Path("tool-hosts/kills/poll") @io.smallrye.common.annotation.RunOnVirtualThread
    public Response poll(@QueryParam("afterEpoch")long after,@QueryParam("waitMs")@DefaultValue("20000")long wait){var peer=device();return reply(service().poll(tenant(),peer,after,wait));}
    @POST @Path("tool-hosts/kills/acknowledgements") @Consumes("application/json")
    public Response acknowledge(String document){var peer=device();return reply(service().acknowledge(tenant(),peer,body(document)));}
}
