// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** No plaintext cache. Authorization precedes every runtime unwrap; provisioning is platform-only. */
public final class TenantVaultKeys {
    private final Codec codec;
    private final VaultWrappingProvider provider;
    public TenantVaultKeys(Codec codec,VaultWrappingProvider provider){this.codec=codec;this.provider=provider;}
    private byte[] context(TenantId tenant,String purpose,long version,String reference){return codec.json(List.of("ToolGate wrapped tenant key",1L,tenant.value(),purpose,version,reference)).getBytes(StandardCharsets.UTF_8);}
    private byte[] random(){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return key;}
    private EnterpriseStore.VaultKey wrapped(TenantId tenant,long version,byte[] encryption,byte[] retry) {
        String reference=provider.currentReference();
        return new EnterpriseStore.VaultKey(version,"ACTIVE",reference,provider.wrap(reference,encryption,context(tenant,"ENCRYPTION",version,reference)),provider.wrap(reference,retry,context(tenant,"RETRY",0,reference)));
    }
    public void provision(TenantId tenant,Store.Session tx) {
        if(tx.enterprise().currentVaultKey()!=null)return;
        if(!tx.enterprise().vaultKeys().isEmpty())throw Failure.conflict();
        tx.enterprise().insertVaultKey(initial(tenant));
    }
    public EnterpriseStore.VaultKey initial(TenantId tenant) {
        byte[] encryption=random(),retry=random();
        try{return wrapped(tenant,1,encryption,retry);}
        finally{Arrays.fill(encryption,(byte)0);Arrays.fill(retry,(byte)0);}
    }
    public EnterpriseStore.VaultKey current(Store.Session tx){var key=tx.enterprise().currentVaultKey();if(key==null)throw Failure.unavailable();return key;}
    public byte[] encryption(TenantId tenant,EnterpriseStore.VaultKey key) {
        if(key==null||!Set.of("ACTIVE","READ_ONLY").contains(key.state()))throw Failure.unavailable();
        byte[] clear=provider.unwrap(key.providerReference(),key.wrappedEncryptionKey(),context(tenant,"ENCRYPTION",key.version(),key.providerReference()));
        if(clear.length!=32){Arrays.fill(clear,(byte)0);throw Failure.unavailable();}return clear;
    }
    public byte[] retry(TenantId tenant,Store.Session tx) {
        var key=current(tx);byte[] clear=provider.unwrap(key.providerReference(),key.wrappedRetryKey(),context(tenant,"RETRY",0,key.providerReference()));
        if(clear.length!=32){Arrays.fill(clear,(byte)0);throw Failure.unavailable();}return clear;
    }
    public EnterpriseStore.VaultKey rotate(TenantId tenant,Store.Session tx) {
        var previous=current(tx);if(previous.version()>=1024)throw Failure.conflict();
        byte[] encryption=random(),retry=null;
        try {
            retry=retry(tenant,tx);
            var next=wrapped(tenant,previous.version()+1,encryption,retry);
            tx.enterprise().updateVaultKey(new EnterpriseStore.VaultKey(previous.version(),"READ_ONLY",previous.providerReference(),previous.wrappedEncryptionKey(),previous.wrappedRetryKey()),"ACTIVE");
            tx.enterprise().insertVaultKey(next);return next;
        }finally{Arrays.fill(encryption,(byte)0);if(retry!=null)Arrays.fill(retry,(byte)0);}
    }
    public void rewrap(TenantId tenant,Store.Session tx) {
        for(var key:tx.enterprise().vaultKeys()) {
            if(key.state().equals("RETIRED"))continue;
            byte[] encryption=encryption(tenant,key),retry=null;
            try{retry=provider.unwrap(key.providerReference(),key.wrappedRetryKey(),context(tenant,"RETRY",0,key.providerReference()));if(retry.length!=32)throw Failure.unavailable();var next=wrapped(tenant,key.version(),encryption,retry);tx.enterprise().updateVaultKey(new EnterpriseStore.VaultKey(next.version(),key.state(),next.providerReference(),next.wrappedEncryptionKey(),next.wrappedRetryKey()),key.state());}
            finally{Arrays.fill(encryption,(byte)0);if(retry!=null)Arrays.fill(retry,(byte)0);}
        }
    }
}
