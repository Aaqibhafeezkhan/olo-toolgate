// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.microprofile.config.Config;

/** Protected operator-managed KEK ring; neither keys nor caller-supplied provider paths enter the registry. */
@ApplicationScoped
public class VaultKeyBootstrap {
    @Inject Config config;@Inject ContractCodec codec;
    void start(@jakarta.enterprise.event.Observes @jakarta.annotation.Priority(7) io.quarkus.runtime.StartupEvent event){optional();}
    public TenantVaultKeys optional() {
        if(config.getOptionalValue("toolgate.control.vault.key-path",String.class).isPresent())throw new IllegalStateException("Raw vault key configuration is unsupported; configure a protected wrapping key ring");
        var path=config.getOptionalValue("toolgate.control.vault.wrapping-key-ring-path",String.class);if(path.isEmpty())return null;
        byte[] raw=null;
        try {
            raw=ProtectedCustody.privateKey(Path.of(path.get()),16384);var node=codec.configuration(new String(raw,java.nio.charset.StandardCharsets.UTF_8),false);
            if(!node.isObject()||node.size()!=3||!node.path("formatVersion").isInt()||node.path("formatVersion").asInt()!=1||!node.path("currentKeyId").isTextual()||!node.path("keys").isObject())throw Failure.validation();
            var keys=new HashMap<String,byte[]>();
            try {
                for(var field:node.path("keys").properties()){if(!field.getValue().isTextual())throw Failure.validation();keys.put(field.getKey(),Base64.getDecoder().decode(field.getValue().asText()));}
                return new TenantVaultKeys(codec,new LocalVaultWrappingProvider(node.path("currentKeyId").asText(),keys));
            }finally{keys.values().forEach(key->Arrays.fill(key,(byte)0));}
        }catch(Exception invalid){throw new IllegalStateException("Protected vault wrapping key ring rejected");}
        finally{if(raw!=null)Arrays.fill(raw,(byte)0);}
    }
    public TenantVaultKeys required(){var keys=optional();if(keys==null)throw Failure.unavailable();return keys;}
}
