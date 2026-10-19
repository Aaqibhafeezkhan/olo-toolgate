// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import io.vertx.ext.web.RoutingContext;

/** Human management, authenticated Gateway and certificate-bound client routes are explicit trust boundaries. */
@Path("/api/control/v1/access") @Produces("application/json") @Blocking
public class EnterpriseOperationResource {
    @Inject TenancyConfiguration tenancy;
    @Inject EnterpriseOperations operations;@Inject VerifiedActors actors;@Inject ContractCodec codec;
    @Inject EndpointService endpoints;@Inject PostgresStore store;@Inject RoutingContext routing;
    @Inject io.quarkus.security.identity.SecurityIdentity security;
    @Inject VaultResource vault;
    @Inject DirectoryService directory;
    @Inject ServerPackages packages;@Inject Correlation correlation;
    /** v2 bodies extend these endpoints for Tool Hosts; a v2 body is recognised by a field only it has. */
    private boolean v2(String document,String field){return ((com.fasterxml.jackson.databind.JsonNode)codec.value(body(document))).has(field);}
    @POST @Path("simulate") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response simulate(String document){return reply(directory.simulate(actors.management(),body(document)));}
    @POST @Path("shadow") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response shadow(String document){return reply(directory.shadow(actors.management(),document));}
    @POST @Path("human/catalog") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response humanCatalog(String document){var actor=actors.human();return reply(new HumanRuntime(store,codec,operations,endpoints.relay(),packages).catalog(actor,actors.humanEpoch(),body(document)));}
    @POST @Path("human/invocations") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response humanInvoke(@HeaderParam("Idempotency-Key")String key,String document){var actor=actors.human();return reply(new HumanRuntime(store,codec,operations,endpoints.relay(),packages).invoke(actor,actors.humanEpoch(),body(document),key));}
    @GET @Path("human/invocations/{id}/result") @io.quarkus.security.Authenticated
    public Response humanResult(@PathParam("id")String id){var actor=actors.human();return reply(new HumanRuntime(store,codec,operations,endpoints.relay(),packages).result(actor,actors.humanEpoch(),id));}
    @GET @Path("status") @io.quarkus.security.Authenticated public Response authorityStatus(){var a=actors.management();return reply(store.transaction(a.tenant(),false,tx->{new io.ololabs.toolgate.control.application.ManagementAccess(codec).requireAll(tx.load(),a.userId(),"access_snapshot.read",System.currentTimeMillis());long seq=tx.bundleSequence();var bundle=seq==0?null:tx.bundle(seq);var value=new io.ololabs.toolgate.contracts.EnterpriseAuthorityStatus(tx.load().revision(),tx.enterprise().authorizationEpoch(),seq,bundle==null?null:bundle.directoryRevision(),tx.enterprise().adoptions());return new Store.Reply(200,codec.json(value),tx.load().revision());}));}
    @GET @Path("operations") @io.quarkus.security.Authenticated public Response operationsStatus(){var a=actors.management();return reply(store.transaction(a.tenant(),false,tx->{new io.ololabs.toolgate.control.application.ManagementAccess(codec).requireAll(tx.load(),a.userId(),"access_snapshot.read",System.currentTimeMillis());return new Store.Reply(200,codec.json(tx.enterprise().operationalStatus(System.currentTimeMillis())),tx.load().revision());}));}
    private DirectoryService.Actor gateway(){return actors.service("toolgate-relay-gateway");}
    private DirectoryService.Actor reader(){return security.hasRole("toolgate-relay-gateway")?gateway():actors.human();}
    private Response reply(Store.Reply value){return Response.status(value.status()).header("Cache-Control","no-store").header("ETag","\""+value.revision()+"\"").entity(value.body()).build();}
    private String body(String value){if(value==null||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw Failure.validation();return value;}
    private java.security.cert.X509Certificate peer(){if(!routing.request().isSSL())throw unauthorized();try{return (java.security.cert.X509Certificate)routing.request().sslSession().getPeerCertificates()[0];}catch(Exception invalid){throw unauthorized();}}
    private Ids.TenantId tenant(){return tenancy.defaultId();}
    private static Failure unauthorized(){return new Failure(ErrorCode.UNAUTHORIZED,401,"Direct device certificate required");}
    @GET @Path("health") @RolesAllowed("toolgate-relay-gateway")
    public Response health(){var actor=gateway();store.transaction(actor.tenant(),false,tx->{tx.load();tx.enterprise().authorizationEpoch();return null;});return Response.ok("{\"ready\":true}").header("Cache-Control","no-store").build();}
    @POST @Path("invocations") @Consumes("application/json") @RolesAllowed("toolgate-relay-gateway")
    public Response submit(String document){return reply(operations.submit(gateway(),body(document)));}
    @POST @Path("invocations/reserve") @Consumes("application/json") @RolesAllowed("toolgate-relay-gateway")
    public Response reserve(String document){return reply(v2(document,"audience")?packages.permit(gateway(),document,correlation.id()):operations.reserve(gateway(),body(document)));}
    /** Pull mode: the Gateway hands Control a dispatch for a Tool Host that fetches its calls, then polls for the answer. */
    @POST @Path("dispatches") @Consumes("application/json") @RolesAllowed("toolgate-relay-gateway")
    public Response relay(String document){return reply(packages.relay(gateway(),PackageResource.large(document)));}
    @GET @Path("dispatches/{id}") @RolesAllowed("toolgate-relay-gateway")
    public Response relayed(@PathParam("id")String id){return reply(packages.relayed(gateway(),id));}
    @GET @Path("invocations") @io.quarkus.security.Authenticated
    public Response invocations(@QueryParam("after")String after,@QueryParam("limit")@DefaultValue("50")int limit){return reply(operations.page(actors.human(),after,limit));}
    @POST @Path("invocations/{id}/reconciliation") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response propose(@PathParam("id")String id,@HeaderParam("Idempotency-Key")String key,String document){return reply(operations.proposeReconciliation(actors.human(),id,body(document),key));}
    @GET @Path("reconciliations/{id}") @io.quarkus.security.Authenticated
    public Response reconciliation(@PathParam("id")String id){return reply(operations.reconciliation(actors.human(),id));}
    @POST @Path("reconciliations/{id}/decision") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response reconcile(@PathParam("id")String id,String document){return reply(operations.reconcile(actors.human(),id,body(document)));}
    @GET @Path("invocations/{id}") @io.quarkus.security.Authenticated
    public Response invocation(@PathParam("id")String id){return reply(operations.get(reader(),id));}
    @POST @Path("invocations/{id}/cancel") @io.quarkus.security.Authenticated
    public Response cancel(@PathParam("id")String id,@HeaderParam("If-Match")String etag){if(etag==null||!etag.matches("\"[1-9][0-9]{0,15}\""))throw Failure.validation();long revision=Long.parseLong(etag.substring(1,etag.length()-1));if(revision>9007199254740991L)throw Failure.validation();return reply(operations.cancel(actors.human(),id,revision));}
    @POST @Path("permits/consume") @Consumes("application/json") @PermitAll
    public Response consume(String document){var certificate=peer();java.util.function.Function<Store.Session,String> device=tx->endpoints.authenticate(tx,certificate,System.currentTimeMillis()).deviceId();
        return reply(v2(document,"toolDescriptorDigest")?operations.consumeInvocation(tenant(),device,document):operations.consume(tenant(),device,body(document)));}
    @POST @Path("effects/report") @Consumes("application/json") @PermitAll
    public Response report(@HeaderParam("Toolgate-Fencing-Token")String fencingToken,String document){var certificate=peer();java.util.function.Function<Store.Session,String> device=tx->endpoints.authenticate(tx,certificate,System.currentTimeMillis()).deviceId();
        return reply(v2(document,"fencingToken")?operations.reportInvocation(tenant(),device,document,fencingToken==null?"":fencingToken):operations.report(tenant(),device,body(document)));}
    @GET @Path("invocations/{id}/device-outcome") @PermitAll
    public Response deviceOutcome(@PathParam("id")String id){var certificate=peer();return reply(operations.deviceOutcome(tenant(),tx->endpoints.authenticate(tx,certificate,System.currentTimeMillis()).deviceId(),id));}
    /** Run-response frames from the executor that admitted the invocation; the device certificate and fencing token bind them. */
    @POST @Path("invocations/{id}/events") @Consumes("application/json") @PermitAll
    public Response events(@PathParam("id")String id,String document){var certificate=peer();return reply(new RunEvents(store,codec,operations).ingest(tenant(),tx->endpoints.authenticate(tx,certificate,System.currentTimeMillis()).deviceId(),id,body(document)));}
    /** Authorized cursor replay of an invocation's retained run-response events. */
    @GET @Path("invocations/{id}/events") @io.quarkus.security.Authenticated
    public Response eventPage(@PathParam("id")String id,@QueryParam("after")@DefaultValue("0")long after,@QueryParam("limit")@DefaultValue("200")int limit){return reply(new RunEvents(store,codec,operations).page(actors.human(),id,after,limit));}
    @POST @Path("secrets/deliver") @Consumes("application/json") @PermitAll
    public Response secret(@HeaderParam("Toolgate-Fencing-Token")String fencingToken,String document){var certificate=peer();return reply(vault.service().deliver(tenant(),tx->endpoints.authenticate(tx,certificate,System.currentTimeMillis()).deviceId(),body(document),fencingToken,operations));}
}
