// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.*;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/** Device proof is session-bound evidence, never an approval or a permission grant. */
public final class ConsoleDeviceSessions {
    private ConsoleDeviceSessions(){}
    public static final String DOMAIN="OLO ToolGate console device proof v1\n";
    public static String current(Store.Session tx,Codec codec,String session,String user,long now){
        if(session==null)return null;var context=tx.consoleDevice(session);
        if(context==null||!context.userId().equals(user)||context.verifiedAt()<=0||context.expiresAt()<=now)return null;
        var endpoint=tx.endpoint(context.deviceId());
        if(endpoint!=null){var device=codec.model(endpoint.document(),EndpointDeviceRecord.class);if(device.state()==EndpointState.REVOKED||!device.userId().equals(user)||!device.keyFingerprint().equals(context.fingerprint()))return null;
            var entry=tx.load().entries().get(Ids.Kind.DEVICE.id(context.deviceId()));return entry!=null&&codec.model(entry.document(),ControlDevice.class).ownerUserId().equals(user)?context.deviceId():null;}
        var enrollment=tx.enrollmentCode(context.codeDigest());
        if(enrollment==null||enrollment.expiresAt()<=now||!user.equals(enrollment.userId()))return null;
        var review=codec.model(enrollment.document(),EndpointEnrollmentReview.class);
        return review.state()==EnrollmentState.PENDING&&review.deviceId().equals(context.deviceId())&&review.keyFingerprint().equals(context.fingerprint())?context.deviceId():null;
    }
    public static boolean verify(String csr,String nonce,String signature){
        try(var parser=new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(csr))){
            if(!(parser.readObject() instanceof org.bouncycastle.pkcs.PKCS10CertificationRequest request))return false;
            var key=new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().getPublicKey(request.getSubjectPublicKeyInfo());
            if(!key.getAlgorithm().equals("EC"))return false;
            var bytes=Base64.getUrlDecoder().decode(signature);if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(signature))return false;
            var verifier=java.security.Signature.getInstance("SHA256withECDSA");verifier.initVerify(key);verifier.update((DOMAIN+nonce).getBytes(StandardCharsets.UTF_8));return verifier.verify(bytes);
        }catch(java.io.IOException|java.security.GeneralSecurityException|IllegalArgumentException invalid){return false;}
    }
}
