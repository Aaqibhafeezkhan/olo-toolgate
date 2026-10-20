// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.contracts.v2.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.cert.X509Certificate;
import java.util.*;

/** Tenant-owned mounted PKCS#8 custody. Database rows contain public metadata and references only. */
public final class TenantIdentityCustody {
    @FunctionalInterface interface Reader { String read(String reference)throws Exception; }
    public record Reference(String purpose,String keyId,String state,String certificatePem) {}
    public record Bound(List<Store.IdentityKey> records,DeviceIssuer issuer,RsaTenantDiscovery signer,
                        DiscoveryKeySet discoveryKeys,List<DiscoveryDeviceAuthority> authorities,long generation) {
        public Bound { records=List.copyOf(records);authorities=List.copyOf(authorities); }
        public SignedTenantDiscovery discovery(Codec codec,TenantId tenant,Store.RuntimeProfile profile,String minimumVersion,long now){
            var active=authorities.stream().filter(ca->ca.state()==DiscoveryAuthorityState.ACTIVE).findFirst().orElseThrow(Failure::unavailable);
            var audiences=new LinkedHashSet<String>();audiences.add(profile.controlUrl());audiences.add(profile.gatewayUrl());
            return signer.sign(new TenantDiscovery(2L,tenant.value(),"urn:toolgate:tenant:"+tenant.value(),List.copyOf(audiences),profile.serverId(),profile.organization(),profile.controlUrl(),profile.gatewayUrl(),profile.controlUrl()+"/console/#enroll",minimumVersion,now,now+300000,generation,discoveryKeys,active.keyId(),authorities),now);
        }
    }
    private final Codec codec;
    private final Reader reader;
    private final List<java.math.BigInteger> forbidden;
    public TenantIdentityCustody(Codec codec,Path root,List<java.math.BigInteger> forbidden){
        this(codec,reference->{
            if(!root.isAbsolute()||!Files.isDirectory(root))throw new IllegalArgumentException();
            var base=root.toRealPath();var file=base.resolve(reference).normalize();
            if(!file.startsWith(base)||!file.toRealPath().startsWith(base))throw new IllegalArgumentException();
            return ProtectedCustody.privateText(file,16384);
        },forbidden);
    }
    TenantIdentityCustody(Codec codec,Reader reader,List<java.math.BigInteger> forbidden){this.codec=codec;this.reader=reader;this.forbidden=List.copyOf(forbidden);}
    private static String reference(TenantId tenant,String purpose,String keyId){return tenant.value()+"/"+purpose+"/"+keyId+".pk8.pem";}
    private RSAPrivateCrtKey privateKey(String reference){
        try(var parser=new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(reader.read(reference)))){
            if(!(parser.readObject() instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo info)||parser.readObject()!=null)throw Failure.unavailable();
            var key=(RSAPrivateCrtKey)new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().getPrivateKey(info);
            if(!Set.of(3072,4096).contains(key.getModulus().bitLength())||!key.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))||forbidden.contains(key.getModulus()))throw Failure.unavailable();
            return key;
        }catch(Exception rejected){throw Failure.unavailable();}
    }
    private static RSAPublicKey publicKey(RSAPrivateCrtKey privateKey){
        try{return (RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.RSAPublicKeySpec(privateKey.getModulus(),privateKey.getPublicExponent()));}
        catch(GeneralSecurityException invalid){throw Failure.unavailable();}
    }
    private static X509Certificate certificate(String pem){
        try(var parser=new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(pem))){
            if(!(parser.readObject() instanceof org.bouncycastle.cert.X509CertificateHolder holder)||parser.readObject()!=null)throw Failure.validation();
            var text=new java.io.StringWriter();try(var writer=new org.bouncycastle.openssl.jcajce.JcaPEMWriter(text)){writer.writeObject(holder);}
            // Do not persist comments, extra blocks or accidentally concatenated private material.
            if(!pem.replace("\r\n","\n").strip().equals(text.toString().replace("\r\n","\n").strip()))throw Failure.validation();
            return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder);
        }catch(Exception invalid){throw Failure.validation();}
    }
    /** Preflight reads custody before metadata mutations; failed validation cannot partially register keys. */
    public List<Store.IdentityKey> prepare(TenantId tenant,Store.RuntimeProfile profile,long generation,List<Reference> references,long now){
        if(references==null||references.size()<2||references.size()>6)throw Failure.validation();
        var rows=new ArrayList<Store.IdentityKey>();var moduli=new HashSet<java.math.BigInteger>();
        for(var reference:references){
            // Validate IDs and states before using either in a path.
            new Store.IdentityKey(reference.purpose(),reference.keyId(),reference.state(),"pending","", "0".repeat(64),generation);
            if(reference.state().equals("RETIRED"))throw Failure.validation();
            var path=reference(tenant,reference.purpose(),reference.keyId());var key=privateKey(path);var publicKey=publicKey(key);
            if(!moduli.add(publicKey.getModulus()))throw Failure.validation();
            String material;
            if(reference.purpose().equals("DISCOVERY")){
                if(reference.certificatePem()!=null)throw Failure.validation();material=codec.json(RsaTenantDiscovery.publicKey(reference.keyId(),publicKey));
            }else{
                var ca=certificate(reference.certificatePem());
                try{if(!reference.keyId().equals(DirectoryService.digest(ca.getEncoded()))||!publicKey.equals(ca.getPublicKey()))throw Failure.validation();}
                catch(java.security.cert.CertificateEncodingException invalid){throw Failure.validation();}
                material=reference.certificatePem();
            }
            rows.add(new Store.IdentityKey(reference.purpose(),reference.keyId(),reference.state(),path,material,DirectoryService.digest(publicKey.getEncoded()),generation));
        }
        // Exercises all semantic checks (CA identity/purpose, signer/profile and public ring) before admission.
        bind(tenant,profile,rows,now);
        return List.copyOf(rows);
    }
    /** Resolve only this tenant's authoritative records; no shared/default signer or missing-key generation. */
    public Bound bind(TenantId tenant,Store.RuntimeProfile profile,List<Store.IdentityKey> records,long now){
        if(profile==null||records==null||records.size()<2||records.size()>4096)throw Failure.unavailable();
        var discovery=new ArrayList<DiscoveryPublicKey>();var authorities=new ArrayList<DiscoveryDeviceAuthority>();
        var fingerprints=new HashSet<String>();var ids=new HashSet<String>();var moduli=new HashSet<java.math.BigInteger>();
        Store.IdentityKey signer=null,issuer=null;long generation=0;
        for(var row:records){
            if(!row.providerReference().equals(reference(tenant,row.purpose(),row.keyId()))||!fingerprints.add(row.fingerprint())||!ids.add(row.purpose()+"/"+row.keyId()))throw Failure.unavailable();
            if(generation==0)generation=row.generation();else if(generation!=row.generation())throw Failure.unavailable();
            if(row.state().equals("RETIRED"))continue;
            RSAPublicKey key;
            if(row.purpose().equals("DISCOVERY")){
                var jwk=codec.model(row.publicMaterial(),DiscoveryPublicKey.class);if(!jwk.kid().equals(row.keyId()))throw Failure.unavailable();
                try{key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.RSAPublicKeySpec(new java.math.BigInteger(1,Base64.getUrlDecoder().decode(jwk.n())),java.math.BigInteger.valueOf(65537)));}
                catch(GeneralSecurityException|IllegalArgumentException invalid){throw Failure.unavailable();}
                discovery.add(jwk);if(row.state().equals("ACTIVE")){if(signer!=null)throw Failure.unavailable();signer=row;}
            }else{
                var ca=certificate(row.publicMaterial());if(!(ca.getPublicKey() instanceof RSAPublicKey rsa))throw Failure.unavailable();key=rsa;
                authorities.add(new DiscoveryDeviceAuthority(row.keyId(),DiscoveryAuthorityState.valueOf(row.state()),row.publicMaterial()));
                if(row.state().equals("ACTIVE")){if(issuer!=null)throw Failure.unavailable();issuer=row;}
            }
            if(!DirectoryService.digest(key.getEncoded()).equals(row.fingerprint())||!moduli.add(key.getModulus())||forbidden.contains(key.getModulus()))throw Failure.unavailable();
        }
        if(signer==null||issuer==null||discovery.size()>4||authorities.size()>2)throw Failure.unavailable();
        var signingKey=privateKey(signer.providerReference());
        if(!DirectoryService.digest(publicKey(signingKey).getEncoded()).equals(signer.fingerprint()))throw Failure.unavailable();
        var deviceKey=privateKey(issuer.providerReference());
        if(!DirectoryService.digest(publicKey(deviceKey).getEncoded()).equals(issuer.fingerprint()))throw Failure.unavailable();
        var encoded=new java.io.StringWriter();
        try(var writer=new org.bouncycastle.util.io.pem.PemWriter(encoded)){writer.writeObject(new org.bouncycastle.util.io.pem.PemObject("PRIVATE KEY",deviceKey.getEncoded()));}
        catch(java.io.IOException unavailable){throw Failure.unavailable();}
        String keyPem=encoded.toString();
        var issuing=new X509DeviceIssuer(keyPem,issuer.publicMaterial(),now);
        var scoped=new TenantDeviceIssuer(tenant,profile.serverId(),issuing,authorities.stream().map(ca->certificate(ca.certificatePem())).toList());
        var bound=new Bound(records,scoped,new RsaTenantDiscovery(codec,tenant,profile,signingKey,signer.keyId(),forbidden),new DiscoveryKeySet(discovery),authorities,generation);
        var proof=bound.discovery(codec,tenant,profile,"0.0.0",now);
        RsaTenantDiscovery.verify(codec,proof,profile.controlUrl(),tenant,null,now);return bound;
    }
}
