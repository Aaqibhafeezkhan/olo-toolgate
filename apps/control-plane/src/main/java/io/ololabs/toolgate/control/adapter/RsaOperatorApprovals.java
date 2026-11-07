// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.*;

/** Exactly three external public-only RSA keys, used only for offline emergency kill approvals. */
public final class RsaOperatorApprovals implements OperatorApprovals {
    private final Codec codec;
    private final Map<String,RSAPublicKey> keys;
    public RsaOperatorApprovals(Codec codec,List<KillTrustKey> entries,List<BigInteger> forbiddenModuli) {
        this.codec=codec;
        if(entries.size()!=3)throw new IllegalArgumentException("Exactly three distinct operator keys required");
        var keys=new HashMap<String,RSAPublicKey>();var moduli=new HashSet<BigInteger>();
        try {
            for(var entry:entries) {
                codec.model(codec.json(entry),KillTrustKey.class);
                byte[] encoded=decode(entry.n());var modulus=new BigInteger(1,encoded);
                if(encoded.length==0||encoded[0]==0||!Set.of(2048,3072,4096).contains(modulus.bitLength())
                    ||!entry.e().equals("AQAB")||keys.containsKey(entry.kid())
                    ||!moduli.add(modulus)||forbiddenModuli.contains(modulus))
                    throw new IllegalArgumentException("Disjoint strong operator keys required");
                Ids.valid(entry.kid());
                keys.put(entry.kid(),(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(modulus,BigInteger.valueOf(65537))));
            }
        } catch(GeneralSecurityException|Failure invalid) {throw new IllegalArgumentException("Invalid operator keyring");}
        this.keys=Map.copyOf(keys);
    }
    private static byte[] decode(String value) {
        try {
            var bytes=Base64.getUrlDecoder().decode(value);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))throw Failure.validation();
            return bytes;
        } catch(IllegalArgumentException invalid) {throw Failure.validation();}
    }
    @Override public Approved verify(Ids.TenantId tenant,String requestId,BreakGlassRequest request,long now) {
        Ids.valid(requestId);codec.model(codec.json(request),BreakGlassRequest.class);KillPolicy.validate(request.scope());
        if(now<0)throw Failure.validation();
        var operators=new TreeSet<String>();JsonNode shared=null;BreakGlassApprovalClaims claims=null;
        for(String signature:request.operatorSignatures()) {
            if(signature.length()>32768)throw Failure.validation();
            var parts=signature.split("\\.",-1);
            if(parts.length!=3)throw Failure.validation();
            var header=(JsonNode)codec.value(new String(decode(parts[0]),StandardCharsets.UTF_8));
            var fields=new HashSet<String>();header.fieldNames().forEachRemaining(fields::add);
            if(!header.isObject()||!fields.equals(Set.of("alg","typ","kid"))||!header.path("alg").asText().equals("RS256")
                ||!header.path("typ").asText().equals("toolgate-breakglass-approval+jws")
                ||!header.path("kid").isTextual())throw Failure.validation();
            String kid=header.path("kid").asText();var key=keys.get(kid);
            if(key==null||!operators.add(kid))throw ManagementAccess.denied();
            byte[] payload=decode(parts[1]),signed=decode(parts[2]);
            if(payload.length>24000||signed.length!=(key.getModulus().bitLength()+7)/8)throw Failure.validation();
            try {
                var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);
                verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
                if(!verifier.verify(signed))throw ManagementAccess.denied();
            } catch(GeneralSecurityException invalid) {throw ManagementAccess.denied();}
            // Decode strictly and require exact canonical payload bytes, including Unicode.
            var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            String document;try{document=text.decode(java.nio.ByteBuffer.wrap(payload)).toString();}
            catch(java.nio.charset.CharacterCodingException invalid){throw Failure.validation();}
            var current=codec.model(document,BreakGlassApprovalClaims.class);
            var value=(JsonNode)codec.value(document);
            if(!java.util.Arrays.equals(payload,Canonical.jcs(value)))throw Failure.validation();
            if(shared==null){shared=value;claims=current;}else if(!shared.equals(value))throw ManagementAccess.denied();
        }
        if(operators.size()<2||claims==null||!claims.tenantId().equals(tenant.value())||!claims.requestId().equals(requestId)
            ||!claims.scope().equals(request.scope())||claims.mode()!=request.mode()||!claims.reason().equals(request.reason())
            ||claims.issuedAtUnixMs()>now||claims.notAfterUnixMs()<=now
            ||claims.notAfterUnixMs()<=claims.issuedAtUnixMs()||claims.notAfterUnixMs()-claims.issuedAtUnixMs()>600000)
            throw ManagementAccess.denied();
        return new Approved(claims,new ArrayList<>(operators));
    }
}
