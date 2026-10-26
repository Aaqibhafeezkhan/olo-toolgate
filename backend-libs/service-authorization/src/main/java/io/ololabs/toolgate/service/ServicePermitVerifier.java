// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.ololabs.toolgate.contracts.*;
import javax.net.ssl.SSLSession;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.*;

/** Fail-closed verification shared by downstream services. Call again before releasing a read result. */
public final class ServicePermitVerifier {
    public record Epoch(long value, long observedAtUnixMs) {}
    @FunctionalInterface public interface EpochFeed { Epoch current(String tenant); }
    @FunctionalInterface public interface WriteConsumer { boolean consume(ServiceAudience audience, ServicePermitConsumption request); }
    public record Request(String tenant, String operation, List<ResourceDescriptor> resources, byte[] exactBody) {
        public Request { resources=List.copyOf(resources);exactBody=exactBody.clone(); }
        @Override public byte[] exactBody(){return exactBody.clone();}
    }
    public final class Authorization {
        private final ServicePermitClaims claims;
        private final long expiresAfterNanos;
        private final long startedAtNanos=System.nanoTime();
        private Authorization(ServicePermitClaims claims){this.claims=claims;this.expiresAfterNanos=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(15000,claims.exp()-clock.millis()));}
        public ServicePermitClaims claims(){return claims;}
        /** Invoke inside the storage transaction immediately before commit; a delayed transaction must roll back. */
        public void checkCommitDeadline(){if(clock.millis()>=claims.exp()||System.nanoTime()-startedAtNanos>=expiresAfterNanos)throw denied();}
    }
    private final String issuer;
    private final ServiceAudience audience;
    private final Map<String,PublicKey> keys;
    private final Clock clock;
    private final EpochFeed epochs;
    private final WriteConsumer consumer;
    private static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public ServicePermitVerifier(String issuer, ServiceAudience audience, Map<String,PublicKey> trustedKeys,
            Clock clock, EpochFeed epochs, WriteConsumer consumer){
        this.issuer=Objects.requireNonNull(issuer);this.audience=Objects.requireNonNull(audience);this.keys=Map.copyOf(trustedKeys);
        if(keys.isEmpty()||keys.size()>16||keys.values().stream().anyMatch(key->!(key instanceof java.security.interfaces.RSAPublicKey rsa)
                ||rsa.getModulus().bitLength()<2048||!rsa.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))))
            throw new IllegalArgumentException("Pinned RSA service permit keys required");
        this.clock=Objects.requireNonNull(clock);this.epochs=Objects.requireNonNull(epochs);this.consumer=Objects.requireNonNull(consumer);
    }
    /** The TLS session must come from the accepted mTLS socket, never from an HTTP header or submitted certificate. */
    public Authorization authorize(ServicePermit permit, Request request, SSLSession authenticatedExecutor) {
        try {
            if(authenticatedExecutor==null||!authenticatedExecutor.isValid())throw denied();
            var peer=(X509Certificate)authenticatedExecutor.getPeerCertificates()[0];peer.checkValidity(new Date(clock.millis()));
            String keyHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(peer.getPublicKey().getEncoded()));
            return authorizeKey(permit,request,keyHash);
        } catch(GeneralSecurityException|javax.net.ssl.SSLPeerUnverifiedException invalid){throw denied();}
    }
    // Package-private test seam; production requests enter through the authenticated TLS session above.
    Authorization authorizeKey(ServicePermit permit, Request request, String executorKey) {
        try {
            if(permit==null||permit.jws().length()>32768)throw denied();
            String[] parts=permit.jws().split("\\.",-1);if(parts.length!=3)throw denied();
            var header=JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if(header.size()!=3||!header.path("alg").asText().equals("RS256")||!header.path("typ").asText().equals("olo-service-permit+jws"))throw denied();
            PublicKey key=keys.get(header.path("kid").asText());if(key==null)throw denied();
            var signature=Signature.getInstance("SHA256withRSA");signature.initVerify(key);
            signature.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
            if(!signature.verify(Base64.getUrlDecoder().decode(parts[2])))throw denied();
            var claims=JSON.readValue(Base64.getUrlDecoder().decode(parts[1]),ServicePermitClaims.class);
            // D1/D3: platform data is authorized for the user, never an agent actor. Background work only indexes.
            if(claims.act()!=null || claims.mode()==io.ololabs.toolgate.contracts.v2.RequestMode.DELEGATED
                || (claims.mode()==io.ololabs.toolgate.contracts.v2.RequestMode.SERVICE && !request.operation().equals("rag.update")))throw denied();
            long now=clock.millis();
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.exactBody()));
            if(!issuer.equals(claims.iss())||audience!=claims.aud()||!request.tenant().equals(claims.tenantId())
                ||!request.operation().equals(claims.operation())||!request.resources().equals(claims.resources())
                ||claims.sub()==null||claims.sub().isBlank()||claims.mode()==null
                ||!digest.equals(claims.requestFingerprint())||!executorKey.equals(claims.executorKeySha256())
                ||claims.iat()>now||claims.exp()<=now||claims.exp()<=claims.iat()||claims.exp()-claims.iat()>15000
                ||request.resources().isEmpty()||request.resources().stream().anyMatch(r->r.kind()!=ResourceKind.CUSTOM||!r.locator().startsWith("olo://"+request.tenant()+"/")))throw denied();
            boolean read=switch(request.operation()){
                case "memory.read","rag.query" -> true;
                case "memory.append","memory.delete","memory.share","memory.export","rag.ingest","rag.update","rag.delete","rag.share" -> false;
                default -> throw denied();
            };
            if(read){var epoch=epochs.current(request.tenant());
                if(epoch==null||epoch.observedAtUnixMs()>now||now-epoch.observedAtUnixMs()>10000||epoch.value()!=claims.authorizationEpoch())throw new StaleReadEpoch();
            } else if(!consumer.consume(audience,new ServicePermitConsumption(permit,request.operation(),request.resources(),digest,executorKey)))throw denied();
            return new Authorization(claims);
        } catch(java.io.IOException|GeneralSecurityException|IllegalArgumentException invalid){throw denied();}
    }
    /** Only reached after signature, peer, identity, exact request and lifetime checks pass. */
    static final class StaleReadEpoch extends SecurityException {
        private static final long serialVersionUID = 1L;
        StaleReadEpoch(){super("Read authorization epoch is stale");}
    }
    private static SecurityException denied(){return new SecurityException("Downstream authorization denied");}
}
