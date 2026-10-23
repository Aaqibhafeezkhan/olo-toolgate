// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.DirectoryService;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.Store;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/** Versioned organization management adapter. Directory records never supply authentication roles. */
@Path("/api/control/v1")
@Produces("application/json")
@Blocking
@io.quarkus.security.Authenticated
public class ControlResource {
    private static final String COLLECTION = "{kind:users|teams|agents|tools|policies|devices|roles|device-groups|agent-groups|tool-groups|grants|delegations|agent-delegations|bindings|extractors|workload-bindings|identity-bindings|device-evidence}";
    @Inject DirectoryService service;
    @Inject io.ololabs.toolgate.control.application.ConfigurationChanges changes;
    @Inject io.ololabs.toolgate.control.application.BundleService bundles;
    @Inject Correlation correlation;
    @Inject MeterRegistry metrics;
    @Inject VerifiedActors actors;
    @Inject PostgresStore store;@Inject ContractCodec codec;
    private DirectoryService.Actor actor() { return actors.management(); }
    private Response response(Store.Reply reply, String operation, String kind) {
        metrics.counter("toolgate_control_operations_total", "operation", operation, "kind", kind).increment();
        var response = Response.status(reply.status()).header("ETag", "\"" + reply.revision() + "\"");
        if (reply.status() != 204) response.entity(reply.body()); return response.build();
    }
    private long expected(String value) {
        if (value == null || !value.matches("\"(0|[1-9][0-9]{0,15})\"")) throw Failure.validation();
        try { var result = Long.parseLong(value.substring(1, value.length() - 1)); if (result > 9007199254740991L) throw Failure.validation(); return result; }
        catch (NumberFormatException e) { throw Failure.validation(); }
    }
    @Inject io.ololabs.toolgate.control.application.ServerSettingsService settings;
    @GET @Path("settings")
    public Response settings(@QueryParam("section") String section) {
        return Response.fromResponse(response(settings.get(actor(),section), "READ", "settings")).header("Cache-Control", "no-store").build();
    }
    @PUT @Path("settings") @Consumes("application/json")
    public Response updateSettings(@HeaderParam("Idempotency-Key") String key, @HeaderParam("If-Match") String revision, String document) {
        if (document == null || document.length() > 16384) throw Failure.validation();
        return Response.fromResponse(response(settings.update(actor(), document, expected(revision), key, correlation.id()), "UPDATE", "settings")).header("Cache-Control", "no-store").build();
    }
    @PATCH @Path("settings") @Consumes("application/merge-patch+json")
    public Response patchSettings(@HeaderParam("Idempotency-Key") String key, @HeaderParam("If-Match") String revision, String document) {
        if (document == null || document.length() > 16384) throw Failure.validation();
        return Response.fromResponse(response(settings.patch(actor(), document, expected(revision), key, correlation.id()), "UPDATE", "settings")).header("Cache-Control", "no-store").build();
    }
    @GET @Path("review-policy")
    public Response reviewPolicy() {
        return Response.fromResponse(response(settings.reviewPolicy(actors.human()), "READ", "review-policy")).header("Cache-Control", "no-store").build();
    }
    @POST @Path("workload-bindings/{id}/credential") @Consumes("application/json")
    public Response rotateCredential(@PathParam("id") String id, @HeaderParam("Idempotency-Key") String key, String document) {
        if (document == null || document.length() > 16384) throw Failure.validation();
        return Response.fromResponse(response(service.rotateCredential(actor(), id, document, key, correlation.id()), "ROTATE", "workload-bindings")).header("Cache-Control", "no-store").build();
    }
    @POST @Path("users/{id}/{change:enable|disable}")
    public Response userEnabled(@PathParam("id") String id, @PathParam("change") String change, @HeaderParam("Idempotency-Key") String key) {
        return response(service.userEnabled(actor(), id, change.equals("enable"), key, correlation.id()), "UPDATE", "users");
    }
    @GET @Path("admin-session") @io.quarkus.security.Authenticated
    public String adminSession() {
        var identity=actor();
        return codec.json(store.transaction(identity.tenant(),false,tx->{var access=new io.ololabs.toolgate.control.application.ManagementAccess(codec);var directory=tx.load();var profile=tx.runtimeProfile();return new io.ololabs.toolgate.contracts.AdminSession(access.capabilities(directory,identity.userId(),System.currentTimeMillis(),io.ololabs.toolgate.control.application.ConsoleDeviceSessions.current(tx,codec,identity.sessionId(),identity.userId(),System.currentTimeMillis())).stream().sorted().toList(),access.access(directory,identity.userId()).portalRole(),tx.bootstrapAdminUserId(),identity.tenant().value(),profile==null?null:profile.organization());}));
    }
    @GET @Path("bundles/current") @io.quarkus.security.Authenticated
    public Response currentBundle() {
        return Response.fromResponse(response(bundles.current(actors.bundleReader()), "READ", "bundle")).header("Cache-Control", "no-store").build();
    }
    @GET @Path("bundles/versions/{sequence}")
    public Response bundle(@PathParam("sequence") long sequence) {
        if (sequence < 1) throw Failure.validation();
        return Response.fromResponse(response(bundles.get(actors.bundleReader(), sequence), "READ", "bundle")).header("Cache-Control", "no-store").build();
    }
    @POST @Path("bundles/publish") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response publishBundle(@HeaderParam("Idempotency-Key") String key, String document) {
        if (codec.model(document, io.ololabs.toolgate.contracts.BundlePublishRequest.class).rollbackOf() != null) throw Failure.validation();
        return response(bundles.publish(actor(), document, key, correlation.id()), "PUBLISH", "bundle");
    }
    @POST @Path("bundles/rollback") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response rollbackBundle(@HeaderParam("Idempotency-Key") String key, String document) {
        if (codec.model(document, io.ololabs.toolgate.contracts.BundlePublishRequest.class).rollbackOf() == null) throw Failure.validation();
        return response(bundles.publish(actor(), document, key, correlation.id()), "ROLLBACK", "bundle");
    }
    @GET @Path(COLLECTION)
    public Response page(@PathParam("kind") String kind, @QueryParam("cursor") String cursor, @QueryParam("limit") @DefaultValue("50") int limit) {
        return response(service.page(actor(), Kind.path(kind), cursor, limit), "READ", kind);
    }
    @GET @Path(COLLECTION + "/{id:.+}")
    public Response get(@PathParam("kind") String kind, @PathParam("id") String id) {
        return response(service.get(actor(), Kind.path(kind), id), "READ", kind);
    }
    @POST @Path(COLLECTION) @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response create(@PathParam("kind") String kind, @HeaderParam("Idempotency-Key") String key, String document) {
        // Canonical validation happens before extracting the identity used in the application use case.
        var entry = codec.entry(Kind.path(kind), document);
        return response(changes.mutate(actor(),command(Kind.path(kind),entry.id().value(),"CREATE",document,0),key,correlation.id()), "CREATE", kind);
    }
    @GET @Path("{entity:users|agents|tools|devices}/{id:.+}/groups")
    public Response memberships(@PathParam("entity") String entity,@PathParam("id") String id) { return response(service.memberships(actor(),Kind.path(entity),id),"READ","memberships"); }
    @GET @Path("{entity:users|agents|tools|devices}/{id:.+}/effective-access")
    public Response effectiveAccess(@PathParam("entity")String entity,@PathParam("id")String id){return response(new io.ololabs.toolgate.control.application.EffectiveAccess(store,codec).get(actor(),Kind.path(entity),id),"READ","effective-access");}
    @PUT @Path("{entity:users|agents|tools|devices}/{id:.+}/groups") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response memberships(@PathParam("entity") String entity,@PathParam("id") String id,@HeaderParam("If-Match") String etag,@HeaderParam("Idempotency-Key") String key,String document) {
        return response(changes.mutate(actor(),command(Kind.path(entity),id,"MEMBERSHIPS",document,expected(etag)),key,correlation.id()),"UPDATE","memberships");
    }
    @GET @Path("openapi")
    public String openapi() { actor(); return codec.openapi(); }
    @PUT @Path(COLLECTION + "/{id:.+}") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response update(@PathParam("kind") String kind, @PathParam("id") String id, @HeaderParam("If-Match") String etag,
                           @HeaderParam("Idempotency-Key") String key, String document) {
        return response(changes.mutate(actor(),command(Kind.path(kind),id,"UPDATE",document,expected(etag)),key,correlation.id()), "UPDATE", kind);
    }
    @DELETE @Path(COLLECTION + "/{id:.+}") @io.quarkus.security.Authenticated
    public Response delete(@PathParam("kind") String kind, @PathParam("id") String id, @HeaderParam("If-Match") String etag,
                           @HeaderParam("Idempotency-Key") String key) {
        return response(changes.mutate(actor(),command(Kind.path(kind),id,"DELETE","",expected(etag)),key,correlation.id()), "DELETE", kind);
    }
    @GET @Path("config/export") @Produces({"application/json", "application/yaml"})
    public Response export(@HeaderParam("Accept") @DefaultValue("application/json") String accept) {
        if (!accept.equals("application/json") && !accept.equals("application/yaml") && !accept.equals("*/*")) throw new NotAcceptableException();
        var yaml = accept.equals("application/yaml");
        return Response.fromResponse(response(service.export(actor(), yaml), "EXPORT", "config")).type(yaml ? "application/yaml" : "application/json").build();
    }
    @POST @Path("config/import") @Consumes({"application/json", "application/yaml"}) @io.quarkus.security.Authenticated
    public Response importConfig(@HeaderParam("Content-Type") String type, @HeaderParam("If-Match") String etag,
                                 @HeaderParam("Idempotency-Key") String key, String document) {
        var identity=actor();boolean yaml=type!=null&&type.split(";",2)[0].trim().equals("application/yaml");var input=codec.input(document,yaml,identity.tenant());if(input.dryRun())return response(service.importConfig(identity,document,yaml,expected(etag),key,correlation.id()),"PREVIEW","config");
        var json=codec.document(identity.tenant(),new io.ololabs.toolgate.control.application.Codec.Import(input.directory(),input.replace(),false,input.settings(),input.requiredIdentities(),input.identityResolution(),input.removeUnlistedMemberships()));return response(changes.mutate(identity,command(Kind.USER,"configuration-import","IMPORT",json,expected(etag)),key,correlation.id()),"PROPOSE","config");
    }
    private io.ololabs.toolgate.contracts.EnterpriseConfigurationCommand command(Kind kind,String id,String op,String document,long revision){return new io.ololabs.toolgate.contracts.EnterpriseConfigurationCommand(io.ololabs.toolgate.contracts.EnterpriseConfigurationOperation.valueOf(op),io.ololabs.toolgate.contracts.ControlEntityKind.valueOf(kind.name()),id,document,revision);}
    @GET @Path("configuration-changes") public Response configurationChanges(@QueryParam("after")String after){return response(changes.page(actor(),after),"READ","configuration-change");}
    @GET @Path("configuration-changes/{id}") public Response configurationChange(@PathParam("id")String id){return response(changes.get(actor(),id),"READ","configuration-change");}
    @POST @Path("configuration-changes/{id}/transition") @Consumes("application/json") public Response configurationTransition(@PathParam("id")String id,@HeaderParam("Idempotency-Key")String key,String document){return response(changes.transition(actor(),id,document,key,correlation.id()),"REVIEW","configuration-change");}
    @GET @Path("audit") @io.quarkus.security.Authenticated
    public Response audit(@QueryParam("cursor") @DefaultValue("0") long cursor, @QueryParam("limit") @DefaultValue("50") int limit) {
        return response(service.audit(actor(), cursor, limit), "READ", "audit");
    }
}
