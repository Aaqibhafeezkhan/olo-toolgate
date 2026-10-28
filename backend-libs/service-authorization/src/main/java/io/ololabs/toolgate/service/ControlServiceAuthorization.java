// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import com.fasterxml.jackson.databind.*;
import io.ololabs.toolgate.contracts.*;
import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Authenticated Control adapter. Cache lifetime is at most five seconds; stale/failed refreshes refuse access. */
public final class ControlServiceAuthorization {
    private final HttpClient http;
    private final URI base;
    private final String issuer,tenant;
    private final ServiceAudience audience;
    private final Supplier<String> credential;
    private final Clock clock;
    private long refreshedAt=-1;
    private Map<String,PublicKey> keys=Map.of();
    private ServicePermitVerifier.Epoch epoch;
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    public ControlServiceAuthorization(HttpClient http, URI origin, String issuer, String tenant,
            ServiceAudience audience, Supplier<String> credential, Clock clock) {
        if(!"https".equals(origin.getScheme())||origin.getHost()==null||origin.getUserInfo()!=null||origin.getQuery()!=null
            ||origin.getFragment()!=null||!(origin.getPath().isEmpty()||origin.getPath().equals("/")))throw new IllegalArgumentException("HTTPS Control origin required");
        if(http.followRedirects()!=HttpClient.Redirect.NEVER)throw new IllegalArgumentException("Control redirects must be disabled");
        this.http=Objects.requireNonNull(http);this.issuer=Objects.requireNonNull(issuer);this.tenant=Objects.requireNonNull(tenant);
        this.audience=Objects.requireNonNull(audience);this.credential=Objects.requireNonNull(credential);this.clock=Objects.requireNonNull(clock);
        base=origin.resolve("/api/control/v1/access/service-permits/"+audience.name().replace('_','-')+"/");
    }
    /** Call inside the storage transaction for writes and again before releasing read results. */
    public synchronized ServicePermitVerifier.Authorization authorize(ServicePermit permit,
            ServicePermitVerifier.Request request, SSLSession executor) {
        if(!tenant.equals(request.tenant()))throw denied();
        refresh();
        return withFreshEpoch(()->new ServicePermitVerifier(issuer,audience,keys,clock,t->tenant.equals(t)?epoch:null,
            (a,c)->consume(c)).authorize(permit,request,executor),()->{refreshedAt=-1;refresh();});
    }
    /** Refresh once only for a fully verified read whose signed epoch differs from our cache. */
    static <T> T withFreshEpoch(Supplier<T> authorize,Runnable refresh) {
        try{return authorize.get();}
        catch(ServicePermitVerifier.StaleReadEpoch stale){refresh.run();return authorize.get();}
    }
    private boolean consume(ServicePermitConsumption request) {
        return exchange("consume",request).path("authorized").asBoolean(false);
    }
    private void refresh() {
        long now=clock.millis();
        if(refreshedAt>=0&&now>=refreshedAt&&now-refreshedAt<5000)return;
        // Fetch both under authenticated TLS; do not retain an old epoch when either dependency fails.
        refreshedAt=-1;epoch=null;keys=Map.of();
        try {
            var snapshot=JSON.treeToValue(exchange("epoch",null),ServiceAuthorizationEpoch.class);
            if(!tenant.equals(snapshot.tenantId())||snapshot.observedAtUnixMs()>now+2000||now-snapshot.observedAtUnixMs()>10000)throw denied();
            var material=exchange("jwks",null).path("keys");
            if(!material.isArray()||material.isEmpty()||material.size()>16)throw denied();
            var trusted=new HashMap<String,PublicKey>();
            for(var key:material){
                if(!key.path("kty").asText().equals("RSA")||!key.path("alg").asText().equals("RS256")||!key.path("use").asText().equals("sig"))throw denied();
                var rsa=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new java.math.BigInteger(1,Base64.getUrlDecoder().decode(key.path("n").asText())),
                    new java.math.BigInteger(1,Base64.getUrlDecoder().decode(key.path("e").asText()))));
                if(rsa.getModulus().bitLength()<2048||!rsa.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))
                    ||trusted.put(key.path("kid").asText(),rsa)!=null)throw denied();
            }
            keys=Map.copyOf(trusted);epoch=new ServicePermitVerifier.Epoch(snapshot.authorizationEpoch(),now);refreshedAt=now;
        } catch(java.io.IOException|GeneralSecurityException|IllegalArgumentException invalid){throw denied();}
    }
    private JsonNode exchange(String path,Object body) {
        try {
            String token=credential.get();if(token==null||token.isBlank())throw denied();
            var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3))
                .header("Authorization","Bearer "+token).header("Toolgate-Tenant-ID",tenant).header("Accept","application/json");
            if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200||response.body().length>65536)throw denied();
            return JSON.readTree(response.body());
        } catch(java.io.IOException invalid){throw denied();}
        catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw denied();}
    }
    private static SecurityException denied(){return new SecurityException("Control service authorization unavailable");}
}
