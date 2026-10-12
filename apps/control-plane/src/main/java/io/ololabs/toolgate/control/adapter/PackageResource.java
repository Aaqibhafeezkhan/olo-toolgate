// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import io.vertx.ext.web.RoutingContext;

/** Server packages for administrators, and their content for Tool Hosts, which present their device certificate. */
@Path("/api/control/v1") @Produces("application/json") @Blocking @PermitAll
public class PackageResource {
    public static final String PACKAGE_MEDIA_TYPE = "application/vnd.ololabs.toolgate.package+zip";
    @Inject ServerPackages packages;@Inject VerifiedActors actors;@Inject EndpointService endpoints;@Inject RoutingContext routing;@Inject Correlation correlation;

    private static Response reply(Store.Reply reply){return reply.status()==204?Response.noContent().header("Cache-Control","no-store").build():Response.status(reply.status()).header("Cache-Control","no-store").entity(reply.body()).build();}
    private static String body(String value){if(value==null||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw Failure.validation();return value;}
    /** A tool's result frame may carry up to the platform's 4 MiB output ceiling. */
    static String large(String value){if(value==null||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8388608)throw Failure.validation();return value;}
    private java.util.function.Function<Store.Session,io.ololabs.toolgate.contracts.EndpointDeviceRecord> device(){
        if(!routing.request().isSSL())throw new Failure(ErrorCode.UNAUTHORIZED,401,"Direct TLS required");
        java.security.cert.X509Certificate peer;
        try{peer=(java.security.cert.X509Certificate)routing.request().sslSession().getPeerCertificates()[0];}catch(Exception failure){throw new Failure(ErrorCode.UNAUTHORIZED,401,"Device certificate required");}
        return tx->endpoints.authenticate(tx,peer,System.currentTimeMillis());
    }
    private io.ololabs.toolgate.control.domain.Ids.TenantId tenant(){return new io.ololabs.toolgate.control.domain.Ids.TenantId(org.eclipse.microprofile.config.ConfigProvider.getConfig().getValue("toolgate.control.endpoint.tenant-id",String.class));}

    @POST @Path("packages/uploads") @Consumes(PACKAGE_MEDIA_TYPE) @io.quarkus.security.Authenticated
    public Response upload(java.io.InputStream input){
        byte[] data;
        try{data=input.readNBytes(PackageArchive.MAX_PACKAGE_BYTES+1);}catch(java.io.IOException failure){throw Failure.validation();}
        if(data.length>PackageArchive.MAX_PACKAGE_BYTES)throw new Failure(ErrorCode.VALIDATION,413,"Package too large");
        return reply(packages.upload(actors.management(),data,correlation.id()));
    }
    @GET @Path("packages") @io.quarkus.security.Authenticated public Response versions(){return reply(packages.versions(actors.management()));}
    @GET @Path("packages/deployments") @io.quarkus.security.Authenticated public Response deployments(){return reply(packages.deployments(actors.management()));}
    @POST @Path("packages/deployments") @Consumes("application/json") @io.quarkus.security.Authenticated
    public Response deploy(String document){return reply(packages.deploy(actors.management(),body(document),correlation.id()));}

    @POST @Path("tool-hosts/registrations") @Consumes("application/json")
    public Response register(String document){return reply(packages.register(tenant(),device(),body(document),correlation.id()));}
    @GET @Path("tool-hosts/assignments") public Response assignments(){return reply(packages.assignments(tenant(),device()));}
    @GET @Path("tool-hosts/packages/{digest}") @Produces(PACKAGE_MEDIA_TYPE)
    public Response content(@PathParam("digest")String digest){return Response.ok(packages.content(tenant(),device(),digest)).header("Cache-Control","no-store").type(PACKAGE_MEDIA_TYPE).build();}
    @GET @Path("tool-hosts/documents/{digest}") public Response document(@PathParam("digest")String digest){return reply(packages.document(tenant(),device(),digest));}

    /** Pull mode: the host's next call, its admission and its result, over the host's own connection. */
    @POST @Path("tool-hosts/dispatches/next") public Response next(){return reply(packages.next(tenant(),device()));}
    @POST @Path("tool-hosts/dispatches/{id}/admission") @Consumes("application/json")
    public Response admission(@PathParam("id")String id,String document){return reply(packages.admission(tenant(),device(),id,body(document)));}
    @POST @Path("tool-hosts/dispatches/{id}/result") @Consumes("application/json")
    public Response result(@PathParam("id")String id,String document){return reply(packages.result(tenant(),device(),id,large(document)));}
}
