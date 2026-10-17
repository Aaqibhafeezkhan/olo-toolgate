// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import io.vertx.ext.web.RoutingContext;

/** Direct TLS peer authentication; proxy headers and request device assertions cannot authenticate. */
@Path("/api/control/v1/endpoint") @Produces("application/json") @Blocking @PermitAll
public class EndpointResource {
    @Inject EndpointService service;
    @Inject RoutingContext routing;

    @Inject Correlation correlation;
    @Inject MeterRegistry metrics;
    private void tls(){if(!routing.request().isSSL())throw new Failure(ErrorCode.UNAUTHORIZED,401,"Direct TLS required");}
    private java.security.cert.X509Certificate peer(){
        tls();try{var certificates=routing.request().sslSession().getPeerCertificates();if(certificates.length==0)throw new IllegalArgumentException();return (java.security.cert.X509Certificate)certificates[0];}
        catch(Exception failure){throw new Failure(ErrorCode.UNAUTHORIZED,401,"Device certificate required");}
    }
    @Inject VerifiedActors actors;
    private DirectoryService.Actor actor(){tls();return actors.human();}

    private String user(){return actors.human().userId();}
    private String body(String body){if(body==null||body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw Failure.validation();return body;}
    private String ipAddress(){var address=routing.request().remoteAddress().hostAddress();return address==null?null:address.split("%",2)[0];}
    private Response response(Store.Reply reply,String operation){metrics.counter("toolgate_control_endpoint_operations_total","operation",operation).increment();return Response.status(reply.status()).header("Cache-Control","no-store").header("ETag","\""+reply.revision()+"\"").entity(reply.body()).build();}
    @POST @Path("enrollments") @Consumes("application/json") public Response start(String document){tls();return response(service.start(body(document),correlation.id()),"START");}
    @POST @Path("ping") @Consumes("application/json") public Response ping(String document){tls();return response(service.ping(body(document)),"PING");}
    @POST @Path("enrollments/request-approval") @Consumes("application/json") public Response requestApproval(String document){tls();return response(service.requestApproval(body(document),correlation.id()),"REQUEST_APPROVAL");}
    @GET @Path("enrollments") @io.quarkus.security.Authenticated public Response pending(){return response(service.pending(actor(),user()),"LIST");}
    @POST @Path("enrollments/poll") @Consumes("application/json") public Response poll(String document){tls();return response(service.poll(body(document),correlation.id()),"POLL");}
    @POST @Path("enrollments/lookup") @Consumes("application/json") @io.quarkus.security.Authenticated public Response lookup(String document){return response(service.lookup(actor(),user(),body(document)),"LOOKUP");}
    @POST @Path("console-device/challenge") @Consumes("application/json") @io.quarkus.security.Authenticated public Response consoleChallenge(String document){return response(service.consoleChallenge(actor(),body(document),correlation.id()),"CONSOLE_DEVICE_CHALLENGE");}
    @POST @Path("console-device/verify") @Consumes("application/json") @io.quarkus.security.Authenticated public Response consoleVerify(String document){return response(service.consoleVerify(actor(),body(document),correlation.id()),"CONSOLE_DEVICE_VERIFY");}
    @GET @Path("enrollments/review") @io.quarkus.security.Authenticated public Response review(@QueryParam("code")String code){return response(service.review(actor(),user(),code),"REVIEW");}
    @POST @Path("enrollments/decision") @Consumes("application/json") @io.quarkus.security.Authenticated public Response decide(@HeaderParam("Idempotency-Key")String key,String document){return response(service.decide(actor(),user(),body(document),key,correlation.id()),"DECIDE");}
    @POST @Path("check-in") @Consumes("application/json") public Response checkIn(@HeaderParam("X-ToolGate-System-Name")String systemName,String document){return response(service.checkIn(peer(),body(document),correlation.id(),systemName,ipAddress()),"CHECK_IN");}
    @GET @Path("devices/{id}") @io.quarkus.security.Authenticated public Response device(@PathParam("id")String id){return response(service.device(actor(),id),"DEVICE");}
    @GET @Path("devices") @io.quarkus.security.Authenticated public Response devices(){return response(service.devices(actor()),"DEVICES");}
    @POST @Path("devices/{id}/approval") @Consumes("application/json") @io.quarkus.security.Authenticated public Response approval(@PathParam("id")String id,@HeaderParam("Idempotency-Key")String key,String document){return response(service.approval(actor(),user(),id,body(document),key,correlation.id()),"APPROVAL");}
    @POST @Path("devices/{id}/enabled") @Consumes("application/json") @io.quarkus.security.Authenticated public Response enabled(@PathParam("id")String id,@HeaderParam("Idempotency-Key")String key,String document){return response(service.enabled(actor(),user(),id,body(document),key,correlation.id()),"ENABLED");}
    @POST @Path("devices/{id}/revoke") @Consumes("application/json") @io.quarkus.security.Authenticated public Response revoke(@PathParam("id")String id,@HeaderParam("Idempotency-Key")String key,String document){return response(service.revoke(actor(),id,body(document),key,correlation.id()),"REVOKE");}
    @POST @Path("devices/{id}/request-approval") @Consumes("application/json") @io.quarkus.security.Authenticated public Response requestApproval(@PathParam("id")String id,@HeaderParam("Idempotency-Key")String key,String document){return response(service.requestApproval(actor(),user(),id,body(document),key,correlation.id()),"REQUEST_APPROVAL");}
}
