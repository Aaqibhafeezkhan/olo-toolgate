// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.*;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * ADR 062 periodic signed anchoring of every active tenant's audit-chain head.
 * Disabled unless configured; when enabled, missing or shared key custody fails startup closed.
 */
@ApplicationScoped
public class AuditAnchorScheduler {
    private static final Logger LOG = Logger.getLogger(AuditAnchorScheduler.class);
    @Inject Config config; @Inject TenantRegistry tenants; @Inject PostgresStore store; @Inject IdentityKeyBootstrap identity;
    private ScheduledExecutorService executor; private AuditAnchoring anchoring;

    private String setting(String name) { return config.getValue("toolgate.control.audit-anchor." + name, String.class); }

    void start(@Observes StartupEvent ignored) {
        if (!config.getOptionalValue("toolgate.control.audit-anchor.enabled", Boolean.class).orElse(false)) return;
        try {
            var keyring = AuditAnchors.keyring(ProtectedCustody.text(Path.of(setting("keyring-path")), 65536));
            var signer = signer(setting("key-id"), Path.of(setting("private-key-path")));
            var pinned = keyring.active(signer.keyId()).key();
            if (!pinned.getModulus().equals(signer.key.getModulus())) throw new IllegalArgumentException("anchor key is not the pinned active key");
            requireDedicated(signer.key.getModulus(), identity.forbidden());
            anchoring = new AuditAnchoring(store, signer, keyring, new LocalAnchorSink(Path.of(setting("sink-directory"))), java.time.Clock.systemUTC());
        } catch (Exception invalid) { throw new IllegalStateException("Dedicated audit anchor key, pinned keyring and sink required", invalid); }
        long interval = config.getOptionalValue("toolgate.control.audit-anchor.interval-seconds", Long.class).orElse(300L);
        if (interval < 10 || interval > 86400) throw new IllegalStateException("Audit anchor interval must be 10..86400 seconds");
        executor = Executors.newSingleThreadScheduledExecutor(task -> { var thread = new Thread(task, "toolgate-audit-anchor"); thread.setDaemon(true); return thread; });
        executor.scheduleWithFixedDelay(this::sweep, 30, interval, TimeUnit.SECONDS);
    }

    void sweep() {
        String cursor = "";
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var page = tenants.activeAfter(cursor, 64); if (page.isEmpty()) return;
                for (var tenant : page) {
                    cursor = tenant.value();
                    try {
                        var result = anchoring.anchor(tenant);
                        if (result.outcome() == AuditAnchoring.Outcome.CHAIN_BROKEN)
                            LOG.errorf("audit_chain_broken tenant=%s defect=%s index=%d", tenant.value(), result.chain().defect(), result.chain().chainIndex());
                    } catch (AuditAnchors.Refused refused) {
                        LOG.errorf("audit_anchor_refused tenant=%s reason=%s", tenant.value(), refused.refusal());
                    } catch (RuntimeException unavailable) { LOG.warnf("audit_anchor_unavailable tenant=%s", tenant.value()); }
                }
                if (page.size() < 64) return;
            }
        } catch (RuntimeException unavailable) { LOG.warn("audit_anchor_registry_unavailable"); }
    }

    static final class RsaAnchorSigner implements AuditAnchors.Signer {
        private final String keyId; final RSAPrivateCrtKey key;
        RsaAnchorSigner(String keyId, RSAPrivateCrtKey key) {
            if (!java.util.Set.of(3072, 4096).contains(key.getModulus().bitLength()) || !key.getPublicExponent().equals(java.math.BigInteger.valueOf(65537)))
                throw new IllegalArgumentException("Invalid audit anchor key");
            this.keyId = keyId; this.key = key;
        }
        public String keyId() { return keyId; }
        public byte[] sign(byte[] message) {
            try { var signature = Signature.getInstance("SHA256withRSA"); signature.initSign(key); signature.update(message); return signature.sign(); }
            catch (GeneralSecurityException failure) { throw Failure.unavailable(); }
        }
    }

    /**
     * The anchor key path is itself one of the collected custody entries; any further match is reuse
     * of a fleet, permit, bundle, release, identity, TLS or operator-approval key.
     */
    static void requireDedicated(java.math.BigInteger modulus, java.util.List<java.math.BigInteger> custody) {
        if (custody.stream().filter(modulus::equals).count() != 1) throw new IllegalArgumentException("anchor key shares another custody domain");
    }

    static RsaAnchorSigner signer(String keyId, Path path) throws Exception {
        String pem = ProtectedCustody.privateText(path, 16384);
        if (!pem.startsWith("-----BEGIN " + "PRIVATE KEY-----")) throw new IllegalArgumentException();
        var body = pem.replace("-----BEGIN " + "PRIVATE KEY-----", "").replace("-----END " + "PRIVATE KEY-----", "").replaceAll("\\s", "");
        return new RsaAnchorSigner(keyId, (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body))));
    }

    @PreDestroy void stop() { if (executor != null) executor.shutdownNow(); }
}
