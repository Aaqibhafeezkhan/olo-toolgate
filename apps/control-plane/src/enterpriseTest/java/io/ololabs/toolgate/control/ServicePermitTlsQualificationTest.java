// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.application.ServicePermits;
import io.ololabs.toolgate.service.ServicePermitVerifier;
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
        var permit=rig.graph.codec.model(permits.issue(rig.gateway.tenant(),tx->"device",executorHash,
            rig.graph.codec.json(new ServicePermitIssue(invocation.id(),invocation.reservedNonce(),ServiceAudience.olo_memory))).body(),ServicePermit.class);
        var publicKey=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(EnterpriseOperationTest.key.getModulus(),EnterpriseOperationTest.key.getPublicExponent()));
        var verifier=new ServicePermitVerifier("control",ServiceAudience.olo_memory,Map.of("permit-key",publicKey),rig.clock,
            tenant->null,(audience,request)->{permits.consume(rig.gateway,audience,rig.graph.codec.json(request));return true;});
        byte[] body=rig.graph.codec.json(fixture.request(invocation.id()).request().arguments()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
                        var request=new ServicePermitVerifier.Request(rig.gateway.tenant().value(),"memory.append",invocation.evaluation().resources(),body);
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
                    client.startHandshake();assertEquals(1,client.getInputStream().read());
                }
                var principal=result.get(15,TimeUnit.SECONDS);assertEquals("alice",principal.sub());assertNull(principal.act());
                try(var connection=DriverManager.getConnection("jdbc:sqlite:"+storage);var rows=connection.createStatement().executeQuery("SELECT tenant,subject,actor FROM effects")){
                    assertTrue(rows.next());assertEquals(rig.gateway.tenant().value(),rows.getString(1));assertEquals("alice",rows.getString(2));assertNull(rows.getString(3));assertFalse(rows.next());
                }
            } finally {pool.shutdownNow();}
        }
    }
}
