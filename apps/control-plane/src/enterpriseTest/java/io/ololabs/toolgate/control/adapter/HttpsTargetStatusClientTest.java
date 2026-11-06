// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.v2.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual TLS/HTTP with explicit test DNS/dial seams; no production address-policy bypass. */
class HttpsTargetStatusClientTest {
    @TempDir static Path temp;
    static SSLContext tls;static String pem,pin;
    static final String KEY="tg-"+"a".repeat(64);
    static final InetAddress PUBLIC=address(93,184,215,14);
    final ContractCodec codec=new ContractCodec();
    static InetAddress address(int... bytes){
        byte[] raw=new byte[bytes.length];for(int n=0;n<bytes.length;n++)raw[n]=(byte)bytes[n];
        try{return InetAddress.getByAddress(raw);}catch(Exception invalid){throw new IllegalArgumentException(invalid);}
    }
    @BeforeAll static void certificate()throws Exception{
        var file=temp.resolve("target.p12");
        var tool=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool");
        var process=new ProcessBuilder(tool.toString(),"-genkeypair","-alias","target","-keyalg","RSA","-keysize","2048",
            "-dname","CN=target.example.test","-ext","SAN=DNS:target.example.test","-ext","EKU=serverAuth",
            "-startdate","2020/01/01 00:00:00","-validity","3650","-storetype","PKCS12","-keystore",file.toString(),
            "-storepass","fixture-only-password","-noprompt").redirectErrorStream(true).start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes()));
        var keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(file)){keys.load(in,"fixture-only-password".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"fixture-only-password".toCharArray());
        tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),null,new SecureRandom());
        byte[] certificate=keys.getCertificate("target").getEncoded();
        pin="sha256/"+Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate));
        pem="-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(certificate)+"\n-----END CERTIFICATE-----\n";
    }
    ObjectNode profile()throws Exception{
        var source=Path.of(System.getProperty("toolgate.fixtures")).getParent().getParent().resolve("v2/valid.json");
        var value=(ObjectNode)new ObjectMapper().readTree(Files.readString(source)).path("ServiceProfile").deepCopy();
        var destination=(ObjectNode)value.path("destinations").get(0);
        destination.put("host","target.example.test").put("port",443).put("scheme","https").put("mode","BROKERED");
        var settings=destination.putObject("tls");settings.put("caBundle",pem);settings.putArray("spkiPins").add(pin);
        var route=(ObjectNode)value.path("routes").get(0);
        route.put("id","status").put("destination",destination.path("id").asText()).put("method","GET").put("path","/effects/{effectKey:tg-[a-f0-9]+}")
            .put("streaming","NONE").put("maxResponseBytes",65536);route.putArray("query");route.putArray("headers");route.remove("auth");
        value.putArray("privateRanges");
        var review=value.putArray("effectReviews").addObject().put("reviewedBy","reviewer").put("reviewedAtUnixMs",1);
        var policy=review.putObject("policy").put("routeId","status").put("classification","READ")
            .put("consentRequired",false).put("acknowledgedUnfencedRisk",false);
        var intent=policy.putObject("intent").put("path","/effects/{effectKey:tg-[a-f0-9]+}").put("contentType","").put("bodyMode","NONE").putNull("body");
        intent.putObject("query");intent.putObject("headers");return value;
    }
    TargetStatusClient.Request request(ObjectNode profile){return new TargetStatusClient.Request(
        codec.model(profile.toString(),ServiceProfile.class),"status",KEY,Map.of("x-api-key","fixture-target-key"));}
    String completed(){return "{\"key\":\""+KEY+"\",\"state\":\"COMPLETED\",\"bodyDigest\":\"sha256:"+"b".repeat(64)
        +"\",\"businessReceipt\":\"receipt-1\",\"resultDigest\":\"sha256:"+"c".repeat(64)+"\"}";}
    String response(String body){return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
        +body.getBytes(StandardCharsets.UTF_8).length+"\r\n\r\n"+body;}
    TargetEffectStatusReply exchange(ObjectNode profile,String reply,boolean expectRequest)throws Exception{
        try(var server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress())){
            server.setSoTimeout(5000);
            var pool=Executors.newSingleThreadExecutor();
            var observed=new AtomicInteger();
            var accepted=pool.submit(()->{
                try(var socket=(SSLSocket)server.accept()){
                    socket.setSoTimeout(5000);socket.startHandshake();
                    var input=socket.getInputStream();var bytes=new java.io.ByteArrayOutputStream();int tail=0;
                    while(tail!=0x0d0a0d0a){
                        int b=input.read();if(b<0)return;assertTrue(bytes.size()<16384);bytes.write(b);tail=(tail<<8)|b;
                    }
                    String head=bytes.toString(StandardCharsets.US_ASCII);
                    assertTrue(head.startsWith("GET /effects/"+KEY+" HTTP/1.1\r\nHost: target.example.test\r\n"));
                    assertTrue(head.contains("\r\nx-api-key: fixture-target-key\r\n"));
                    assertFalse(head.contains("POST"));observed.incrementAndGet();
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
                }catch(java.io.IOException refused){if(expectRequest)throw new IllegalStateException(refused);}
            });
            try{
                var client=new HttpsTargetStatusClient(codec,host->new InetAddress[]{PUBLIC},
                    (address,port)->{assertEquals(PUBLIC,address);assertEquals(443,port);return new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());});
                return client.lookup(request(profile));
            }finally{
                accepted.get(6,TimeUnit.SECONDS);pool.shutdownNow();
                assertEquals(expectRequest?1:0,observed.get());
            }
        }
    }
    @Test void verifiedHttpsGetReturnsOnlyTheClosedCurrentTargetStatus()throws Exception{
        var result=exchange(profile(),response(completed()),true);
        assertEquals(TargetEffectStatus.COMPLETED,result.state());assertEquals("receipt-1",result.businessReceipt());
    }
    @Test void redirectsOversizedAndAmbiguousResponsesNeverBecomeWitnesses()throws Exception{
        for(String response:List.of("HTTP/1.1 302 Found\r\nLocation: https://other.test/\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 65537\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
            response("{\"key\":\""+KEY+"\",\"state\":\"COMPLETED\"}"),
            response(completed().replace(KEY,"tg-"+"d".repeat(64))))){
            assertThrows(Failure.class,()->exchange(profile(),response,true));
        }
    }
    @Test void wrongCaHostnameAndVerifiedLeafPinStopBeforeAnyRequestBytes()throws Exception{
        var wrongPin=profile();((ObjectNode)wrongPin.path("destinations").get(0).path("tls")).putArray("spkiPins").add("sha256/"+"A".repeat(43)+"=");
        assertThrows(Failure.class,()->exchange(wrongPin,response(completed()),false));
        var wrongHost=profile();((ObjectNode)wrongHost.path("destinations").get(0)).put("host","other.example.test");
        assertThrows(Failure.class,()->exchange(wrongHost,response(completed()),false));
        var wrongCa=profile();((ObjectNode)wrongCa.path("destinations").get(0).path("tls")).remove("caBundle");
        assertThrows(Failure.class,()->exchange(wrongCa,response(completed()),false));
    }
    @Test void everyDnsAnswerIsCheckedAndNoRefusedAddressIsDialed()throws Exception{
        var calls=new AtomicInteger();
        var client=new HttpsTargetStatusClient(codec,host->new InetAddress[]{PUBLIC,address(127,0,0,1)},
            (address,port)->{calls.incrementAndGet();throw new AssertionError("Refused address contacted");});
        assertThrows(Failure.class,()->client.lookup(request(profile())));assertEquals(0,calls.get());
        var model=request(profile()).profile();var destination=model.destinations().getFirst();
        for(var address:List.of(address(0,0,0,0),address(127,0,0,1),address(169,254,169,254),address(10,1,2,3),address(224,0,0,1)))
            assertFalse(HttpsTargetStatusClient.allowed(address,destination,model));
    }
    @Test void unsupportedRoutesAndCredentialSmugglingAreRefusedBeforeDns()throws Exception{
        var client=new HttpsTargetStatusClient(codec,host->{throw new AssertionError("Invalid route resolved");},
            (address,port)->{throw new AssertionError("Invalid route contacted");});
        for(String field:List.of("method","path","streaming")){
            var profile=profile();((ObjectNode)profile.path("routes").get(0)).put(field,
                field.equals("method")?"POST":field.equals("streaming")?"SSE":"/status/{effectKey}?admin=true");
            assertThrows(Failure.class,()->client.lookup(request(profile)));
        }
        var req=request(profile());
        assertThrows(Failure.class,()->client.lookup(new TargetStatusClient.Request(req.profile(),req.routeId(),KEY,Map.of("host","evil.test"))));
        assertThrows(Failure.class,()->client.lookup(new TargetStatusClient.Request(req.profile(),req.routeId(),KEY,Map.of("authorization","secret\r\nx-admin: true"))));
        assertFalse(req.toString().contains(KEY));assertFalse(req.toString().contains("fixture-target-key"));
    }
}
