// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Fixed algorithm, fixed type, pinned verification key; no attacker-selected key URLs or algorithms. */
public final class RsaEffectSigner implements EffectSigner {
    private final RSAPrivateCrtKey key;private final PublicKey publicKey;private final ContractCodec codec;private final String header,invocationHeader,scanHeader,serviceHeader,keyId;
    public RsaEffectSigner(RSAPrivateCrtKey key,String keyId,ContractCodec codec){
        if(!java.util.Set.of(2048,3072,4096).contains(key.getModulus().bitLength())||!key.getPublicExponent().equals(java.math.BigInteger.valueOf(65537)))throw new IllegalArgumentException("Invalid permit key");
        this.keyId=keyId;this.key=key;this.codec=codec;header=encode(codec.json(new EnterprisePermitHeader("RS256","toolgate-effect-permit+jws",Ids.valid(keyId))));
        invocationHeader=encode(codec.json(new io.ololabs.toolgate.contracts.v2.PermitHeader("RS256","toolgate-invocation-permit+jws",keyId)));
        var scan=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("alg","RS256").put("typ","toolgate-mcp-scan-permit+jws").put("kid",keyId);
        scanHeader=encode(scan.toString());
        serviceHeader=encode(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("alg","RS256").put("typ","olo-service-permit+jws").put("kid",keyId).toString());
        try{publicKey=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(key.getModulus(),key.getPublicExponent()));}catch(GeneralSecurityException invalid){throw new IllegalArgumentException("Invalid permit key",invalid);}
    }
    public ServicePermit signService(ServicePermitClaims claims){String payload=codec.json(claims);codec.model(payload,ServicePermitClaims.class);return new ServicePermit(signed(serviceHeader,payload));}
    public ServicePermitClaims verifyService(ServicePermit permit){return codec.model(verified(serviceHeader,permit.jws()),ServicePermitClaims.class);}
    public java.util.Map<String,Object> servicePublicKey(){return java.util.Map.of("kty","RSA","alg","RS256","use","sig","kid",keyId,"n",unsigned(key.getModulus()),"e",unsigned(key.getPublicExponent()));}
    private static String unsigned(java.math.BigInteger v){byte[] b=v.toByteArray();if(b[0]==0)b=java.util.Arrays.copyOfRange(b,1,b.length);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public EnterpriseSignedPermit sign(EnterprisePermitClaims claims){String payload=codec.json(claims);codec.model(payload,EnterprisePermitClaims.class);return new EnterpriseSignedPermit(signed(header,payload));}
    public EnterprisePermitClaims verify(EnterpriseSignedPermit permit){return codec.model(verified(header,permit.jws()),EnterprisePermitClaims.class);}
    public io.ololabs.toolgate.contracts.v2.SignedInvocationPermit signInvocation(io.ololabs.toolgate.contracts.v2.InvocationPermitClaims claims){String payload=codec.json(claims);codec.model(payload,io.ololabs.toolgate.contracts.v2.InvocationPermitClaims.class);return new io.ololabs.toolgate.contracts.v2.SignedInvocationPermit(signed(invocationHeader,payload));}
    public io.ololabs.toolgate.contracts.v2.InvocationPermitClaims verifyInvocation(io.ololabs.toolgate.contracts.v2.SignedInvocationPermit permit){return codec.model(verified(invocationHeader,permit.jws()),io.ololabs.toolgate.contracts.v2.InvocationPermitClaims.class);}
    public String signScan(io.ololabs.toolgate.contracts.v2.McpScanClaims claims){String payload=codec.json(claims);codec.model(payload,io.ololabs.toolgate.contracts.v2.McpScanClaims.class);return signed(scanHeader,payload);}
    public io.ololabs.toolgate.contracts.v2.McpScanClaims verifyScan(String jws){return codec.model(verified(scanHeader,jws),io.ololabs.toolgate.contracts.v2.McpScanClaims.class);}
    private String signed(String header,String payload){String message=header+"."+encode(payload);
        try{var signature=Signature.getInstance("SHA256withRSA");signature.initSign(key);signature.update(message.getBytes(StandardCharsets.US_ASCII));return message+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());}catch(GeneralSecurityException failure){throw Failure.unavailable();}
    }
    /** The payload of a permit with exactly this header and a valid signature. */
    private String verified(String header,String jws){
        try{if(jws.length()>32768||!jws.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw ManagementAccess.denied();String[] parts=jws.split("\\.");if(!parts[0].equals(header))throw ManagementAccess.denied();
            var signature=Signature.getInstance("SHA256withRSA");signature.initVerify(publicKey);signature.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));if(!signature.verify(Base64.getUrlDecoder().decode(parts[2])))throw ManagementAccess.denied();
            return new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8);
        }catch(GeneralSecurityException|IllegalArgumentException invalid){throw ManagementAccess.denied();}
    }
    private static String encode(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
}
