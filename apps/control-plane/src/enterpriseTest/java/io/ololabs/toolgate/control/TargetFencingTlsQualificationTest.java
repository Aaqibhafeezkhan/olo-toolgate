// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.application.ServicePermits;
import io.ololabs.toolgate.service.ServicePermitVerifier;
import io.ololabs.toolgate.service.HttpRequestFingerprint;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.nio.file.*;
import java.security.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real mTLS service boundary and a separate transactional storage effect using Control's admitted principal. */
class TargetFencingTlsQualificationTest {
    @TempDir Path temp;
    @Test void genericTargetCrossesTlsWithCurrentPeerBindingAndDurableOutcome()throws Exception {
        EnterpriseOperationTest.signingKey();
        var fixture=new EffectGrantTest();fixture.temp=temp;fixture.fencedTarget=new io.ololabs.toolgate.contracts.v2.FencedTarget("billing",io.ololabs.toolgate.contracts.v2.FencedTargetClass.SHARED_SQL);fixture.setup();
        var rig=fixture.base.rig;
        Path keyStore=temp.resolve("executor.p12");
        String executable=System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool";
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-genkeypair",
            "-alias","executor","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=DNS:localhost,IP:127.0.0.1",
            "-ext","EKU=serverAuth,clientAuth","-startdate","2020/01/01 00:00:00","-validity","3650",
            "-storetype","PKCS12","-keystore",keyStore.toString(),"-storepass","test-only-password","-noprompt")
            .redirectErrorStream(true).start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes()));
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(keyStore)){keys.load(input,"test-only-password".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"test-only-password".toCharArray());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        var tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
        String executorHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("executor").getPublicKey().getEncoded()));
        var invocation=fixture.invocation;
        var targets=new io.ololabs.toolgate.control.application.TargetClaims(rig.store,rig.graph.codec,rig.operations);
        byte[] body=rig.graph.codec.json(fixture.issue.arguments()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var bound=fixture.issue.httpRequestManifest();
        var profile=new HttpRequestFingerprint.Profile(bound.profileId(),bound.profileDigest(),"write",null,List.of());
        var destination=java.net.URI.create("https://external.example.test/send");
        var grant=rig.graph.codec.model(fixture.grants.issue(rig.gateway.tenant(),tx->"device",executorHash,
            rig.graph.codec.json(fixture.issue)).body(),io.ololabs.toolgate.contracts.v2.EffectGrant.class);
        var permit=grant.grant();
        fixture.send(grant,"handler");
        var controlServer=com.sun.net.httpserver.HttpsServer.create(new java.net.InetSocketAddress("localhost",0),0);
        controlServer.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls));
        controlServer.createContext("/api/control/v1/access/target-permits/billing/",exchange->{
            try{
                assertEquals("Bearer fixture-control",exchange.getRequestHeaders().getFirst("Authorization"));
                String path=exchange.getRequestURI().getPath();
                String document=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                String reply=path.endsWith("/jwks")?rig.graph.codec.json(targets.keys()):
                    path.endsWith("/claims/consume")?targets.consume(rig.gateway,"billing",document).body():
                    path.endsWith("/claims/status")?targets.inspect(rig.gateway,"billing",document).body():
                    path.endsWith("/claims/outcome")?targets.outcome(rig.gateway,"billing",document).body():null;
                if(reply==null){exchange.sendResponseHeaders(404,-1);return;}
                byte[] bytes=reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}
        });
        controlServer.start();
        var http=java.net.http.HttpClient.newBuilder().sslContext(tls)
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        var authorizationClient=new io.ololabs.toolgate.service.ControlTargetAuthorization(http,
            java.net.URI.create("https://localhost:"+controlServer.getAddress().getPort()),"control",
            rig.gateway.tenant().value(),fixture.fencedTarget,()->"fixture-control",rig.clock);
        var localClaims=new io.ololabs.toolgate.service.JdbcEffectClaims();
        Path storage=temp.resolve("service-storage.sqlite");
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+storage);var statement=connection.createStatement()){
            statement.execute("CREATE TABLE effects(effect_id TEXT PRIMARY KEY,tenant TEXT)");
            connection.setAutoCommit(false);
            io.ololabs.toolgate.service.JdbcEffectClaims.initialize(connection);connection.commit();
        }
        try(var server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){
            server.setNeedClientAuth(true);
            var pool=Executors.newSingleThreadExecutor();
            try {
                var result=pool.submit(()->{
                    try(var socket=(SSLSocket)server.accept()){
                        socket.startHandshake();
                        socket.setSoTimeout(10000);
                        var input=socket.getInputStream();var head=new java.io.ByteArrayOutputStream();
                        while(!head.toString(java.nio.charset.StandardCharsets.US_ASCII).endsWith("\r\n\r\n")){
                            int next=input.read();assertTrue(next>=0);head.write(next);assertTrue(head.size()<65536);
                        }
                        String[] lines=head.toString(java.nio.charset.StandardCharsets.US_ASCII).split("\r\n");
                        assertEquals("POST /send HTTP/1.1",lines[0]);
                        var actualHeaders=new LinkedHashMap<String,List<String>>();
                        for(int n=1;n<lines.length;n++){int colon=lines[n].indexOf(':');assertTrue(colon>0);
                            String name=lines[n].substring(0,colon).toLowerCase(Locale.ROOT);
                            actualHeaders.computeIfAbsent(name,k->new ArrayList<>()).add(lines[n].substring(colon+1).stripLeading());
                        }
                        byte[] actualBody=input.readNBytes(Integer.parseInt(actualHeaders.get("content-length").getFirst()));
                        var transport=new ServicePermitVerifier.Transport(profile,destination,"POST",actualHeaders);
                        assertThrows(SecurityException.class,()->authorizationClient.verifyWrite(permit,transport,"{}".getBytes(),socket.getSession()));
                        var verified=authorizationClient.verifyWrite(permit,transport,actualBody,socket.getSession());
                        io.ololabs.toolgate.service.JdbcEffectClaims.Owner owner;
                        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+storage)){
                            connection.setAutoCommit(false);
                            owner=localClaims.claim(connection,verified.binding(),verified.expiresAt()).owner();connection.commit();
                        }
                        assertNotNull(owner);
                        var authorization=authorizationClient.consumeClaim(verified,owner);
                        var binding=verified.binding();
                        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+storage)){
                            connection.setAutoCommit(false);
                            assertTrue(localClaims.beginApply(connection,owner));
                            try(var statement=connection.prepareStatement("INSERT INTO effects VALUES(?,?)")){
                                statement.setString(1,binding.effectId());statement.setString(2,binding.tenant());statement.executeUpdate();
                            }
                            assertTrue(localClaims.finish(connection,owner,true,
                                "sha256:"+"a".repeat(64)));
                            authorization.checkCommitDeadline();connection.commit();
                        }
                        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+storage)){
                            connection.setAutoCommit(false);
                            var pending=localClaims.pendingOutcome(connection,binding);assertNotNull(pending);
                            assertEquals(ServicePermitClaimState.COMPLETED,authorizationClient.reportOutcome(pending).status());
                            assertEquals(ServicePermitClaimState.COMPLETED,authorizationClient.reportOutcome(pending).status());
                            assertTrue(localClaims.acknowledgeOutcome(connection,pending));connection.commit();
                        }
                        socket.getOutputStream().write(1);return binding;
                    }
                });
                try(var client=(SSLSocket)tls.getSocketFactory().createSocket("localhost",server.getLocalPort())){
                    client.startHandshake();
                    String head="POST /send HTTP/1.1\r\nHost: external.example.test\r\nContent-Type: application/json\r\nAccept: */*\r\nToolgate-Effect-Permit: "+permit.jws()+"\r\nContent-Length: "+body.length+"\r\n\r\n";
                    client.getOutputStream().write(head.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    client.getOutputStream().write(body);client.getOutputStream().flush();
                    assertEquals(1,client.getInputStream().read());
                }
                var binding=result.get(15,TimeUnit.SECONDS);assertEquals(rig.gateway.tenant().value(),binding.tenant());
                try(var connection=DriverManager.getConnection("jdbc:sqlite:"+storage);var rows=connection.createStatement().executeQuery("SELECT effect_id,tenant FROM effects")){
                    assertTrue(rows.next());assertEquals(binding.effectId(),rows.getString(1));assertEquals(binding.tenant(),rows.getString(2));assertFalse(rows.next());
                }
            } finally {pool.shutdownNow();}
        }finally{controlServer.stop(0);http.close();}
    }
}
