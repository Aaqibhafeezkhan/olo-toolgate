// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual separate operator listener, real TLS peers, real application/storage supplied by the caller. */
public final class KillOperatorTlsFixture {
    private KillOperatorTlsFixture(){}
    record Issued(KeyPair keys,X509Certificate certificate){}
    static Issued issue(String subject,Issued issuer,boolean ca,boolean both)throws Exception{
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var keys=generator.generateKeyPair();
        long now=System.currentTimeMillis();
        var builder=new JcaX509v3CertificateBuilder(issuer==null?new X500Name(subject):new X500Name(issuer.certificate().getSubjectX500Principal().getName()),
            new java.math.BigInteger(128,new SecureRandom()),new Date(now-60000),new Date(now+3600000),new X500Name(subject),keys.getPublic());
        builder.addExtension(Extension.basicConstraints,true,new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage,true,new KeyUsage(ca?KeyUsage.keyCertSign|KeyUsage.cRLSign:KeyUsage.digitalSignature|KeyUsage.keyEncipherment));
        if(!ca){
            builder.addExtension(Extension.subjectAlternativeName,false,new GeneralNames(new GeneralName(GeneralName.iPAddress,"127.0.0.1")));
            builder.addExtension(Extension.extendedKeyUsage,false,new ExtendedKeyUsage(
                both?new KeyPurposeId[]{KeyPurposeId.id_kp_clientAuth,KeyPurposeId.id_kp_serverAuth}:new KeyPurposeId[]{KeyPurposeId.id_kp_clientAuth}));
        }
        var signer=new JcaContentSignerBuilder("SHA256withRSA").build(issuer==null?keys.getPrivate():issuer.keys().getPrivate());
        return new Issued(keys,new JcaX509CertificateConverter().getCertificate(builder.build(signer)));
    }
    static Path pem(Path root,String name,Object value,boolean secret)throws Exception{
        var path=root.resolve(name);try(var writer=new JcaPEMWriter(Files.newBufferedWriter(path))){writer.writeObject(value);}
        if(secret)Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        return path;
    }
    static HttpClient client(Issued trusted,Issued identity)throws Exception{
        var trust=KeyStore.getInstance("PKCS12");trust.load(null,null);trust.setCertificateEntry("root",trusted.certificate());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        KeyManager[] keys=null;
        if(identity!=null){
            var store=KeyStore.getInstance("PKCS12");store.load(null,null);
            store.setKeyEntry("client",identity.keys().getPrivate(),new char[0],new java.security.cert.Certificate[]{identity.certificate()});
            var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,new char[0]);keys=km.getKeyManagers();
        }
        var tls=SSLContext.getInstance("TLS");tls.init(keys,tm.getTrustManagers(),null);
        return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).build();
    }
    static HttpRequest.Builder request(URI uri,String document,String id){
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json")
            .header("Idempotency-Key",id).POST(HttpRequest.BodyPublishers.ofString(document));
    }
    public static void qualify(Path root,Ids.TenantId tenant,ContractCodec codec,KillSwitch kills,OperatorApprovals approvals,
            String document,String id)throws Exception{
        // ProtectedCustody deliberately rejects shared /tmp ancestors.
        var custody=Files.createTempDirectory(Path.of(System.getProperty("user.dir")).resolve("build"),"operator-custody-");
        Files.setPosixFilePermissions(custody,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var ca=issue("CN=Emergency operator CA",null,true,false);
        // The server needs a server-only EKU; construct it independently of operator leaves.
        var serverKeys=KeyPairGenerator.getInstance("RSA");serverKeys.initialize(2048);var pair=serverKeys.generateKeyPair();
        var now=System.currentTimeMillis();
        var serverBuilder=new JcaX509v3CertificateBuilder(new X500Name(ca.certificate().getSubjectX500Principal().getName()),java.math.BigInteger.ONE,
            new Date(now-60000),new Date(now+3600000),new X500Name("CN=operator-listener"),pair.getPublic());
        serverBuilder.addExtension(Extension.basicConstraints,true,new BasicConstraints(false));
        serverBuilder.addExtension(Extension.subjectAlternativeName,false,new GeneralNames(new GeneralName(GeneralName.iPAddress,"127.0.0.1")));
        serverBuilder.addExtension(Extension.extendedKeyUsage,false,new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        var server=new Issued(pair,new JcaX509CertificateConverter().getCertificate(serverBuilder.build(
            new JcaContentSignerBuilder("SHA256withRSA").build(ca.keys().getPrivate()))));
        var operator=issue("CN=Incident responder",ca,false,false);
        var wrong=issue("CN=Tenant device",issue("CN=Device CA",null,true,false),false,false);
        var dual=issue("CN=Wrong purpose",ca,false,true);
        var vertx=io.vertx.core.Vertx.vertx();var listener=new KillOperatorListener();
        listener.vertx=vertx;listener.codec=codec;
        try{
            listener.listen("127.0.0.1",0,pem(custody,"operator-server.pem",server.certificate(),false).toString(),
                pem(custody,"operator-server.key",server.keys().getPrivate(),true).toString(),pem(custody,"operator-ca.pem",ca.certificate(),false).toString(),
                tenant,approvals,kills);
            var uri=URI.create("https://127.0.0.1:"+listener.port()+"/break-glass/kills");
            var body=HttpResponse.BodyHandlers.ofString();
            try(var absent=client(ca,null);var foreign=client(ca,wrong);var client=client(ca,operator);var wrongPurpose=client(ca,dual)){
                assertThrows(java.io.IOException.class,()->absent.send(request(uri,document,id).header("Authorization","Bearer ignored")
                    .header("X-Forwarded-Client-Cert","forged").build(),body));
                assertThrows(java.io.IOException.class,()->foreign.send(request(uri,document,id).build(),body));
                assertEquals(401,wrongPurpose.send(request(uri,document,id).build(),body).statusCode());
                assertEquals(403,client.send(request(uri,document,id).header("Toolgate-Tenant-ID","20000000-0000-4000-8000-000000000001").build(),body).statusCode());
                assertEquals(404,client.send(request(URI.create(uri.toString().replace("kills","recoveries")),document,id).build(),body).statusCode());
                assertEquals(400,client.send(request(uri,document,id).header("Idempotency-Key","duplicate").build(),body).statusCode());
                assertEquals(413,client.send(request(uri,"x".repeat(131073),id).build(),body).statusCode());
                var response=client.send(request(uri,document,id).build(),body);
                assertEquals(201,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
                var event=codec.model(response.body(),io.ololabs.toolgate.contracts.v2.KillEvent.class);
                assertEquals(io.ololabs.toolgate.contracts.v2.KillIssuer.control_breakglass,event.issuer());
                assertEquals(409,client.send(request(uri,document,id).build(),body).statusCode());
            }
        }finally{
            listener.stop();vertx.close().toCompletionStage().toCompletableFuture().get();
            try(var files=Files.walk(custody)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
}
