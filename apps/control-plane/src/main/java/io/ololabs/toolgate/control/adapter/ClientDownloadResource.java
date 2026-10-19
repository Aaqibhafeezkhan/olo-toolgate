// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import io.smallrye.common.annotation.Blocking;

/** Anonymous installation entry points. Enrollment and organization APIs keep their existing authorization. */
@Path("/") @PermitAll @Blocking
public class ClientDownloadResource {
    @Inject ClientArtifacts artifacts;
    @Inject org.eclipse.microprofile.config.Config config;
    @Inject EndpointBootstrap factory; @Inject TenantRequest request;
    @Inject io.ololabs.toolgate.control.application.EndpointService endpoints;
    @GET public Response home(){return Response.seeOther(java.net.URI.create("/console/")).build();}
    @GET @Path("api/public/v1/clients") @Produces("application/json")
    public Response manifest(){return Response.ok(artifacts.manifest()).header("Cache-Control","no-store").build();}
    @GET @Path("api/public/v1/installers") @Produces("application/json")
    public Response installers(){return Response.ok(artifacts.installers()).header("Cache-Control","no-store").build();}
    @GET @Path("api/public/v1/clients/extension") @Produces("application/json")
    public Response extension(){return Response.ok(artifacts.extension()).header("Cache-Control","no-store").build();}
    private String serverUrl(){
        var profile=factory.profile(request.tenant()); if(profile==null)throw io.ololabs.toolgate.control.application.Failure.unavailable(); var value=profile.controlUrl();
        var uri=java.net.URI.create(value);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalStateException("Invalid endpoint gateway URL");
        return value.endsWith("/")?value.substring(0,value.length()-1):value;
    }
    @GET @Path("api/public/v1/clients/configuration") @Produces("application/json")
    public Response configuration(){return Response.ok(java.util.Map.of("serverUrl",serverUrl())).header("Cache-Control","no-store").build();}
    @GET @Path("api/public/v1/clients/local-trust") @Produces("application/json")
    public Response localTrust(){
        if(!config.getOptionalValue("toolgate.quickstart.enabled",Boolean.class).orElse(false))throw new NotFoundException();
        return Response.ok(java.util.Map.of("serverUrl",serverUrl(),"caCertificatePem",endpoints.issuerCertificate())).header("Cache-Control","no-store").build();
    }
    @GET @Path("api/public/v1/clients/setup/{target}")
    public Response configuredSetup(@PathParam("target")String target){return artifacts.configuredWindowsInstaller(target,setupSource());}
    /** A local Quickstart console also publishes its gateway CA, so its setup names that console. */
    private String setupSource(){
        String canonical=serverUrl();
        if(!config.getOptionalValue("toolgate.quickstart.enabled",Boolean.class).orElse(false)
            ||!java.util.Set.of("localhost","127.0.0.1","0:0:0:0:0:0:0:1").contains(TenantHosts.normalize(java.net.URI.create(canonical).getHost())))return canonical;
        return config.getOptionalValue("toolgate.control.endpoint.console-url",String.class).map(value->value.endsWith("/")?value.substring(0,value.length()-1):value).orElseGet(this::serverUrl);
    }
    @GET @Path("api/public/v1/clients/{filename}")
    public Response download(@PathParam("filename")String filename){return artifacts.download(filename);}
}
