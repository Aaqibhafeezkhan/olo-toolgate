// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.contracts.v2.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.RSAPublicKeySpec;
import java.util.*;

/** Compact RFC 7515 JWS. This helper does not enable v2 serving or load deployment/device-CA signing keys. */
public final class RsaTenantDiscovery {
    public record Trust(TenantId tenant,long version,DiscoveryKeySet keys,long issuedAt) {}
    public record Verified(TenantDiscovery manifest,Trust trust) {}
    private final Codec codec;
    private final TenantId tenant;
    private final RSAPrivateCrtKey signer;
    private final String keyId;
    private final Store.RuntimeProfile profile;
    public RsaTenantDiscovery(Codec codec,TenantId tenant,Store.RuntimeProfile profile,RSAPrivateCrtKey signer,String keyId,List<java.math.BigInteger> forbiddenModuli){
        this.codec=codec;this.tenant=Objects.requireNonNull(tenant);this.profile=Objects.requireNonNull(profile);this.signer=Objects.requireNonNull(signer);this.keyId=keyId;
        codec.model(codec.json(new DiscoveryHeader("RS256","toolgate-discovery+jws",keyId)),DiscoveryHeader.class);
        if(!Set.of(3072,4096).contains(signer.getModulus().bitLength())||!signer.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))||forbiddenModuli.contains(signer.getModulus()))throw new IllegalArgumentException("Dedicated tenant discovery key required");
    }
    public static DiscoveryPublicKey publicKey(String id,RSAPublicKey key){
        byte[] n=key.getModulus().toByteArray();if(n[0]==0)n=Arrays.copyOfRange(n,1,n.length);
        return new DiscoveryPublicKey("RSA","sig","RS256",id,encode(n),encode(unsigned(key.getPublicExponent())));
    }
    private static byte[] unsigned(java.math.BigInteger value){var bytes=value.toByteArray();return bytes[0]==0?Arrays.copyOfRange(bytes,1,bytes.length):bytes;}
    private static String encode(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static byte[] decode(String value){try{var bytes=Base64.getUrlDecoder().decode(value);if(!encode(bytes).equals(value))throw Failure.validation();return bytes;}catch(IllegalArgumentException invalid){throw Failure.validation();}}
    private static String text(byte[] bytes){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException invalid){throw Failure.validation();}}
    private static Failure denied(){return new Failure(ErrorCode.UNAUTHORIZED,401,"Discovery signature rejected");}
    private static Map<String,RSAPublicKey> keys(Codec codec,DiscoveryKeySet set){
        codec.model(codec.json(set),DiscoveryKeySet.class);var result=new HashMap<String,RSAPublicKey>();var moduli=new HashSet<java.math.BigInteger>();
        try{for(var entry:set.keys()){
            var bytes=decode(entry.n());var n=new java.math.BigInteger(1,bytes);
            if(!Set.of(3072,4096).contains(n.bitLength())||bytes.length!=n.bitLength()/8||!entry.e().equals("AQAB")||!moduli.add(n)||result.containsKey(entry.kid()))throw Failure.validation();
            result.put(entry.kid(),(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,java.math.BigInteger.valueOf(65537))));
        }}catch(GeneralSecurityException invalid){throw Failure.validation();}return Map.copyOf(result);
    }
    /** Only an independently registered tenant key may sign that tenant's manifest. */
    public SignedTenantDiscovery sign(TenantDiscovery value,long now){
        validate(codec,value,now);if(!tenant.value().equals(value.tenantId()))throw denied();
        var approved=new HashSet<TenantRequestResolver.Authority>();approved.add(authority(profile.controlUrl()));approved.add(authority(profile.gatewayUrl()));
        if(!value.serverId().equals(profile.serverId())||!value.organization().equals(profile.organization())||!sameOrigin(value.controlUrl(),profile.controlUrl())
            ||!sameOrigin(value.gatewayUrl(),profile.gatewayUrl())||!audiences(value).equals(approved))throw denied();
        var key=keys(codec,value.discoveryKeys()).get(keyId);
        if(key==null||!key.getModulus().equals(signer.getModulus()))throw denied();
        String header=encode(codec.json(new DiscoveryHeader("RS256","toolgate-discovery+jws",keyId)).getBytes(StandardCharsets.UTF_8));
        byte[] bytes=codec.json(value).getBytes(StandardCharsets.UTF_8);if(bytes.length>65536)throw Failure.validation();String payload=encode(bytes);
        try{var signature=Signature.getInstance("SHA256withRSA");signature.initSign(signer);signature.update((header+"."+payload).getBytes(StandardCharsets.US_ASCII));return new SignedTenantDiscovery(header+"."+payload+"."+encode(signature.sign()));}
        catch(GeneralSecurityException unavailable){throw Failure.unavailable();}
    }
    /** Initial bootstrap requires independently authenticated HTTPS; subsequent trust must continue from stored keys. */
    public static Verified verify(Codec codec,SignedTenantDiscovery envelope,String origin,TenantId expected,Trust pinned,long now){
        codec.model(codec.json(envelope),SignedTenantDiscovery.class);var parts=envelope.jws().split("\\.",-1);
        if(parts.length!=3||parts[0].length()>1024||parts[1].length()>87384)throw Failure.validation();
        var header=codec.model(text(decode(parts[0])),DiscoveryHeader.class);var bytes=decode(parts[1]);if(bytes.length>65536)throw Failure.validation();
        var value=codec.model(text(bytes),TenantDiscovery.class);var id=new TenantId(value.tenantId());
        if(expected!=null&&!expected.equals(id)||pinned!=null&&!pinned.tenant().equals(id))throw new Failure(ErrorCode.TENANT_MISMATCH,403,"Tenant identity mismatch");
        if(!audiences(value).contains(authority(origin)))throw denied();
        var advertised=keys(codec,value.discoveryKeys());var trusted=pinned==null?advertised:keys(codec,pinned.keys());var key=trusted.get(header.kid());
        if(key==null||!key.equals(advertised.get(header.kid())))throw denied();
        if(pinned!=null&&(value.keySetVersion()<pinned.version()||value.issuedAtUnixMs()<pinned.issuedAt()||value.keySetVersion()==pinned.version()&&!advertised.equals(trusted)))throw denied();
        var signature=decode(parts[2]);if(signature.length!=key.getModulus().bitLength()/8)throw denied();
        try{var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));if(!verifier.verify(signature))throw denied();}
        catch(GeneralSecurityException invalid){throw denied();}
        validate(codec,value,now);return new Verified(value,new Trust(id,value.keySetVersion(),value.discoveryKeys(),value.issuedAtUnixMs()));
    }
    private static void validate(Codec codec,TenantDiscovery value,long now){
        codec.model(codec.json(value),TenantDiscovery.class);new TenantId(value.tenantId());
        String control=EndpointService.origin(value.controlUrl());EndpointService.origin(value.gatewayUrl());
        var audiences=audiences(value);
        if(!value.issuer().equals("urn:toolgate:tenant:"+value.tenantId())||!audiences.contains(authority(control))||!audiences.contains(authority(value.gatewayUrl()))
            ||!value.verificationUri().equals(control+"/console/#enroll")||value.issuedAtUnixMs()>now||value.expiresAtUnixMs()<=now||value.expiresAtUnixMs()-value.issuedAtUnixMs()>300000)throw denied();
        var moduli=new HashSet<java.math.BigInteger>();keys(codec,value.discoveryKeys()).values().forEach(key->moduli.add(key.getModulus()));
        var authorities=new HashSet<String>();int active=0;
        try{for(var authority:value.deviceAuthorities()){
            var factory=java.security.cert.CertificateFactory.getInstance("X.509");var input=new java.io.ByteArrayInputStream(authority.certificatePem().getBytes(StandardCharsets.US_ASCII));
            var certificates=factory.generateCertificates(input);if(certificates.size()!=1)throw denied();
            var ca=(java.security.cert.X509Certificate)certificates.iterator().next();ca.checkValidity(new Date(now));ca.verify(ca.getPublicKey());
            var subject=org.bouncycastle.asn1.x500.X500Name.getInstance(ca.getSubjectX500Principal().getEncoded());var units=subject.getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.OU);
            if(units.length!=1||units[0].size()!=1||!org.bouncycastle.asn1.x500.style.IETFUtils.valueToString(units[0].getFirst().getValue()).equals(value.tenantId())
                ||!ca.getIssuerX500Principal().equals(ca.getSubjectX500Principal())||!ca.getSigAlgOID().equals("1.2.840.113549.1.1.11")||ca.getBasicConstraints()!=0||ca.getKeyUsage()==null||!ca.getKeyUsage()[5]
                ||!(ca.getPublicKey() instanceof RSAPublicKey publicKey)||!Set.of(3072,4096).contains(publicKey.getModulus().bitLength())||!publicKey.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))
                ||!moduli.add(publicKey.getModulus())||!DirectoryService.digest(ca.getEncoded()).equals(authority.keyId())||!authorities.add(authority.keyId()))throw denied();
            if(authority.state()==DiscoveryAuthorityState.ACTIVE){active++;if(!authority.keyId().equals(value.deviceIssuerKeyId()))throw denied();}
        }}catch(GeneralSecurityException invalid){throw denied();}
        if(active!=1)throw denied();
    }
    private static boolean sameOrigin(String first,String second){
        return authority(first).equals(authority(second));
    }
    private static TenantRequestResolver.Authority authority(String origin){return TenantRequestResolver.authority(List.of(java.net.URI.create(EndpointService.origin(origin)).getRawAuthority()),443);}
    private static Set<TenantRequestResolver.Authority> audiences(TenantDiscovery value){
        var result=new HashSet<TenantRequestResolver.Authority>();for(var origin:value.audiences())if(!result.add(authority(origin)))throw Failure.validation();return Set.copyOf(result);
    }
}
