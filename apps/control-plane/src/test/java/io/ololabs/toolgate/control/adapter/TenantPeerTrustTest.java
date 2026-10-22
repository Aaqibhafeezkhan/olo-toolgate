// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.contracts.ErrorCode;
import java.security.*;
import java.security.cert.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TenantPeerTrustTest {
    private X509Certificate certificate(String pem)throws Exception{return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));}
    private TenantIdentityCustody.Bound bound(ContractCodec codec,String tenant)throws Exception{
        var id=new TenantId(tenant);var profile=new Store.RuntimeProfile("server","Tenant","https://tenant.example.test","https://tenant.example.test");
        var fixture=new TenantIdentityFixtures(codec,id,profile);long now=System.currentTimeMillis();
        return fixture.custody.bind(id,profile,fixture.custody.prepare(id,profile,1,fixture.references,now),now);
    }
    @Test void verifiedForeignTenantIsForbiddenAndListenerRootsCannotReviveRetiredOwnCa()throws Exception{
        var codec=new ContractCodec();var a=new TenantId("10000000-0000-4000-8000-000000000001");var b=new TenantId("10000000-0000-4000-8000-000000000002");
        var first=bound(codec,a.value());var other=bound(codec,b.value());var replacement=bound(codec,a.value());
        var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));var key=generator.generateKeyPair();
        var request=new org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(new org.bouncycastle.asn1.x500.X500Name("CN=device"),key.getPublic()).build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(key.getPrivate()));
        var text=new java.io.StringWriter();try(var writer=new org.bouncycastle.openssl.jcajce.JcaPEMWriter(text)){writer.writeObject(request);}String csr=text.toString();long now=System.currentTimeMillis();
        var own=certificate(first.issuer().issue(csr,"device",a.value(),"user","server",now).certificatePem());
        var foreign=certificate(other.issuer().issue(csr,"device",b.value(),"user","server",now).certificatePem());
        var roots=List.of(certificate(first.issuer().issuerCertificate()),certificate(other.issuer().issuerCertificate()));
        var trusted=new TenantPeerTrust(first.issuer(),a,roots);
        assertEquals(a,trusted.verifyPeer(own,now).tenant());
        assertEquals(ErrorCode.TENANT_MISMATCH,assertThrows(Failure.class,()->trusted.verifyPeer(foreign,now)).code());
        assertEquals(401,assertThrows(Failure.class,()->new TenantPeerTrust(replacement.issuer(),a,roots).verifyPeer(own,now)).status());
        assertEquals(401,assertThrows(Failure.class,()->new TenantPeerTrust(first.issuer(),a,List.of()).verifyPeer(foreign,now)).status());
    }
}
