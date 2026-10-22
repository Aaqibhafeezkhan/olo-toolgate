// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

/** Opt-in direct TLS enrollment; missing external CA/TLS settings stop startup. */
@ApplicationScoped
public class EndpointBootstrap {
    @Inject TenantRequest request;
    @Inject Config config;
    @Inject ContractCodec codec;
    @Inject PostgresStore store;
    @Inject EnterpriseOperations operations;
    @Inject IdentityKeyBootstrap identityKeys;
    private EndpointService service;
    private java.util.List<java.security.cert.X509Certificate> listenerRoots=java.util.List.of();
    void start(@Observes StartupEvent ignored){if(service==null)service=load();}
    private EndpointService load(){
        boolean enabled=config.getValue("toolgate.control.endpoint.enabled",Boolean.class);DeviceIssuer issuer=null;
        String tenant="00000000-0000-0000-0000-000000000000",server="disabled",organization="disabled",control="https://disabled.invalid",gateway="https://disabled.invalid";
        if(enabled)try{
            if((!config.getValue("quarkus.http.insecure-requests",String.class).equalsIgnoreCase("disabled") && !config.getOptionalValue("toolgate.quickstart.enabled",Boolean.class).orElse(false))
                || !config.getValue("quarkus.http.ssl.client-auth",String.class).equalsIgnoreCase("request")
                || config.getOptionalValue("quarkus.http.ssl.certificate.trust-store-file",String.class).isEmpty())throw new IllegalArgumentException();
            if(identityKeys.optional()==null)throw new IllegalArgumentException();
            var trust=java.security.KeyStore.getInstance("PKCS12");
            var bytes=ProtectedCustody.read(java.nio.file.Path.of(config.getValue("quarkus.http.ssl.certificate.trust-store-file",String.class)),2097152);
            try(var input=new java.io.ByteArrayInputStream(bytes)){trust.load(input,config.getValue("quarkus.http.ssl.certificate.trust-store-password",String.class).toCharArray());}
            var roots=new java.util.ArrayList<java.security.cert.X509Certificate>();var aliases=trust.aliases();
            while(aliases.hasMoreElements()){var alias=aliases.nextElement();if(roots.size()>=4096||!trust.isCertificateEntry(alias))throw new IllegalArgumentException();roots.add((java.security.cert.X509Certificate)trust.getCertificate(alias));}
            if(roots.isEmpty())throw new IllegalArgumentException();listenerRoots=java.util.List.copyOf(roots);
        }catch(Exception failure){throw new IllegalStateException("Endpoint enrollment requires protected tenant identity custody and direct HTTPS with requested client certificates");}
        ClientCheckInPolicy checkIn;
        try{checkIn=ClientCheckInPolicy.configure(config.getOptionalValue("toolgate.control.client-check-in.check-in-ms",Long.class).orElse(null),
            config.getOptionalValue("toolgate.control.client-check-in.jitter-pct",Integer.class).orElse(null),
            config.getOptionalValue("toolgate.control.client-check-in.offline-after-ms",Long.class).orElse(null));
        }catch(IllegalArgumentException failure){throw new IllegalStateException(failure.getMessage());}
        return new EndpointService(store,codec,issuer,java.time.Clock.systemUTC(),enabled,tenant,server,organization,control,gateway).operations(operations).checkInPolicy(checkIn);
    }
    private synchronized EndpointService template(){if(service==null)service=load();return service;}
    public Store.RuntimeProfile profile(Ids.TenantId tenant){return store.transaction(tenant,false,Store.Session::runtimeProfile);}
    public EndpointService forTenant(Ids.TenantId tenant){
        var template=template();var profile=profile(tenant);
        if(!config.getValue("toolgate.control.endpoint.enabled",Boolean.class)||profile==null)return template.forTenant(tenant,profile);
        var provider=identityKeys.optional();if(provider==null)throw Failure.unavailable();
        var records=store.transaction(tenant,false,Store.Session::identityKeys);
        var bound=provider.bind(tenant,profile,records,System.currentTimeMillis());
        return template.forTenant(tenant,profile,new TenantPeerTrust(bound.issuer(),tenant,listenerRoots))
            .identityGeneration(bound.generation())
            .discoverySigner((minimum,now)->bound.discovery(codec,tenant,profile,minimum,now));
    }
    public ClientCheckInPolicy checkInPolicy(){return template().checkInPolicy();}
    @Produces @jakarta.enterprise.context.RequestScoped EndpointService endpoint(){return forTenant(request.tenant());}
}
