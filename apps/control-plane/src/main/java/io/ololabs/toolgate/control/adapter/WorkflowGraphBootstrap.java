// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import java.nio.file.Path;

/** Runtime routing/trust are operator installation data, never profile-selected URLs or keys. */
@ApplicationScoped
public class WorkflowGraphBootstrap {
    @Inject Config config;
    @Inject ContractCodec codec;
    private volatile WorkflowGraphPreparation.Builder builder;
    private String required(String key) {
        return config.getOptionalValue("toolgate.control.runtime-builder." + key, String.class).orElseThrow(Failure::unavailable);
    }
    WorkflowGraphPreparation.Builder builder() {
        var current = builder;
        if (current != null) return current;
        synchronized (this) {
            if (builder != null) return builder;
            char[] password = null;
            byte[] identity = null;
            try {
                String origin = required("origin");
                password = ProtectedCustody.privateText(Path.of(required("password-path")), 1024).strip().toCharArray();
                identity = ProtectedCustody.privateKey(Path.of(required("identity-path")), 65536);
                var keys = java.security.KeyStore.getInstance("PKCS12");
                keys.load(new java.io.ByteArrayInputStream(identity), password);
                var manager = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
                manager.init(keys, password);
                var certificates = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(
                    new java.io.ByteArrayInputStream(ProtectedCustody.read(Path.of(required("ca-path")), 65536)));
                if (certificates.isEmpty() || certificates.size() > 8) throw Failure.unavailable();
                var trusted = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); trusted.load(null, null);
                int i = 0; for (var certificate : certificates) trusted.setCertificateEntry("runtime-" + i++, certificate);
                var trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); trust.init(trusted);
                var tls = javax.net.ssl.SSLContext.getInstance("TLS"); tls.init(manager.getKeyManagers(), trust.getTrustManagers(), null);
                builder = new HttpsWorkflowGraphBuilder(origin, codec, tls);
                return builder;
            } catch (java.io.IOException | java.security.GeneralSecurityException invalid) {
                throw Failure.unavailable();
            } finally {
                if (password != null) java.util.Arrays.fill(password, '\0');
                if (identity != null) java.util.Arrays.fill(identity, (byte)0);
            }
        }
    }
}
