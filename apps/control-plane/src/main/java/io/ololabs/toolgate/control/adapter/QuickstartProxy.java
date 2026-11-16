// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Fixed loopback composition routes for the direct TLS enrollment/browser origin.
 * No device identity is forwarded in headers. Device enrollment/check-in remains
 * in the existing direct-TLS adapter. Absent Quickstart, every route is closed.
 */
@Path("/") @PermitAll @Blocking @Produces("application/json")
public class QuickstartProxy {
    @Inject org.eclipse.microprofile.config.Config config;
    @Inject RoutingContext routing;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private Response relay(String path,String method,String document,int port) {
        if(!config.getOptionalValue("toolgate.quickstart.enabled",Boolean.class).orElse(false))throw new Failure(ErrorCode.NOT_FOUND,404,"Unavailable route");
        if(!routing.request().isSSL())throw new Failure(ErrorCode.UNAUTHORIZED,401,"Direct TLS required");
        var host=routing.request().getHeader("Host");
        if(host==null || !host.matches("(localhost|127[.]0[.]0[.]1)(:[0-9]{1,5})?"))throw new Failure(ErrorCode.FORBIDDEN,403,"Local origin required");
        var origin=routing.request().getHeader("Origin");
        if(origin!=null && !origin.equals("https://"+host))throw new Failure(ErrorCode.FORBIDDEN,403,"Invalid origin");
        var bytes=document==null?new byte[0]:document.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length>(path.equals("/v1/chat/completions")?98304:65536))throw Failure.validation();
        try {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(path.equals("/mcp")||path.equals("/v1/chat/completions")?35:12))
                .header("Content-Type","application/json").method(method,bytes.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));
            var authorization=routing.request().getHeader("Authorization");
            if(authorization==null) authorization=routing.get(QuickstartRuntimeAuthentication.BEARER);
            if(authorization!=null){if(authorization.length()>16384)throw Failure.validation();request.header("Authorization",authorization);}
            for(var header:java.util.List.of("X-Request-ID","Idempotency-Key","traceparent","Accept","MCP-Protocol-Version","MCP-Method","MCP-Name","X-ToolGate-Run-Id")){
                var value=routing.request().getHeader(header);
                if(value!=null){if(value.length()>(header.equals("MCP-Name")?1024:128))throw Failure.validation();request.header(header,value);}
            }
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;try(var stream=response.body()){body=stream.readNBytes(131073);}
            if(body.length>131072)throw Failure.unavailable();
            var result=Response.status(response.statusCode()).header("Cache-Control","no-store").entity(new String(body,java.nio.charset.StandardCharsets.UTF_8));
            response.headers().firstValue("X-Request-ID").ifPresent(id->result.header("X-Request-ID",id));
            return result.build();
        }catch(InterruptedException failure){Thread.currentThread().interrupt();throw Failure.unavailable();}
        catch(java.io.IOException failure){throw Failure.unavailable();}
    }
    @GET @Path("api/quickstart/v1/{operation:status|tools|vault}")
    public Response get(@PathParam("operation")String operation){return relay("/api/quickstart/v1/"+operation,"GET",null,8080);}
    @POST @Path("api/quickstart/v1/{operation:login|invoke|vault}") @Consumes("application/json")
    public Response post(@PathParam("operation")String operation,String document){return relay("/api/quickstart/v1/"+operation,"POST",document,8080);}
    @POST @Path("access/invocations") @Consumes("application/json")
    public Response authorize(String document){return relay("/access/invocations","POST",document,8081);}
    @POST @Path("access/catalog") @Consumes("application/json")
    public Response catalog(String document){return relay("/access/catalog","POST",document,8081);}
    @POST @Path("mcp") @Consumes("application/json")
    public Response mcp(String document){return relay("/mcp","POST",document,8081);}
    @POST @Path("v1/chat/completions") @Consumes("application/json")
    public Response workflowInference(String document){return relay("/v1/chat/completions","POST",document,8081);}
}
