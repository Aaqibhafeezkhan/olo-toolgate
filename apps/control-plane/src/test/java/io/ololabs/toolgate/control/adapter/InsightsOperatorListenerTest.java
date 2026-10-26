// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.contracts.v2.InsightsMode;
import io.ololabs.toolgate.control.application.InsightsProjector;
import io.ololabs.toolgate.control.application.InsightsService;
import io.ololabs.toolgate.control.fixtures.Tenants;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** ADR 031 §8: the operator API answers only clients holding a certificate from the operator CA. */
final class InsightsOperatorListenerTest {
    @TempDir Path directory;
    private io.vertx.core.Vertx vertx;

    private record Issued(KeyPair keys, X509Certificate certificate) {}
    private static Issued issue(String subject, Issued issuer, boolean ca) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var keys = generator.generateKeyPair();
        var now = System.currentTimeMillis();
        var builder = new JcaX509v3CertificateBuilder(issuer == null ? new X500Name(subject) : new X500Name(issuer.certificate().getSubjectX500Principal().getName()),
            BigInteger.valueOf(now), new Date(now - 60_000), new Date(now + 3_600_000), new X500Name(subject), keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        if (!ca) builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuer == null ? keys.getPrivate() : issuer.keys().getPrivate());
        return new Issued(keys, new JcaX509CertificateConverter().getCertificate(builder.build(signer)));
    }
    private Path pem(String name, Object value) throws Exception {
        var path = directory.resolve(name);
        try (var writer = new JcaPEMWriter(Files.newBufferedWriter(path))) { writer.writeObject(value); }
        return path;
    }
    private static HttpClient client(Issued trusted, Issued identity) throws Exception {
        var trust = KeyStore.getInstance("PKCS12"); trust.load(null, null); trust.setCertificateEntry("ca", trusted.certificate());
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(trust);
        KeyManager[] keyManagers = null;
        if (identity != null) {
            var keys = KeyStore.getInstance("PKCS12"); keys.load(null, null);
            keys.setKeyEntry("me", identity.keys().getPrivate(), new char[0], new java.security.cert.Certificate[]{identity.certificate()});
            var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); kmf.init(keys, new char[0]); keyManagers = kmf.getKeyManagers();
        }
        var context = SSLContext.getInstance("TLS"); context.init(keyManagers, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(context).build();
    }

    @AfterEach void close() { if (vertx != null) vertx.close(); }

    @Test void onlyOperatorCertificatesReachTheApi() throws Exception {
        var operatorCa = issue("CN=Operator CA", null, true);
        var server = issue("CN=control", operatorCa, false);
        var operator = issue("CN=ops-1", operatorCa, false);
        var rogue = issue("CN=rogue", issue("CN=Rogue CA", null, true), false);

        var store = new PostgresStore(Tenants.database(SqliteState.open(directory.resolve("listener.sqlite"))), new ContractCodec());
        store.insightsMode(InsightsMode.BASIC);
        var listener = new InsightsOperatorListener();
        vertx = io.vertx.core.Vertx.vertx(); listener.vertx = vertx; listener.codec = new ContractCodec();
        listener.insights = new InsightsService(store, InsightsProjector.Settings.defaults(), (cursor, limit) -> List.of(), store::insightsMode);
        listener.listen("127.0.0.1", 0, pem("server.pem", server.certificate()).toString(), pem("server.key", server.keys().getPrivate()).toString(),
            pem("ca.pem", operatorCa.certificate()).toString());
        var status = URI.create("https://127.0.0.1:" + listener.port() + "/insights/v1/status");

        var ok = client(operatorCa, operator).send(HttpRequest.newBuilder(status).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"mode\":\"BASIC\""), ok.body());
        assertEquals("no-store", ok.headers().firstValue("Cache-Control").orElse(""));

        var bad = client(operatorCa, operator).send(HttpRequest.newBuilder(URI.create(status.toString().replace("status", "series?granularity=WEEK&from=0&to=1"))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bad.statusCode());

        // The page: the root redirects to it, and only the page and hashed assets are served, with the console's policy.
        var root = client(operatorCa, operator).send(HttpRequest.newBuilder(URI.create(status.toString().replace("insights/v1/status", ""))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, root.statusCode());
        assertEquals("/console/insights.html", root.headers().firstValue("Location").orElse(""));
        for (var path : List.of("console/index.html", "console/../application.properties", "console/assets/x.map", "console/%2e%2e/secret")) {
            var refused = client(operatorCa, operator).send(HttpRequest.newBuilder(URI.create(status.toString().replace("insights/v1/status", path))).build(),
                HttpResponse.BodyHandlers.ofString());
            assertTrue(refused.statusCode() == 404 || refused.statusCode() == 400, path + " " + refused.statusCode());
        }
        var page = client(operatorCa, operator).send(HttpRequest.newBuilder(URI.create(status.toString().replace("insights/v1/status", "console/insights.html"))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElse(""));

        // No certificate, or one from another CA: the TLS handshake fails before any route runs.
        assertThrows(java.io.IOException.class, () -> client(operatorCa, null).send(HttpRequest.newBuilder(status).build(), HttpResponse.BodyHandlers.ofString()));
        assertThrows(java.io.IOException.class, () -> client(operatorCa, rogue).send(HttpRequest.newBuilder(status).build(), HttpResponse.BodyHandlers.ofString()));
        listener.stop();
    }
}
