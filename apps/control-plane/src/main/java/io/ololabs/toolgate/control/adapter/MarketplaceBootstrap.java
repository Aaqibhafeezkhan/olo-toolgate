// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import java.nio.file.Path;
import java.security.KeyStore;

/** The OLO Orgs Marketplace settings come from the environment only (api.md §4), so a configuration import can't
 *  redirect the fetch origin or swap the pinned keys. Each pinned key's fingerprint is logged at startup; Control never
 *  fetches a key to trust it. A half-configured Marketplace fails startup. */
@ApplicationScoped
public class MarketplaceBootstrap {
    @Inject Config config; @Inject ContractCodec codec;

    Marketplace.Settings settings() {
        if (!config.getOptionalValue("toolgate.control.marketplace.enabled", Boolean.class).orElse(false)) return Marketplace.Settings.disabled();
        var settings = Marketplace.Settings.parse(value("id"), value("base-url"), value("keys"));
        var log = org.jboss.logging.Logger.getLogger(MarketplaceBootstrap.class);
        log.infof("marketplace_enabled marketplace=%s origin=%s", settings.marketplaceId(), settings.origin());
        for (var key : settings.keys().values()) log.infof("marketplace_key marketplace=%s key=%s fingerprint=%s", settings.marketplaceId(), key.keyId(), key.fingerprint());
        return settings;
    }

    private String value(String name) { return config.getOptionalValue("toolgate.control.marketplace." + name, String.class).orElse(null); }

    /** An optional PEM bundle (at most 4 certificates) for a Marketplace behind a private CA. */
    private javax.net.ssl.SSLContext tls() {
        var path = config.getOptionalValue("toolgate.control.marketplace.ca-path", String.class);
        if (path.isEmpty()) return null;
        try {
            var certificates = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(ProtectedCustody.read(Path.of(path.get()), 65536)));
            if (certificates.isEmpty() || certificates.size() > 4) throw new IllegalArgumentException();
            var trusted = KeyStore.getInstance(KeyStore.getDefaultType()); trusted.load(null, null); int i = 0;
            for (var certificate : certificates) trusted.setCertificateEntry("marketplace-" + (i++), certificate);
            var trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); trust.init(trusted);
            var tls = javax.net.ssl.SSLContext.getInstance("TLS"); tls.init(null, trust.getTrustManagers(), null); return tls;
        } catch (java.io.IOException | java.security.GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalStateException("TOOLGATE_CONTROL_MARKETPLACE_CA_PATH must name a PEM bundle of 1 to 4 certificates", failure);
        }
    }

    @Produces @ApplicationScoped Marketplace marketplace(PostgresStore store, ServerPackages packages) {
        var settings = settings();
        MarketplaceSource source = settings.enabled() ? new HttpsMarketplaceSource(settings.origin(), tls()) : (path, accept, max) -> { throw Failure.unavailable(); };
        return new Marketplace(settings, source, packages, store, codec, java.time.Clock.systemUTC());
    }

    void start(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event, Marketplace marketplace) { marketplace.settings(); }
}
