// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import com.fasterxml.jackson.databind.*;
import io.ololabs.toolgate.contracts.v2.*;
import javax.net.ssl.SSLSession;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.*;

/** Exact accepted HTTP facts and peer verification for explicitly declared, current fenced targets. */
public final class FencedTargetVerifier {
    private static final ObjectMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final String issuer,tenant;
    private final FencedTarget target;
    private final Map<String,PublicKey> keys;
    private final Clock clock;
    public FencedTargetVerifier(String issuer,String tenant,FencedTarget target,Map<String,PublicKey> keys,Clock clock){
        this.issuer=Objects.requireNonNull(issuer);this.tenant=Objects.requireNonNull(tenant);
        this.target=Objects.requireNonNull(target);this.keys=Map.copyOf(keys);this.clock=Objects.requireNonNull(clock);
        if(!target.targetId().matches("[a-z][a-z0-9-]{0,63}")||keys.isEmpty()||keys.size()>16
            ||keys.values().stream().anyMatch(k->!(k instanceof java.security.interfaces.RSAPublicKey rsa)
                ||rsa.getModulus().bitLength()<2048||!rsa.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))))
            throw new IllegalArgumentException("Pinned current target configuration required");
    }
    /** Verified facts, not permission to act. Commit a local claim and consume at Control first. */
    public final class VerifiedWrite {
        private final SignedEffectGrant grant;
        private final EffectGrantClaims claims;
        private final JdbcEffectClaims.Binding binding;
        private final long started=System.nanoTime(),remainingNanos;
        private VerifiedWrite(SignedEffectGrant grant,EffectGrantClaims claims){
            this.grant=grant;this.claims=claims;
            binding=new JdbcEffectClaims.Binding(claims.tenantId(),claims.effectId(),claims.generation(),claims.authNonce(),claims.httpRequestDigest());
            remainingNanos=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(2000,claims.exp()-clock.millis()));
        }
        public JdbcEffectClaims.Binding binding(){return binding;}
        public long expiresAt(){return claims.exp();}
        public FencedTarget target(){return claims.fencedTarget();}
        void checkDeadline(){if(clock.millis()>=claims.exp()||System.nanoTime()-started>=remainingNanos)throw denied();}
        TargetClaimConsumption consumption(String token){checkDeadline();return new TargetClaimConsumption(grant,token,claims.httpRequestDigest(),claims.executorKeySha256());}
    }
    /** URI/profile come from trusted listener routing; bytes and headers come from the accepted request. */
    public VerifiedWrite verifyWrite(SignedEffectGrant grant,ServicePermitVerifier.Transport request,byte[] body,SSLSession peer){
        try{
            if(peer==null||!peer.isValid())throw denied();
            var certificates=peer.getPeerCertificates();
            if(certificates.length==0||!(certificates[0] instanceof X509Certificate certificate))throw denied();
            certificate.checkValidity(new Date(clock.millis()));
            String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded()));
            return verifyKey(grant,request,body,key);
        }catch(GeneralSecurityException|javax.net.ssl.SSLPeerUnverifiedException invalid){throw denied();}
    }
    // Only a package-private test seam. Production requires the actual accepted mTLS session.
    VerifiedWrite verifyKey(SignedEffectGrant grant,ServicePermitVerifier.Transport request,byte[] body,String peerKey){
        try{
            if(grant==null||grant.jws().length()>32768)throw denied();
            String[] parts=grant.jws().split("\\.",-1);if(parts.length!=3)throw denied();
            var header=JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if(header.size()!=3||!"RS256".equals(header.path("alg").asText())
                ||!"toolgate-effect-grant-v1".equals(header.path("typ").asText()))throw denied();
            var key=keys.get(header.path("kid").asText());if(key==null)throw denied();
            var signature=Signature.getInstance("SHA256withRSA");signature.initVerify(key);
            signature.update((parts[0]+"."+parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if(!signature.verify(Base64.getUrlDecoder().decode(parts[2])))throw denied();
            var c=JSON.readValue(Base64.getUrlDecoder().decode(parts[1]),EffectGrantClaims.class);
            long now=clock.millis();
            if(!issuer.equals(c.iss())||!tenant.equals(c.tenantId())||!target.equals(c.fencedTarget())
                ||c.classification()==EffectClassification.READ||c.iat()>now||c.nbf()>now||c.exp()<=now
                ||c.exp()<=c.iat()||c.exp()-c.iat()>2000||!peerKey.equals(c.executorKeySha256())
                ||c.generation()<1||c.authorizationEpoch()<0||c.aud().isBlank()
                ||!"https".equals(request.uri().getScheme())
                ||!request.profile().route().equals(c.routeId())||!request.profile().digest().equals(c.profileDigest()))
                throw denied();
            // Permit transport is broker-owned, excluded only after validating its unique exact token.
            var headers=new HashMap<String,List<String>>();boolean found=false;
            for(var e:request.headers().entrySet()){
                if(e.getKey().equalsIgnoreCase("toolgate-effect-permit")){
                    if(found||!e.getValue().equals(List.of(grant.jws())))throw denied();found=true;
                }else headers.put(e.getKey(),e.getValue());
            }
            if(!found||!HttpRequestFingerprint.digest(request.profile(),request.uri(),request.method(),headers,body).equals(c.httpRequestDigest()))throw denied();
            return new VerifiedWrite(grant,c);
        }catch(java.io.IOException|GeneralSecurityException|IllegalArgumentException invalid){throw denied();}
    }
    private static SecurityException denied(){return new SecurityException("Fenced target authorization denied");}
}
