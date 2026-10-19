// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Versioned AES-256-GCM envelope using JCE, with no pre-release format reader. */
public final class VaultEncryption {
    private VaultEncryption() {}
    private static final String PREFIX="TGVAULT1.";
    public static String encrypt(byte[] key,byte[] plaintext,byte[] context) {
        if(key.length!=32)throw Failure.unavailable();
        byte[] nonce=new byte[12];new java.security.SecureRandom().nextBytes(nonce);
        try {
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(context);
            byte[] encrypted=cipher.doFinal(plaintext),packet=Arrays.copyOf(nonce,nonce.length+encrypted.length);
            System.arraycopy(encrypted,0,packet,nonce.length,encrypted.length);return PREFIX+Base64.getEncoder().encodeToString(packet);
        } catch(java.security.GeneralSecurityException invalid) {throw Failure.unavailable();}
    }
    public static byte[] decrypt(byte[] key,String envelope,byte[] context) {
        if(key.length!=32||envelope==null||envelope.length()>65536||!envelope.startsWith(PREFIX))throw Failure.unavailable();
        try {
            byte[] packet=Base64.getDecoder().decode(envelope.substring(PREFIX.length()));if(packet.length<28)throw Failure.unavailable();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(packet,12)));cipher.updateAAD(context);
            return cipher.doFinal(Arrays.copyOfRange(packet,12,packet.length));
        } catch(java.security.GeneralSecurityException|IllegalArgumentException invalid) {throw Failure.unavailable();}
    }
}
