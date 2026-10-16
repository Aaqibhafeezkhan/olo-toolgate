// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Client-tool images and releases (ADR 026). Disabled unless a registry, repository and approved base are configured.
 *  The package-release key is optional: without it Control returns the exact document for the independent release
 *  authority. When present it must be one of the configured release trust keys and never the organization key. */
@ApplicationScoped
public class ClientReleaseBootstrap {
    @Inject Config config; @Inject PostgresStore store; @Inject ContractCodec codec; @Inject FleetService fleet; @Inject ArtifactStore artifacts;
    private String value(String name) { return config.getOptionalValue("toolgate.control.client-images." + name, String.class).orElse(null); }

    @Produces @ApplicationScoped ClientReleases releases() {
        if (!config.getOptionalValue("toolgate.control.client-images.enabled", Boolean.class).orElse(false)) return new ClientReleases(store, codec, null, null, null, fleet, artifacts, java.time.Clock.systemUTC());
        try {
            javax.net.ssl.SSLContext tls = null;
            if (value("ca-path") != null) {
                var certificates = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(ProtectedCustody.read(Path.of(value("ca-path")), 65536)));
                if (certificates.isEmpty() || certificates.size() > 4) throw new IllegalArgumentException();
                var trusted = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); trusted.load(null, null); int i = 0;
                for (var certificate : certificates) trusted.setCertificateEntry("registry-" + (i++), certificate);
                var trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); trust.init(trusted);
                tls = javax.net.ssl.SSLContext.getInstance("TLS"); tls.init(null, trust.getTrustManagers(), null);
            }
            String credentials = value("credentials-path") == null ? null : new String(ProtectedCustody.privateKey(Path.of(value("credentials-path")), 4096), java.nio.charset.StandardCharsets.UTF_8).strip();
            var registry = new HttpsOciRegistry(value("registry-url"), credentials, tls);
            String minimum = value("minimum-client-version");
            if (minimum == null) try (var in = ClientReleaseBootstrap.class.getResourceAsStream("/META-INF/toolgate-product.version")) { minimum = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip(); }
            var settings = ClientReleases.Settings.parse(value("repository"), value("base"), value("platforms") == null ? "LINUX" : value("platforms"), minimum);
            PackageReleaseSigner signer = null;
            var keyPath = config.getOptionalValue("toolgate.control.package-release.private-key-path", String.class);
            if (keyPath.isPresent()) {
                String pem = ProtectedCustody.privateText(Path.of(keyPath.get()), 16384);
                byte[] der = Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
                var key = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
                var releaseKeys = (com.fasterxml.jackson.databind.JsonNode) codec.value(new String(ProtectedCustody.read(Path.of(config.getValue("toolgate.control.fleet.release-keys-path", String.class)), 8192), java.nio.charset.StandardCharsets.UTF_8));
                var organizationKeys = (com.fasterxml.jackson.databind.JsonNode) codec.value(new String(ProtectedCustody.read(Path.of(config.getValue("toolgate.control.fleet.organization-keys-path", String.class)), 8192), java.nio.charset.StandardCharsets.UTF_8));
                String n = Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(key.getModulus()));
                String kid = config.getValue("toolgate.control.package-release.key-id", String.class);
                boolean trusted = false; for (var k : releaseKeys) trusted |= k.path("kid").asText().equals(kid) && k.path("n").asText().equals(n);
                for (var k : organizationKeys) if (k.path("n").asText().equals(n)) throw new IllegalArgumentException("The package-release key must differ from the organization key");
                if (!trusted) throw new IllegalArgumentException("The package-release key must be a configured release trust key");
                signer = new RsaPackageReleaseSigner(codec, key, kid);
            }
            return new ClientReleases(store, codec, registry, settings, signer, fleet, artifacts, java.time.Clock.systemUTC());
        } catch (Exception invalid) { throw new IllegalStateException("Client image releases need a registry origin, repository, approved base by digest and, if configured, a separate release key", invalid); }
    }
    private static byte[] unsigned(java.math.BigInteger value) { byte[] bytes = value.toByteArray(); return bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes; }
}
