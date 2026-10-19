// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.*;

/** Operator-managed local wrapping key ring. This remains a deployment-wide trust boundary. */
public final class LocalVaultWrappingProvider implements VaultWrappingProvider {
    private final String current;
    private final Map<String,byte[]> keys;
    public LocalVaultWrappingProvider(String current,Map<String,byte[]> keys) {
        if(keys.isEmpty()||keys.size()>16||!keys.containsKey(current))throw new IllegalArgumentException();
        var copy=new HashMap<String,byte[]>();
        keys.forEach((id,key)->{if(!id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")||key.length!=32)throw new IllegalArgumentException();copy.put("local:"+id,key.clone());});
        this.current="local:"+current;this.keys=Map.copyOf(copy);
    }
    public String currentReference(){return current;}
    private byte[] key(String reference){var key=keys.get(reference);if(key==null)throw Failure.unavailable();return key;}
    public String wrap(String reference,byte[] plaintext,byte[] context){return VaultEncryption.encrypt(key(reference),plaintext,context);}
    public byte[] unwrap(String reference,String wrapped,byte[] context){return VaultEncryption.decrypt(key(reference),wrapped,context);}
}
