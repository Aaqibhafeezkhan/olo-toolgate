// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.Base64;

/** Missing or reused effect keys fail closed. Identity, policy and effect keys have distinct purposes. */
@ApplicationScoped
public class EffectBootstrap {
    @Inject Config config;@Inject ContractCodec codec;
    private EffectSigner validated;
    void start(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event){validated=signer();}
    public boolean ready(){return validated instanceof RsaEffectSigner;}
    private EffectSigner signer(){
        if(!config.getOptionalValue("toolgate.control.effect.enabled",Boolean.class).orElse(false))return new EffectSigner(){public io.ololabs.toolgate.contracts.EnterpriseSignedPermit sign(io.ololabs.toolgate.contracts.EnterprisePermitClaims claims){throw Failure.unavailable();}public io.ololabs.toolgate.contracts.EnterprisePermitClaims verify(io.ololabs.toolgate.contracts.EnterpriseSignedPermit permit){throw Failure.unavailable();}};
        try{var path=Path.of(config.getValue("toolgate.control.effect.private-key-path",String.class));byte[] raw=ProtectedCustody.privateKey(path,16384);
            String pem=new String(raw,java.nio.charset.StandardCharsets.US_ASCII);if(!pem.startsWith("-----BEGIN "+"PRIVATE KEY-----"))throw new IllegalArgumentException();
            var factory=KeyFactory.getInstance("RSA");var key=(RSAPrivateCrtKey)factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem.replace("-----BEGIN "+"PRIVATE KEY-----","").replace("-----END "+"PRIVATE KEY-----","").replaceAll("\\s",""))));
            String jwt=config.getValue("mp.jwt.verify.publickey.location",String.class);String identity=ProtectedCustody.text(jwt.startsWith("file:")?Path.of(java.net.URI.create(jwt)):Path.of(jwt),16384);var publicKey=(RSAPublicKey)factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(identity.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s",""))));if(key.getModulus().equals(publicKey.getModulus()))throw new IllegalArgumentException();
            if(config.getValue("toolgate.control.bundle.enabled",Boolean.class)){String bundle=ProtectedCustody.privateText(Path.of(config.getValue("toolgate.control.bundle.private-key-path",String.class)),16384);var other=(RSAPrivateCrtKey)factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(bundle.replace("-----BEGIN "+"PRIVATE KEY-----","").replace("-----END "+"PRIVATE KEY-----","").replaceAll("\\s",""))));if(key.getModulus().equals(other.getModulus()))throw new IllegalArgumentException();}
            if(config.getValue("toolgate.control.endpoint.enabled",Boolean.class)){try(var input=new java.io.ByteArrayInputStream(ProtectedCustody.read(Path.of(config.getValue("toolgate.control.endpoint.ca-certificate-path",String.class)),16384))){var certificate=(java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input);if(certificate.getPublicKey() instanceof RSAPublicKey ca&&key.getModulus().equals(ca.getModulus()))throw new IllegalArgumentException();}}
            if(config.getValue("toolgate.control.fleet.enabled",Boolean.class)){String fleet=ProtectedCustody.privateText(Path.of(config.getValue("toolgate.control.fleet.private-key-path",String.class)),16384);var other=(RSAPrivateCrtKey)factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(fleet.replace("-----BEGIN "+"PRIVATE KEY-----","").replace("-----END "+"PRIVATE KEY-----","").replaceAll("\\s",""))));if(key.getModulus().equals(other.getModulus()))throw new IllegalArgumentException();}
            if(config.getValue("toolgate.control.fleet.enabled",Boolean.class))for(String setting:java.util.List.of("toolgate.control.fleet.release-keys-path","toolgate.control.fleet.organization-keys-path")){var keys=(com.fasterxml.jackson.databind.JsonNode)codec.value(ProtectedCustody.text(Path.of(config.getValue(setting,String.class)),16384));for(var value:keys){var pinned=codec.model(codec.json(value),io.ololabs.toolgate.contracts.FleetTrustKey.class);if(key.getModulus().equals(new java.math.BigInteger(1,Base64.getUrlDecoder().decode(pinned.n()))))throw new IllegalArgumentException();}}
            var recovery=config.getOptionalValue("toolgate.control.recovery.trust-path",String.class).filter(value->!value.isBlank());
            if(recovery.isPresent()) {
                var trust=(com.fasterxml.jackson.databind.JsonNode)codec.value(ProtectedCustody.text(Path.of(recovery.get()),65536));
                for(var pinned:trust.path("keys"))if(key.getModulus().equals(new java.math.BigInteger(1,Base64.getUrlDecoder().decode(pinned.path("n").asText()))))throw new IllegalArgumentException();
            }
            return new RsaEffectSigner(key,config.getValue("toolgate.control.effect.key-id",String.class),codec);
        }catch(java.io.IOException|GeneralSecurityException|IllegalArgumentException|java.util.NoSuchElementException failure){throw new IllegalStateException("Dedicated external RSA effect key required",failure);}
    }
    @Produces @ApplicationScoped EnterpriseOperations operations(PostgresStore store){if(validated==null)validated=signer();return new EnterpriseOperations(store,codec,validated,java.time.Clock.systemUTC(),config.getOptionalValue("toolgate.control.effect.issuer",String.class).orElse("control"),config.getOptionalValue("toolgate.control.effect.audience",String.class).orElse("toolgate-client"),config.getValue("toolgate.control.approval.pending-ttl-ms",Long.class),config.getValue("toolgate.control.approval.max-active",Integer.class));}
    @Produces @ApplicationScoped ServerPackages packages(PostgresStore store,EnterpriseOperations operations){return new ServerPackages(store,codec,operations,java.time.Clock.systemUTC());}
}
