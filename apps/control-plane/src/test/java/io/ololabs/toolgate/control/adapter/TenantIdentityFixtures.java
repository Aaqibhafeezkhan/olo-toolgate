// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Store;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.security.*;
import java.util.*;

/** In-memory test custody; no private fixture material is written to the repository. */
public final class TenantIdentityFixtures {
    public final TenantId tenant;
    public final TenantIdentityCustody custody;
    public final Store.RuntimeProfile profile;
    public final List<TenantIdentityCustody.Reference> references;
    private final Map<String,String> material=new HashMap<>();
    public TenantIdentityFixtures(ContractCodec codec,TenantId tenant,Store.RuntimeProfile profile)throws Exception{
        this.tenant=tenant;this.profile=profile;
        custody=new TenantIdentityCustody(codec,reference->{var pem=material.get(reference);if(pem==null)throw new IllegalArgumentException();return pem;},List.of());
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);
        var ca=addAuthority();put("DISCOVERY","discovery-a",generator.generateKeyPair().getPrivate());put("DISCOVERY","discovery-b",generator.generateKeyPair().getPrivate());
        references=List.of(new TenantIdentityCustody.Reference("DISCOVERY","discovery-a","ACTIVE",null),ca);
    }
    public TenantIdentityCustody.Reference addAuthority()throws Exception{
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);var ca=generator.generateKeyPair();
        var name=new org.bouncycastle.asn1.x500.X500Name("CN=Tenant device authority,OU="+tenant.value());
        long now=System.currentTimeMillis();var builder=new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(name,new java.math.BigInteger(128,new SecureRandom()),new Date(now-60000),new Date(now+86400000),name,ca.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints,true,new org.bouncycastle.asn1.x509.BasicConstraints(0));
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage,true,new org.bouncycastle.asn1.x509.KeyUsage(org.bouncycastle.asn1.x509.KeyUsage.keyCertSign|org.bouncycastle.asn1.x509.KeyUsage.cRLSign));
        var certificate=builder.build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(ca.getPrivate()));
        var text=new java.io.StringWriter();try(var writer=new org.bouncycastle.openssl.jcajce.JcaPEMWriter(text)){writer.writeObject(certificate);}
        String caId=io.ololabs.toolgate.control.application.DirectoryService.digest(certificate.getEncoded());
        put("DEVICE_CA",caId,ca.getPrivate());return new TenantIdentityCustody.Reference("DEVICE_CA",caId,"ACTIVE",text.toString());
    }
    private String path(String purpose,String id){return tenant.value()+"/"+purpose+"/"+id+".pk8.pem";}
    private void put(String purpose,String id,PrivateKey key)throws java.io.IOException{var text=new java.io.StringWriter();try(var writer=new org.bouncycastle.util.io.pem.PemWriter(text)){writer.writeObject(new org.bouncycastle.util.io.pem.PemObject("PRIVATE KEY",key.getEncoded()));}material.put(path(purpose,id),text.toString());}
    public void reuseDiscoveryFrom(TenantIdentityFixtures other){material.put(path("DISCOVERY","discovery-a"),other.material.get(other.path("DISCOVERY","discovery-a")));}
    public void forget(String purpose,String id){material.remove(path(purpose,id));}
    public List<TenantIdentityCustody.Reference> announced(){var refs=new ArrayList<>(references);refs.add(new TenantIdentityCustody.Reference("DISCOVERY","discovery-b","READ_ONLY",null));return List.copyOf(refs);}
    public List<TenantIdentityCustody.Reference> rotated(){return announced().stream().map(ref->ref.purpose().equals("DISCOVERY")?new TenantIdentityCustody.Reference(ref.purpose(),ref.keyId(),ref.keyId().equals("discovery-b")?"ACTIVE":"READ_ONLY",null):ref).toList();}
    public List<TenantIdentityCustody.Reference> retired(){return rotated().stream().filter(ref->!ref.keyId().equals("discovery-a")).toList();}
}
