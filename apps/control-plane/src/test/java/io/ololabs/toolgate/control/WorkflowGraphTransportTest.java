// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.contracts.v2.WorkflowGraphBuildRequest;
import java.net.InetSocketAddress;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import static org.junit.jupiter.api.Assertions.*;

/** Real mTLS transport, strict destination trust and bounded compilation receipts. */
final class WorkflowGraphTransportTest {
    @Test void requiresMutualTlsAndRejectsRedirectsAndUnboundedReplies() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var key = generator.generateKeyPair();
        var subject = new X500Name("CN=127.0.0.1"); long now = System.currentTimeMillis();
        var certificate = new JcaX509v3CertificateBuilder(subject, java.math.BigInteger.ONE, new Date(now - 1000), new Date(now + 600000), subject, key.getPublic());
        certificate.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));
        var cert = new JcaX509CertificateConverter().getCertificate(certificate.build(new JcaContentSignerBuilder("SHA256withRSA").build(key.getPrivate())));
        var keys = KeyStore.getInstance("PKCS12"); keys.load(null, null); char[] password = UUID.randomUUID().toString().toCharArray();
        keys.setKeyEntry("service", key.getPrivate(), password, new java.security.cert.Certificate[]{cert});
        var km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, password);
        var trusted = KeyStore.getInstance("PKCS12"); trusted.load(null, null); trusted.setCertificateEntry("service", cert);
        var tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); tm.init(trusted);
        var tls = javax.net.ssl.SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        var anonymous = javax.net.ssl.SSLContext.getInstance("TLS"); anonymous.init(null, tm.getTrustManagers(), null);
        var mode = new java.util.concurrent.atomic.AtomicReference<>("valid");
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var codec = new ContractCodec();
        var request = new WorkflowGraphBuildRequest("tenant", "support", "sha256:" + "a".repeat(64), "investigate", 0L);
        var receipt = new io.ololabs.toolgate.contracts.v2.WorkflowGraphBuildResult(request.tenantId(), request.profileId(), request.profileDigest(),
            request.workflowId(), 0L, "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64), List.of("value"), "READY");
        var pool = java.util.concurrent.Executors.newCachedThreadPool();
        var server = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 2);
        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls) {
            @Override public void configure(com.sun.net.httpserver.HttpsParameters parameters) {
                var settings = tls.getDefaultSSLParameters(); settings.setNeedClientAuth(true); parameters.setSSLParameters(settings);
            }
        });
        server.setExecutor(pool);
        server.createContext("/internal/runtime/v1/graphs:rebuild", exchange -> {
            requests.incrementAndGet();
            try {
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals(request, codec.model(new String(exchange.getRequestBody().readNBytes(8193), java.nio.charset.StandardCharsets.UTF_8), WorkflowGraphBuildRequest.class));
                exchange.getResponseHeaders().set("Content-Type", mode.get().equals("media") ? "text/plain" : "application/json");
                if (mode.get().equals("redirect")) {
                    exchange.getResponseHeaders().set("Location", "https://169.254.169.254/"); exchange.sendResponseHeaders(302, -1);
                } else {
                    byte[] bytes = mode.get().equals("large") ? new byte[8193] : codec.json(receipt).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                }
            } finally { exchange.close(); }
        });
        server.start();
        try {
            String origin = "https://127.0.0.1:" + server.getAddress().getPort();
            var builder = new HttpsWorkflowGraphBuilder(origin, codec, tls);
            assertEquals(receipt, builder.build(request));
            for (var invalid : List.of("redirect", "large", "media")) {
                mode.set(invalid); assertThrows(Failure.class, () -> builder.build(request));
            }
            mode.set("valid");
            assertThrows(Failure.class, () -> new HttpsWorkflowGraphBuilder(origin, codec, anonymous).build(request));
            assertThrows(Failure.class, () -> new HttpsWorkflowGraphBuilder(origin, codec, javax.net.ssl.SSLContext.getDefault()).build(request));
            assertEquals(4, requests.get(), "unauthenticated/untrusted connections never reach the build handler");
            assertEquals(receipt, builder.build(request));
            assertThrows(IllegalArgumentException.class, () -> new HttpsWorkflowGraphBuilder("http://127.0.0.1", codec, tls));
        } finally { server.stop(0); pool.shutdownNow(); Arrays.fill(password, '\0'); }
    }
}
