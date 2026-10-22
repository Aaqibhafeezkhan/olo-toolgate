// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import java.nio.file.*;
import java.util.*;

/** Protected operator publication, after registration. It does not activate the live v2 protocol. */
@ApplicationScoped
public class IdentityKeyBootstrap {
    @Inject Config config;@Inject ContractCodec codec;@Inject TenantRegistry registry;
    private TenantIdentityCustody custody;
    public synchronized TenantIdentityCustody optional(){
        if(custody!=null)return custody;
        var root=config.getOptionalValue("toolgate.control.identity.custody-root",String.class);if(root.isEmpty())return null;
        try{custody=new TenantIdentityCustody(codec,Path.of(root.get()),forbidden());return custody;}
        catch(Exception invalid){throw new IllegalStateException("Dedicated tenant identity custody required");}
    }
    private List<java.math.BigInteger> forbidden()throws Exception{
        var result=new ArrayList<java.math.BigInteger>();
        String location=config.getValue("mp.jwt.verify.publickey.location",String.class);
        collect(ProtectedCustody.text(location.startsWith("file:")?Path.of(java.net.URI.create(location)):Path.of(location),16384),result);
        for(String setting:List.of("toolgate.control.bundle.private-key-path","toolgate.control.fleet.private-key-path","toolgate.control.package-release.private-key-path")){
            var path=config.getOptionalValue(setting,String.class);if(path.isPresent())collect(ProtectedCustody.privateText(Path.of(path.get()),16384),result);
        }
        for(var path:config.getOptionalValue("quarkus.http.ssl.certificate.files",String.class).orElse("").split(","))if(!path.isBlank())collect(ProtectedCustody.text(Path.of(path.trim()),65536),result);
        var shared=config.getOptionalValue("toolgate.control.endpoint.ca-certificate-path",String.class);if(shared.isPresent())collect(ProtectedCustody.text(Path.of(shared.get()),16384),result);
        return List.copyOf(result);
    }
    private static void collect(String pem,List<java.math.BigInteger> result)throws Exception{
        try(var parser=new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(pem))){
            Object parsed;int count=0;while((parsed=parser.readObject())!=null){
                if(++count>8)throw new IllegalArgumentException();
                var converter=new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter();java.security.Key key;
                if(parsed instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo info)key=converter.getPrivateKey(info);
                else if(parsed instanceof org.bouncycastle.asn1.x509.SubjectPublicKeyInfo info)key=converter.getPublicKey(info);
                else if(parsed instanceof org.bouncycastle.cert.X509CertificateHolder certificate)key=converter.getPublicKey(certificate.getSubjectPublicKeyInfo());
                else throw new IllegalArgumentException();
                if(key instanceof java.security.interfaces.RSAKey rsa)result.add(rsa.getModulus());
            }if(count==0)throw new IllegalArgumentException();
        }
    }
    void start(@Observes @jakarta.annotation.Priority(9) StartupEvent ignored){
        var publication=config.getOptionalValue("toolgate.control.identity.publication-path",String.class);if(publication.isEmpty())return;
        try{
            var provider=optional();if(provider==null)throw Failure.unavailable();
            var packet=codec.configuration(ProtectedCustody.text(Path.of(publication.get()),2097152),false);
            if(!packet.isObject()||packet.size()!=2||!packet.path("formatVersion").isIntegralNumber()||!packet.path("formatVersion").canConvertToLong()||packet.path("formatVersion").asLong()!=1
                ||!packet.path("publications").isArray()||packet.path("publications").isEmpty()||packet.path("publications").size()>256)throw Failure.validation();
            var seen=new HashSet<TenantId>();
            // Validate the complete packet's shape before publishing any tenant.
            var prepared=new ArrayList<Publication>();
            for(var item:packet.path("publications")){
                if(!item.isObject()||item.size()!=3||!item.path("generation").isIntegralNumber()||!item.path("generation").canConvertToLong()||item.path("generation").asLong()<1||item.path("generation").asLong()>9007199254740991L
                    ||!item.path("keys").isArray()||item.path("keys").size()<2||item.path("keys").size()>6)throw Failure.validation();
                var tenant=new TenantId(item.path("tenantId").asText());if(!seen.add(tenant))throw Failure.validation();
                var keys=new ArrayList<TenantIdentityCustody.Reference>();
                for(var key:item.path("keys")){
                    if(!key.isObject()||!key.path("purpose").isTextual()||!key.path("keyId").isTextual()||!key.path("state").isTextual()
                        ||key.size()!=(key.has("certificatePem")?4:3)||key.has("certificatePem")&&!key.path("certificatePem").isTextual())throw Failure.validation();
                    keys.add(new TenantIdentityCustody.Reference(key.path("purpose").asText(),key.path("keyId").asText(),key.path("state").asText(),key.has("certificatePem")?key.path("certificatePem").asText():null));
                }
                prepared.add(new Publication(tenant,item.path("generation").asLong(),List.copyOf(keys),"identity-"+DirectoryService.digest(codec.json(item)).substring(0,32)));
            }
            for(var item:prepared)registry.installIdentity(item.tenant(),provider,item.generation(),item.keys(),item.requestId());
        }catch(Exception invalid){throw new IllegalStateException("Protected tenant identity publication rejected ("+invalid.getClass().getSimpleName()+")");}
    }
    private record Publication(TenantId tenant,long generation,List<TenantIdentityCustody.Reference> keys,String requestId){}
}
