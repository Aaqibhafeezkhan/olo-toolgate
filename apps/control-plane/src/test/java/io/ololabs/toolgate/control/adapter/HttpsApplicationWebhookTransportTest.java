// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import java.net.InetAddress;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** No Internet requests: destination denials occur before any socket is opened. */
final class HttpsApplicationWebhookTransportTest {
    private final ContractCodec codec=new ContractCodec();
    @Test void rejectsUnapprovedOriginsCredentialsQueriesRedirectTargetsAndPorts() {
        var transport=new HttpsApplicationWebhookTransport(Set.of("hooks.example.com"),codec,Clock.systemUTC());
        for(String endpoint:List.of("http://hooks.example.com/events","https://127.0.0.1/events","https://localhost/events",
            "https://other.example.com/events","https://user:password@hooks.example.com/events","https://hooks.example.com:8443/events",
            "https://hooks.example.com/events?token=private","https://hooks.example.com/events#fragment"))
            assertEquals(400,assertThrows(Failure.class,()->transport.validate(endpoint)).status(),endpoint);
        assertDoesNotThrow(()->transport.validate("https://hooks.example.com/events"));
        assertEquals(503,assertThrows(Failure.class,()->new HttpsApplicationWebhookTransport(Set.of(),codec,Clock.systemUTC()).validate("https://hooks.example.com/events")).status());
    }
    @Test void rejectsPrivateMetadataSpecialUseAndTransitionAddresses()throws Exception {
        for(String address:List.of("0.0.0.0","10.1.2.3","100.64.0.1","127.0.0.1","169.254.169.254","172.16.0.1",
            "192.168.0.1","192.0.2.1","198.18.0.1","198.51.100.1","203.0.113.1","224.0.0.1","255.255.255.255",
            "::1","fc00::1","fe80::1","2001:db8::1","2002:7f00:1::1","2001::1"))
            assertFalse(HttpsApplicationWebhookTransport.publicAddress(InetAddress.getByName(address)),address);
        assertTrue(HttpsApplicationWebhookTransport.publicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(HttpsApplicationWebhookTransport.publicAddress(InetAddress.getByName("2001:4860:4860::8888")));
    }
    @Test void privateOrMixedDnsAnswersAreRejectedBeforeTransport()throws Exception {
        var loopback=InetAddress.getByName("127.0.0.1");var publicAddress=InetAddress.getByName("8.8.8.8");
        for(var answers:List.of(new InetAddress[]{loopback},new InetAddress[]{publicAddress,loopback})) {
            var transport=new HttpsApplicationWebhookTransport(Set.of("hooks.example.com"),codec,Clock.systemUTC(),host->answers,
                java.net.Socket::new,(javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault());
            assertEquals(400,assertThrows(Failure.class,()->transport.verify("https://hooks.example.com/events","sub-test","challenge",new byte[32])).status());
        }
    }
    @Test void signatureMatchesIndependentHmacSha256Vector() {
        byte[] key=new byte[20];Arrays.fill(key,(byte)0x0b);
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",HttpsApplicationWebhookTransport.signature(key,"Hi There"));
    }
    @Test void realTlsVerifiesOwnershipPinsTheAddressAndNeverFollowsRedirects()throws Exception {
        var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        var subject=new org.bouncycastle.asn1.x500.X500Name("CN=hooks.example.com");long now=System.currentTimeMillis();
        var certificate=new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(subject,java.math.BigInteger.ONE,new Date(now-1000),new Date(now+600000),subject,pair.getPublic());
        certificate.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,false,
            new org.bouncycastle.asn1.x509.GeneralNames(new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName,"hooks.example.com")));
        var cert=new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(certificate.build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
        var keystore=java.security.KeyStore.getInstance("PKCS12");keystore.load(null,null);char[] password=UUID.randomUUID().toString().toCharArray();
        keystore.setKeyEntry("peer",pair.getPrivate(),password,new java.security.cert.Certificate[]{cert});
        var km=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());km.init(keystore,password);
        var trust=java.security.KeyStore.getInstance("PKCS12");trust.load(null,null);trust.setCertificateEntry("peer",cert);
        var tm=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        var tls=javax.net.ssl.SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        var mode=new java.util.concurrent.atomic.AtomicReference<>("valid");var calls=new java.util.concurrent.atomic.AtomicInteger();var dns=new java.util.concurrent.atomic.AtomicInteger();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var server=com.sun.net.httpserver.HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1",0),2);
        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls));server.setExecutor(pool);
        server.createContext("/events",exchange->{
            calls.incrementAndGet();
            try {
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                String body=new String(exchange.getRequestBody().readNBytes(65537),java.nio.charset.StandardCharsets.UTF_8);
                String timestamp=exchange.getRequestHeaders().getFirst("Toolgate-Event-Timestamp");
                assertEquals("sha256="+HttpsApplicationWebhookTransport.signature(key,timestamp+"."+body),exchange.getRequestHeaders().getFirst("Toolgate-Event-Signature"));
                var node=(com.fasterxml.jackson.databind.JsonNode)codec.value(body);
                if(mode.get().equals("redirect")){exchange.getResponseHeaders().set("Location","https://169.254.169.254/");exchange.sendResponseHeaders(302,-1);return;}
                if(node.path("type").asText().equals("VERIFY")) {
                    String proof=mode.get().equals("bad-proof")?"0".repeat(64):HttpsApplicationWebhookTransport.signature(key,"verify."+node.path("subscriptionId").asText()+"."+node.path("challenge").asText());
                    byte[] answer=codec.json(Map.of("proof",proof)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,answer.length);exchange.getResponseBody().write(answer);
                }else{assertEquals("sub-test",node.path("subscriptionId").asText());exchange.sendResponseHeaders(204,-1);}
            }finally{exchange.close();}
        });server.start();
        var sockets=(java.util.function.Supplier<java.net.Socket>)()->new java.net.Socket(){
            @Override public void connect(java.net.SocketAddress endpoint,int timeout)throws java.io.IOException {
                var address=(java.net.InetSocketAddress)endpoint;assertEquals("8.8.8.8",address.getAddress().getHostAddress());assertEquals(443,address.getPort());
                super.connect(server.getAddress(),timeout);
            }
        };
        var resolver=(java.util.function.Function<String,InetAddress[]>)host->{dns.incrementAndGet();try{return new InetAddress[]{InetAddress.getByName("8.8.8.8")};}catch(java.net.UnknownHostException invalid){throw new IllegalStateException(invalid);}};
        try {
            var transport=new HttpsApplicationWebhookTransport(Set.of("hooks.example.com","wrong.example.com"),codec,Clock.systemUTC(),resolver,sockets,tls.getSocketFactory());
            assertTrue(transport.verify("https://hooks.example.com/events","sub-test","challenge",key));
            var event=new io.ololabs.toolgate.contracts.v2.ApplicationEvent("evt-test","48558ebe-a9a8-4680-8da8-129deba2e65c",
                io.ololabs.toolgate.contracts.v2.ApplicationEventSource.WORKFLOW,"run-test",1L,io.ololabs.toolgate.contracts.v2.ApplicationEventKind.PROGRESS,now,
                Map.of("kind",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode("QUEUED")));
            assertTrue(transport.deliver("https://hooks.example.com/events","sub-test",event,key));
            mode.set("bad-proof");assertFalse(transport.verify("https://hooks.example.com/events","sub-test","challenge",key));
            mode.set("redirect");assertFalse(transport.deliver("https://hooks.example.com/events","sub-test",event,key));
            assertFalse(transport.verify("https://wrong.example.com/events","sub-test","challenge",key));
            assertEquals(4,calls.get(),"Wrong TLS hostname must not reach the HTTP handler; redirects must not issue another request");
            assertEquals(5,dns.get(),"Resolve once per attempt; socket connection uses the validated address directly");
        }finally{server.stop(0);pool.shutdownNow();Arrays.fill(password,'\0');Arrays.fill(key,(byte)0);}
    }
}
