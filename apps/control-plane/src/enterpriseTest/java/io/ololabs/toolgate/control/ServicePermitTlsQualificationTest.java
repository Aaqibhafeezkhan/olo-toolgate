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
import java.security.spec.RSAPublicKeySpec;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real mTLS service boundary and a separate transactional storage effect using Control's admitted principal. */
class ServicePermitTlsQualificationTest {
    @TempDir Path temp;
    @Test void admittedPrincipalCrossesTlsIntoAStorageTransactionAndForgedBodyCannotCommit()throws Exception {
        EnterpriseOperationTest.signingKey();
        var fixture=new ServicePermitBridgeTest();fixture.temp=temp;fixture.setup();
        var rig=fixture.rig;
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
        var invocation=fixture.start("tls-storage");
        var permits=new ServicePermits(rig.store,rig.graph.codec,rig.operations);
        byte[] body=rig.graph.codec.json(fixture.request(invocation.id()).request().arguments()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var profile=new HttpRequestFingerprint.Profile("profile","sha256:"+"a".repeat(64),"write","toolgate-service-permit:olo-memory",List.of("toolgate-service-permit"));
        var destination=java.net.URI.create("https://memory.test/v1/memory.append");
        var expectedHeaders=Map.of("content-type",List.of("application/json"),"accept",List.of("*/*"),"x-recipient",List.of("alice"));
        String httpDigest=HttpRequestFingerprint.digest(profile,destination,"POST",expectedHeaders,body);
        var permit=rig.graph.codec.model(permits.issue(rig.gateway.tenant(),tx->"device",executorHash,
            rig.graph.codec.json(new ServicePermitIssue(invocation.id(),invocation.reservedNonce(),ServiceAudience.olo_memory,httpDigest))).body(),ServicePermit.class);
        var publicKey=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(EnterpriseOperationTest.key.getModulus(),EnterpriseOperationTest.key.getPublicExponent()));
        var verifier=new ServicePermitVerifier("control",ServiceAudience.olo_memory,Map.of("permit-key",publicKey),rig.clock,
            tenant->null,(audience,request)->{permits.consume(rig.gateway,audience,rig.graph.codec.json(request));return true;});
        Path storage=temp.resolve("service-storage.sqlite");
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+storage);var statement=connection.createStatement()){
            statement.execute("CREATE TABLE effects(invocation TEXT PRIMARY KEY,tenant TEXT,subject TEXT,actor TEXT)");
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
                        assertEquals("POST /v1/memory.append HTTP/1.1",lines[0]);
                        var actualHeaders=new LinkedHashMap<String,List<String>>();
                        for(int n=1;n<lines.length;n++){int colon=lines[n].indexOf(':');assertTrue(colon>0);
                            String name=lines[n].substring(0,colon).toLowerCase(Locale.ROOT);
                            actualHeaders.computeIfAbsent(name,k->new ArrayList<>()).add(lines[n].substring(colon+1).stripLeading());
                        }
                        byte[] actualBody=input.readNBytes(Integer.parseInt(actualHeaders.get("content-length").getFirst()));
                        var transport=new ServicePermitVerifier.Transport(profile,destination,"POST",actualHeaders);
                        var request=new ServicePermitVerifier.Request(rig.gateway.tenant().value(),"memory.append",invocation.evaluation().resources(),actualBody,transport);
                        var changed=new LinkedHashMap<>(actualHeaders);changed.put("x-recipient",List.of("bob"));
                        assertThrows(SecurityException.class,()->verifier.authorize(permit,new ServicePermitVerifier.Request(
                            request.tenant(),request.operation(),request.resources(),actualBody,
                            new ServicePermitVerifier.Transport(profile,destination,"POST",changed)),socket.getSession()));
                        var authorization=verifier.authorize(permit,request,socket.getSession());
                        var principal=authorization.claims();
                        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+storage)){
                            connection.setAutoCommit(false);
                            try(var statement=connection.prepareStatement("INSERT INTO effects VALUES(?,?,?,?)")){
                                statement.setString(1,principal.invocationId());statement.setString(2,principal.tenantId());
                                statement.setString(3,principal.sub());statement.setString(4,principal.act());statement.executeUpdate();
                            }
                            authorization.checkCommitDeadline();connection.commit();
                        }
                        assertThrows(SecurityException.class,()->verifier.authorize(permit,new ServicePermitVerifier.Request(
                            request.tenant(),request.operation(),request.resources(),"{}".getBytes()),socket.getSession()));
                        socket.getOutputStream().write(1);return principal;
                    }
                });
                try(var client=(SSLSocket)tls.getSocketFactory().createSocket("localhost",server.getLocalPort())){
                    client.startHandshake();
                    String head="POST /v1/memory.append HTTP/1.1\r\nHost: memory.test\r\nContent-Type: application/json\r\nAccept: */*\r\nX-Recipient: alice\r\nToolgate-Service-Permit: "+permit.jws()+"\r\nContent-Length: "+body.length+"\r\n\r\n";
                    client.getOutputStream().write(head.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    client.getOutputStream().write(body);client.getOutputStream().flush();
                    assertEquals(1,client.getInputStream().read());
                }
                var principal=result.get(15,TimeUnit.SECONDS);assertEquals("alice",principal.sub());assertNull(principal.act());
                try(var connection=DriverManager.getConnection("jdbc:sqlite:"+storage);var rows=connection.createStatement().executeQuery("SELECT tenant,subject,actor FROM effects")){
                    assertTrue(rows.next());assertEquals(rig.gateway.tenant().value(),rows.getString(1));assertEquals("alice",rows.getString(2));assertNull(rows.getString(3));assertFalse(rows.next());
                }
            } finally {pool.shutdownNow();}
        }
    }
}
