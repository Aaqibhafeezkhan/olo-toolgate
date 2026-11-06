// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import io.ololabs.toolgate.contracts.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Dependency failures, response bounds and status-only replies cannot become commit authority. */
class ControlTargetAuthorizationTest {
    FencedTargetVerifierTest fixture;
    @BeforeAll static void key()throws Exception{FencedTargetVerifierTest.keys();}
    @BeforeEach void setup(){fixture=new FencedTargetVerifierTest();}
    static final class Transport extends HttpClient {
        String body="{\"effectId\":\"effect-1\",\"authorizationGeneration\":1,\"status\":\"CONSUMED\"}";
        int status=200,calls;boolean cancelled,ioFailure,interrupted;
        HttpRequest last;
        public Redirect followRedirects(){return Redirect.NEVER;}
        public Optional<java.net.CookieHandler> cookieHandler(){return Optional.empty();}
        public Optional<Duration> connectTimeout(){return Optional.of(Duration.ofSeconds(1));}
        public Optional<ProxySelector> proxy(){return Optional.empty();}
        public SSLContext sslContext(){throw new AssertionError("No TLS configuration introspection expected");}
        public SSLParameters sslParameters(){return new SSLParameters();}
        public Optional<Authenticator> authenticator(){return Optional.empty();}
        public Version version(){return Version.HTTP_1_1;}
        public Optional<Executor> executor(){return Optional.empty();}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new AssertionError();}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new AssertionError();}
        public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler)throws java.io.IOException,InterruptedException{
            calls++;last=request;
            if(ioFailure)throw new java.io.IOException("private provider details");
            if(interrupted)throw new InterruptedException("private provider details");
            var headers=HttpHeaders.of(Map.of("content-type",List.of("application/json")),(a,b)->true);
            var info=new HttpResponse.ResponseInfo(){
                public int statusCode(){return status;}public HttpHeaders headers(){return headers;}public Version version(){return Version.HTTP_1_1;}
            };
            var subscriber=handler.apply(info);
            subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled=true;}});
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for(int offset=0;offset<bytes.length&&!cancelled;offset+=8192)
                subscriber.onNext(List.of(ByteBuffer.wrap(bytes,offset,Math.min(8192,bytes.length-offset))));
            if(!cancelled)subscriber.onComplete();
            T data;
            try{data=subscriber.getBody().toCompletableFuture().get(1,TimeUnit.SECONDS);}
            catch(ExecutionException|TimeoutException failure){throw new java.io.IOException("bounded response rejected");}
            return new HttpResponse<>(){
                public int statusCode(){return status;}public HttpRequest request(){return request;}
                public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}public HttpHeaders headers(){return headers;}
                public T body(){return data;}public Optional<SSLSession> sslSession(){return Optional.empty();}
                public URI uri(){return request.uri();}public Version version(){return Version.HTTP_1_1;}
            };
        }
    }
    ControlTargetAuthorization client(Transport http){
        return new ControlTargetAuthorization(http,URI.create("https://control.test"),"control","tenant",fixture.target,()->"fixture-credential",fixture.clock);
    }
    FencedTargetVerifier.VerifiedWrite verified()throws Exception{
        var permit=fixture.sign(fixture.claims());
        return fixture.verifier(fixture.target).verifyKey(permit,fixture.facts(permit),FencedTargetVerifierTest.BODY,FencedTargetVerifierTest.PEER);
    }
    JdbcEffectClaims.Owner owner(FencedTargetVerifier.VerifiedWrite facts){return new JdbcEffectClaims.Owner(facts.binding(),1,"c".repeat(64));}
    @Test void consumedReplyUsesOriginalDeadlineAndOnlyTheDedicatedTargetPath()throws Exception{
        var http=new Transport();var facts=verified();
        var authorization=client(http).consumeClaim(facts,owner(facts));authorization.checkCommitDeadline();
        assertEquals("/api/control/v1/access/target-permits/billing/claims/consume",http.last.uri().getPath());
        assertEquals("Bearer fixture-credential",http.last.headers().firstValue("Authorization").orElseThrow());
        assertEquals("tenant",http.last.headers().firstValue("Toolgate-Tenant-ID").orElseThrow());
        assertEquals(1,http.calls);fixture.now.set(12000);
        assertThrows(SecurityException.class,authorization::checkCommitDeadline);
    }
    @Test void transportRedirectMalformedOversizedAndWrongBindingRepliesCannotAuthorize()throws Exception{
        for(String body:List.of("{}", "not JSON",
                "{\"effectId\":\"other\",\"authorizationGeneration\":1,\"status\":\"CONSUMED\"}",
                "{\"effectId\":\"effect-1\",\"authorizationGeneration\":2,\"status\":\"CONSUMED\"}",
                "{\"effectId\":\"effect-1\",\"authorizationGeneration\":1,\"status\":\"CONSUMED_BY_OTHER_CLAIM\"}")){
            var http=new Transport();http.body=body;var facts=verified();
            assertThrows(SecurityException.class,()->client(http).consumeClaim(facts,owner(facts)));assertEquals(1,http.calls);
        }
        var oversized=new Transport();oversized.body="x".repeat(70000);var facts=verified();
        assertThrows(SecurityException.class,()->client(oversized).consumeClaim(facts,owner(facts)));
        assertTrue(oversized.cancelled);
        var redirect=new Transport();redirect.status=302;
        assertThrows(SecurityException.class,()->client(redirect).consumeClaim(facts,owner(facts)));assertEquals(1,redirect.calls);
        var failed=new Transport();failed.ioFailure=true;
        assertFalse(assertThrows(SecurityException.class,()->client(failed).consumeClaim(facts,owner(facts))).getMessage().contains("private"));
    }
    @Test void evidenceAndStatusAreBoundedObservationsAndForeignTenantsContactNoControl(){
        var binding=new JdbcEffectClaims.Binding("tenant","effect-1",1,"auth-1","sha256:"+"a".repeat(64));
        var http=new Transport();http.body="{\"effectId\":\"effect-1\",\"authorizationGeneration\":1,\"status\":\"COMPLETED\"}";
        var client=client(http);
        assertEquals(ServicePermitClaimState.COMPLETED,client.claimStatus(binding,"c".repeat(64)).status());
        var outcome=new JdbcEffectClaims.PendingOutcome(binding,"c".repeat(64),ServicePermitTargetOutcomeKind.COMPLETED,"sha256:"+"b".repeat(64));
        assertEquals(ServicePermitClaimState.COMPLETED,client.reportOutcome(outcome).status());
        http.body="{\"effectId\":\"effect-1\",\"authorizationGeneration\":1,\"status\":\"CONSUMED\"}";
        assertThrows(SecurityException.class,()->client.reportOutcome(outcome));
        var foreign=new JdbcEffectClaims.Binding("other","effect-1",1,"auth-1",binding.requestDigest());int calls=http.calls;
        assertThrows(SecurityException.class,()->client.claimStatus(foreign,"c".repeat(64)));
        assertThrows(SecurityException.class,()->client.reportOutcome(new JdbcEffectClaims.PendingOutcome(foreign,outcome.claimToken(),outcome.outcome(),outcome.resultDigest())));
        assertEquals(calls,http.calls);
    }
    @Test void missingKeysCredentialsInvalidOriginsAndCancellationNeverFallBackToOldAuthority()throws Exception{
        var http=new Transport();var permit=fixture.sign(fixture.claims());
        for(String body:List.of("{}", "{\"keys\":[]}", "{\"keys\":[{\"kty\":\"EC\"}]}")){
            http.body=body;
            assertThrows(SecurityException.class,()->client(http).verifyWrite(permit,fixture.facts(permit),FencedTargetVerifierTest.BODY,null));
        }
        var missing=new ControlTargetAuthorization(http,URI.create("https://control.test"),"control","tenant",fixture.target,()->null,fixture.clock);
        int calls=http.calls;
        assertThrows(SecurityException.class,()->missing.verifyWrite(permit,fixture.facts(permit),FencedTargetVerifierTest.BODY,null));assertEquals(calls,http.calls);
        for(String origin:List.of("http://control.test","https://user@control.test","https://control.test/path","https://control.test?query","https://control.test/#fragment"))
            assertThrows(IllegalArgumentException.class,()->new ControlTargetAuthorization(http,URI.create(origin),"control","tenant",fixture.target,()->"fixture",fixture.clock));
        var interrupted=new Transport();interrupted.interrupted=true;
        var facts=verified();
        try{
            assertThrows(SecurityException.class,()->client(interrupted).consumeClaim(facts,owner(facts)));
            assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }
}
