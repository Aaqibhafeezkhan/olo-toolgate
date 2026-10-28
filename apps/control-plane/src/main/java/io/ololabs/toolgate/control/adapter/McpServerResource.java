// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/** MCP proxy registrations for administrators of the routed tenant, and their scans for the Tool Hosts that run them,
 *  which present their device certificate (ADR 030). */
@Path("/api/control/v1") @Produces("application/json") @Blocking @PermitAll
public class McpServerResource {
    @Inject TenantRequest request;@Inject McpServers servers;@Inject VerifiedActors actors;@Inject EndpointService endpoints;
    @Inject RoutingContext routing;@Inject Correlation correlation;@Inject PostgresStore store;@Inject ContractCodec codec;@Inject VaultKeyBootstrap keys;

    private static Response reply(Store.Reply reply){return reply.status()==204?Response.noContent().header("Cache-Control","no-store").build():Response.status(reply.status()).header("Cache-Control","no-store").entity(reply.body()).build();}
    private static String body(String value){if(value==null||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw Failure.validation();return value;}
    private java.security.cert.X509Certificate peer(){
        if(!routing.request().isSSL())throw new Failure(ErrorCode.UNAUTHORIZED,401,"Direct TLS required");
        try{return (java.security.cert.X509Certificate)routing.request().sslSession().getPeerCertificates()[0];}catch(Exception failure){throw new Failure(ErrorCode.UNAUTHORIZED,401,"Device certificate required");}
    }
    private java.util.function.Function<Store.Session,io.ololabs.toolgate.contracts.EndpointDeviceRecord> device(){var peer=peer();return tx->endpoints.authenticate(tx,peer,System.currentTimeMillis());}

    @POST @Path("mcp-servers") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response register(@HeaderParam("Idempotency-Key")String key,String document){return reply(servers.register(actors.management(),body(document),key,correlation.id()));}
    @GET @Path("mcp-servers") @io.quarkus.security.Authenticated public Response list(){return reply(servers.servers(actors.management()));}
    @GET @Path("mcp-servers/{slug}") @io.quarkus.security.Authenticated public Response server(@PathParam("slug")String slug){return reply(servers.server(actors.management(),slug));}
    @POST @Path("mcp-servers/{slug}/scans") @io.quarkus.security.Authenticated
    public Response scan(@PathParam("slug")String slug,@HeaderParam("Idempotency-Key")String key){return reply(servers.scan(actors.management(),slug,key,correlation.id()));}
    @DELETE @Path("mcp-servers/{slug}") @io.quarkus.security.Authenticated
    public Response unregister(@PathParam("slug")String slug,@HeaderParam("Idempotency-Key")String key){return reply(servers.unregister(actors.management(),slug,key,correlation.id()));}
    @POST @Path("mcp-servers/{slug}/approvals") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response approve(@PathParam("slug")String slug,@HeaderParam("Idempotency-Key")String key,String document){return reply(servers.approve(actors.management(),slug,body(document),key,correlation.id()));}
    @GET @Path("mcp-servers/{slug}/approvals") @io.quarkus.security.Authenticated public Response approvals(@PathParam("slug")String slug){return reply(servers.approvals(actors.management(),slug));}
    @GET @Path("mcp-servers/{slug}/snapshots/{revision}") @io.quarkus.security.Authenticated
    public Response snapshot(@PathParam("slug")String slug,@PathParam("revision")long revision){return reply(servers.snapshot(actors.management(),slug,revision));}

    /** Pull mode for scans: the host's next scan with its permit, the permit's consumption, the scan's secret, and its outcome. */
    @POST @Path("tool-hosts/mcp-scans/next") public Response next(){return reply(servers.next(request.tenant(),device()));}
    @POST @Path("tool-hosts/mcp-scans/{id}/consume") @Consumes("application/json")
    public Response consume(@PathParam("id")String id,String document){return reply(servers.consume(request.tenant(),device(),id,body(document)));}
    @POST @Path("tool-hosts/mcp-scans/{id}/secret")
    public Response secret(@PathParam("id")String id,@HeaderParam("Toolgate-Fencing-Token")String fencingToken){
        var peer=peer();
        return reply(new VaultService(store,codec,keys.required()).deliverScan(request.tenant(),tx->endpoints.authenticate(tx,peer,System.currentTimeMillis()).deviceId(),id,fencingToken,servers));
    }
    @POST @Path("tool-hosts/mcp-scans/{id}/result") @Consumes("application/json")
    public Response result(@PathParam("id")String id,String document){return reply(servers.result(request.tenant(),device(),id,PackageResource.large(document)));}
    @POST @Path("tool-hosts/mcp-refreshes") @Consumes("application/json")
    public Response refresh(String document){return reply(servers.refreshRequested(request.tenant(),device(),body(document)));}
    @POST @Path("tool-hosts/mcp-quarantines") @Consumes("application/json")
    public Response quarantine(String document){return reply(servers.quarantine(request.tenant(),device(),body(document)));}
    @POST @Path("tool-hosts/mcp-scans/{id}/failure") @Consumes("application/json")
    public Response failure(@PathParam("id")String id,String document){return reply(servers.failure(request.tenant(),device(),id,body(document)));}
}
