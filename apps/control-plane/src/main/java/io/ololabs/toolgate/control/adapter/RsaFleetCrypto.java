// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.RSAPublicKeySpec;
import java.util.*;

/** RFC7515 RS256, strict protected headers, canonical base64url and disjoint trust domains. */
public final class RsaFleetCrypto implements FleetCrypto,KillCrypto {
    private static final String RELEASE="toolgate-package-release+jws", DESIRED="toolgate-fleet-desired+jws", GRANT="toolgate-fleet-artifact+jws";
    private final Codec codec;
    private final RSAPrivateCrtKey signing;
    private final String keyId;
    private final Map<String,RSAPublicKey> releases,organizations;
    public RsaFleetCrypto(Codec codec,RSAPrivateCrtKey signing,String keyId,List<FleetTrustKey> releases,List<FleetTrustKey> organizations,List<java.math.BigInteger> forbiddenModuli){
        this.codec=codec;this.signing=signing;this.keyId=io.ololabs.toolgate.control.domain.Ids.valid(keyId);
        this.releases=keys(releases);this.organizations=keys(organizations);
        var moduli=new HashSet<java.math.BigInteger>();
        for(var key:this.releases.values())if(!moduli.add(key.getModulus())||forbiddenModuli.contains(key.getModulus()))throw new IllegalArgumentException("Separate fleet key domains required");
        for(var key:this.organizations.values())if(!moduli.add(key.getModulus())||forbiddenModuli.contains(key.getModulus()))throw new IllegalArgumentException("Separate fleet key domains required");
        var publicKey=this.organizations.get(keyId);
        if(publicKey==null||!publicKey.getModulus().equals(signing.getModulus())||!signing.getPublicExponent().equals(java.math.BigInteger.valueOf(65537)))throw new IllegalArgumentException("Organization signer must match its trust key");
    }
    private Map<String,RSAPublicKey> keys(List<FleetTrustKey> entries){
        if(entries.isEmpty()||entries.size()>4)throw new IllegalArgumentException("Bounded explicit keyring required");
        var keys=new HashMap<String,RSAPublicKey>();
        try{for(var entry:entries){codec.model(codec.json(entry),FleetTrustKey.class);
            var n=new java.math.BigInteger(1,decode(entry.n()));
            if(!Set.of(2048,3072,4096).contains(n.bitLength())||!entry.e().equals("AQAB")||keys.containsKey(entry.kid()))throw new IllegalArgumentException("Invalid fleet public key");
            keys.put(entry.kid(),(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,java.math.BigInteger.valueOf(65537))));
        }}catch(GeneralSecurityException failure){throw new IllegalArgumentException("Invalid fleet public key");}
        return Map.copyOf(keys);
    }
    private static byte[] decode(String value){try{var bytes=Base64.getUrlDecoder().decode(value);if(!encode(bytes).equals(value))throw Failure.validation();return bytes;}catch(IllegalArgumentException failure){throw Failure.validation();}}
    private static String encode(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private <T> Verified<T> verify(FleetSignedDocument signed,String type,Class<T> model,Map<String,RSAPublicKey> keys){
        codec.model(codec.json(signed),FleetSignedDocument.class);
        var parts=signed.jws().split("\\.",-1);if(parts.length!=3||parts[0].length()>1024||parts[1].length()>87384)throw Failure.validation();
        var header=codec.model(new String(decode(parts[0]),StandardCharsets.UTF_8),FleetSignatureHeader.class);
        var key=keys.get(header.kid());if(key==null||!type.equals(header.typ()))throw unauthorized();
        byte[] bytes=decode(parts[1]),signature=decode(parts[2]);if(bytes.length>65536||signature.length!=key.getModulus().bitLength()/8)throw unauthorized();
        try{var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));if(!verifier.verify(signature))throw unauthorized();}
        catch(GeneralSecurityException failure){throw unauthorized();}
        // Strict UTF-8 conversion does not permit replacement characters to alter signed semantics.
        String document;
        try{document=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException failure){throw Failure.validation();}
        return new Verified<>(codec.model(document,model),bytes);
    }
    private static Failure unauthorized(){return new Failure(ErrorCode.FORBIDDEN,403,"Fleet signature rejected");}
    private FleetSignedDocument sign(Object document,String type){
        var header=codec.json(new FleetSignatureHeader("RS256",type,keyId));
        var message=encode(header.getBytes(StandardCharsets.UTF_8))+"."+encode(codec.json(document).getBytes(StandardCharsets.UTF_8));
        try{var signature=Signature.getInstance("SHA256withRSA");signature.initSign(signing);signature.update(message.getBytes(StandardCharsets.US_ASCII));return new FleetSignedDocument(message+"."+encode(signature.sign()));}
        catch(GeneralSecurityException failure){throw Failure.unavailable();}
    }
    public Verified<FleetPackageDocument> release(FleetSignedDocument signed){var verified=verify(signed,RELEASE,FleetPackageDocument.class,releases);if(verified.bytes().length>32768)throw Failure.validation();return verified;}
    public FleetArtifactGrantClaims grant(FleetSignedDocument signed){return verify(signed,GRANT,FleetArtifactGrantClaims.class,organizations).model();}
    public FleetSignedDocument desired(FleetDesiredDocument document){codec.model(codec.json(document),FleetDesiredDocument.class);return sign(document,DESIRED);}
    public FleetSignedDocument grant(FleetArtifactGrantClaims document){codec.model(codec.json(document),FleetArtifactGrantClaims.class);return sign(document,GRANT);}
    public java.util.List<io.ololabs.toolgate.contracts.v2.KillTrustKey> publicKeys(){
        return organizations.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry->{
            byte[] bytes=entry.getValue().getModulus().toByteArray();if(bytes[0]==0)bytes=Arrays.copyOfRange(bytes,1,bytes.length);
            return new io.ololabs.toolgate.contracts.v2.KillTrustKey(entry.getKey(),encode(bytes),"AQAB");
        }).toList();
    }
    private static final String KILL_EVENT="toolgate-kill-event+jws",KILL_PUSH="toolgate-kill-push+jws";
    public String signEvent(com.fasterxml.jackson.databind.node.ObjectNode unsigned){return signKill(unsigned,KILL_EVENT,24000);}
    public String signPush(com.fasterxml.jackson.databind.node.ObjectNode unsigned){return signKill(unsigned,KILL_PUSH,1500000);}
    public com.fasterxml.jackson.databind.node.ObjectNode verifyEvent(String signature){return verifyKill(signature,KILL_EVENT,32768);}
    public com.fasterxml.jackson.databind.node.ObjectNode verifyPush(String signature){return verifyKill(signature,KILL_PUSH,2097152);}
    private String signKill(com.fasterxml.jackson.databind.node.ObjectNode unsigned,String type,int limit){
        if(unsigned.has("signature"))throw Failure.validation();
        byte[] bytes=Canonical.jcs(unsigned);if(bytes.length>limit)throw Failure.validation();
        String header=encode(Canonical.jcs((com.fasterxml.jackson.databind.JsonNode)codec.value(
            codec.json(Map.of("alg","RS256","typ",type,"kid",keyId)))));
        String message=header+"."+encode(bytes);
        try{var signature=Signature.getInstance("SHA256withRSA");signature.initSign(signing);
            signature.update(message.getBytes(StandardCharsets.US_ASCII));return message+"."+encode(signature.sign());
        }catch(GeneralSecurityException failure){throw Failure.unavailable();}
    }
    private com.fasterxml.jackson.databind.node.ObjectNode verifyKill(String signed,String type,int limit){
        if(signed==null||signed.length()>limit)throw unauthorized();
        String[] parts=signed.split("\\.",-1);
        if(parts.length!=3||parts[0].length()>1024)throw unauthorized();
        var header=(com.fasterxml.jackson.databind.JsonNode)codec.value(new String(decode(parts[0]),StandardCharsets.UTF_8));
        if(!header.isObject()||header.size()!=3||!"RS256".equals(header.path("alg").asText())
            ||!type.equals(header.path("typ").asText()))throw unauthorized();
        var key=organizations.get(header.path("kid").asText());if(key==null)throw unauthorized();
        byte[] signature=decode(parts[2]),payload=decode(parts[1]);
        if(signature.length!=key.getModulus().bitLength()/8)throw unauthorized();
        try{
            var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);
            verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
            if(!verifier.verify(signature))throw unauthorized();
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(payload)).toString();
            var node=(com.fasterxml.jackson.databind.JsonNode)codec.value(text);
            if(!node.isObject()||node.has("signature")||!MessageDigest.isEqual(payload,Canonical.jcs(node)))throw unauthorized();
            return (com.fasterxml.jackson.databind.node.ObjectNode)node;
        }catch(GeneralSecurityException|java.nio.charset.CharacterCodingException failure){throw unauthorized();}
    }

}
