// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.Config;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;

/** Disabled by default. Deployment requires explicit external signer, release keyring and artifact mirror. */
@ApplicationScoped
public class FleetBootstrap {
    @Inject TenancyConfiguration tenancy;
    @Inject Config config; @Inject ContractCodec codec; @Inject PostgresStore store; @Inject EndpointService endpoints;
    private FleetService service; private ArtifactStore artifacts;
    private String setting(String name){return config.getValue("toolgate.control.fleet."+name,String.class);}
    private byte[] read(Path path,int max)throws java.io.IOException{return ProtectedCustody.read(path,max);}
    private String pem(Path path)throws java.io.IOException{return new String(read(path,16384),java.nio.charset.StandardCharsets.US_ASCII);}
    private static byte[] der(String pem,String label){return Base64.getDecoder().decode(pem.replace("-----BEGIN "+label+"-----","").replace("-----END "+label+"-----","").replaceAll("\\s",""));}
    private List<FleetTrustKey> keys(String name)throws java.io.IOException{
        var json=new String(read(Path.of(setting(name)),8192),java.nio.charset.StandardCharsets.UTF_8);
        var value=(com.fasterxml.jackson.databind.JsonNode)codec.value(json);
        if(!value.isArray()||value.isEmpty()||value.size()>4)throw new IllegalArgumentException();
        var keys=new ArrayList<FleetTrustKey>();for(var entry:value)keys.add(codec.model(codec.json(entry),FleetTrustKey.class));return keys;
    }
    private void load(){
        boolean enabled=config.getValue("toolgate.control.fleet.enabled",Boolean.class);
        FleetCrypto crypto=null;String tenant="00000000-0000-0000-0000-000000000000",server="disabled";artifacts=(digest,size)->{throw Failure.unavailable();};
        if(enabled)try{
            if(!config.getValue("toolgate.control.endpoint.enabled",Boolean.class))throw new IllegalArgumentException();
            var factory=KeyFactory.getInstance("RSA");
            var signer=(RSAPrivateCrtKey)factory.generatePrivate(new PKCS8EncodedKeySpec(der(ProtectedCustody.privateText(Path.of(setting("private-key-path")),16384),"PRIVATE "+"KEY")));
            var forbidden=new ArrayList<java.math.BigInteger>();
            var jwt=config.getValue("mp.jwt.verify.publickey.location",String.class);
            var idp=(RSAPublicKey)factory.generatePublic(new X509EncodedKeySpec(der(pem(jwt.startsWith("file:")?Path.of(java.net.URI.create(jwt)):Path.of(jwt)),"PUBLIC KEY")));forbidden.add(idp.getModulus());
            var ca=(java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(read(Path.of(config.getValue("toolgate.control.endpoint.ca-certificate-path",String.class)),16384)));
            forbidden.add(((RSAPublicKey)ca.getPublicKey()).getModulus());
            if(config.getValue("toolgate.control.bundle.enabled",Boolean.class)){
                var policy=(RSAPrivateCrtKey)factory.generatePrivate(new PKCS8EncodedKeySpec(der(pem(Path.of(config.getValue("toolgate.control.bundle.private-key-path",String.class))),"PRIVATE "+"KEY")));forbidden.add(policy.getModulus());
            }
            crypto=new RsaFleetCrypto(codec,signer,setting("key-id"),keys("release-keys-path"),keys("organization-keys-path"),forbidden);
            javax.net.ssl.SSLContext tls=null;
            var caPath=config.getOptionalValue("toolgate.control.fleet.artifact-ca-path",String.class);
            if(caPath.isPresent()){
                var certificates=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(read(Path.of(caPath.get()),16384)));
                if(certificates.isEmpty()||certificates.size()>4)throw new IllegalArgumentException();
                var trusted=KeyStore.getInstance(KeyStore.getDefaultType());trusted.load(null,null);int i=0;for(var certificate:certificates)trusted.setCertificateEntry("mirror-"+(i++),certificate);
                var trust=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());trust.init(trusted);tls=javax.net.ssl.SSLContext.getInstance("TLS");tls.init(null,trust.getTrustManagers(),null);
            }
            String token=null;var tokenPath=config.getOptionalValue("toolgate.control.fleet.artifact-token-path",String.class);if(tokenPath.isPresent())token=new String(ProtectedCustody.privateKey(Path.of(tokenPath.get()),4096),java.nio.charset.StandardCharsets.UTF_8).strip();
            artifacts=config.getOptionalValue("toolgate.quickstart.enabled",Boolean.class).orElse(false)
                && config.getOptionalValue("toolgate.control.fleet.artifact-origin",String.class).orElse("").equals("https://localhost:8443/artifacts")
                ? new LocalArtifactStore(Path.of(config.getValue("toolgate.quickstart.artifact-directory",String.class)))
                : new HttpsArtifactStore(setting("artifact-origin"),token,tls);
            tenant=tenancy.defaultId().value();server=config.getValue("toolgate.control.endpoint.server-id",String.class);
        }catch(Exception failure){throw new IllegalStateException("Fleet deployment requires dedicated external trust keys, endpoint identity and fixed HTTPS artifact store");}
        service=new FleetService(store,codec,crypto,endpoints,java.time.Clock.systemUTC(),enabled,tenant,server);
    }
    void start(@Observes StartupEvent event){if(service==null)load();}
    @Produces @ApplicationScoped FleetService fleet(){if(service==null)load();return service;}
    @Produces @ApplicationScoped ArtifactStore artifacts(){if(service==null)load();return artifacts;}
}
