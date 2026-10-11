// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Where routes go in standalone mode (runtime-modes.md §3): each declared destination resolved from
 * {@code TOOLGATE_DESTINATION_<HOSTSETTING>} or {@code toolgate.dev.json}, checked against its declared port and TLS
 * settings, with one HTTP client per destination. This is developer feedback, not a network boundary.
 */
final class Egress {
    /** A resolved destination; {@code problem} is set when calls to it must fail with CONFIGURATION. */
    static final class Target {
        final String id;
        final URI base;
        final HttpClient client;
        final String problem;

        Target(String id, URI base, HttpClient client, String problem) {
            this.id = id;
            this.base = base;
            this.client = client;
            this.problem = problem;
        }
    }

    /** A resolved TCP destination; {@code problem} is set when connections to it must fail with CONFIGURATION. */
    record TcpTarget(String id, String host, int port, String problem) {}

    /** A cached OAuth 2.0 access token. */
    record Token(String value, long expiresAtMs) {}

    private final Map<String, Target> targets = new LinkedHashMap<>();
    private final Map<String, TcpTarget> tcpTargets = new LinkedHashMap<>();
    final Map<String, Token> tokens = new ConcurrentHashMap<>();
    final Config.Mode mode;

    private Egress(Config.Mode mode) {
        this.mode = mode;
    }

    /**
     * Resolves every destination the tools declare. A missing host is a warning (calls then fail with CONFIGURATION); a
     * value that contradicts the declaration refuses startup.
     */
    static Egress resolve(Config config, Iterable<JsonNode> destinations, java.io.PrintStream diagnostics) {
        Egress egress = new Egress(config.mode);
        for (JsonNode d : destinations) {
            String id = d.path("id").asText();
            if (egress.targets.containsKey(id)) continue;
            String setting = d.path("hostSetting").asText();
            String variable = "TOOLGATE_DESTINATION_" + setting.toUpperCase(Locale.ROOT);
            String value = config.destination(setting);
            if (value == null) {
                diagnostics.println("toolgate: WARNING destination " + id + " has no host; set " + variable + " or destinations." + setting
                        + " in toolgate.dev.json");
                egress.targets.put(id, new Target(id, null, null, "destination " + id + " has no host; set " + variable));
                continue;
            }
            egress.targets.put(id, egress.target(d, variable, value.trim()));
        }
        return egress;
    }

    Target target(String destinationId) {
        return targets.get(destinationId);
    }

    TcpTarget tcpTarget(String destinationId) {
        return tcpTargets.get(destinationId);
    }

    /**
     * Resolves every TCP destination the tools declare, from the same settings as HTTP destinations, as {@code host} or
     * {@code host:port}. The port must be the declared one; development may point a loopback host at any port.
     */
    void resolveTcp(Config config, Iterable<JsonNode> destinations, java.io.PrintStream diagnostics) {
        for (JsonNode d : destinations) {
            String id = d.path("id").asText();
            if (tcpTargets.containsKey(id)) continue;
            String setting = d.path("hostSetting").asText();
            String variable = "TOOLGATE_DESTINATION_" + setting.toUpperCase(Locale.ROOT);
            String value = config.destination(setting);
            if (value == null) {
                diagnostics.println("toolgate: WARNING TCP destination " + id + " has no host; set " + variable + " or destinations." + setting
                        + " in toolgate.dev.json");
                tcpTargets.put(id, new TcpTarget(id, null, -1, "TCP destination " + id + " has no host; set " + variable));
                continue;
            }
            String authority = value.trim();
            URI parsed;
            try {
                parsed = authority.isEmpty() || authority.contains("/") || authority.contains("@") ? null : new URI("x://" + authority);
            } catch (java.net.URISyntaxException e) {
                parsed = null;
            }
            if (parsed == null || parsed.getHost() == null || parsed.getPath() != null && !parsed.getPath().isEmpty()) {
                throw new StartupRefused("CONFIGURATION", variable + " must be a host or host:port, not " + value);
            }
            String host = parsed.getHost();
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            int declared = d.path("port").asInt();
            int port = parsed.getPort() < 0 ? declared : parsed.getPort();
            if (port != declared && !(mode != Config.Mode.PRODUCTION && isLoopback(host))) {
                throw new StartupRefused("CONFIGURATION", variable + " names port " + port + ", but TCP destination " + id + " declares port " + declared);
            }
            tcpTargets.put(id, new TcpTarget(id, host, port, null));
        }
    }

    private Target target(JsonNode d, String variable, String value) {
        String id = d.path("id").asText();
        long declaredPort = d.path("port").asLong(443);
        boolean plain = value.startsWith("http://");
        String authority = plain ? value.substring("http://".length()) : value;
        if (authority.endsWith("/")) authority = authority.substring(0, authority.length() - 1);
        if (authority.isEmpty() || authority.contains("/") || authority.contains("@") || authority.contains("?") || authority.contains("#")
                || value.contains("://") && !plain) {
            throw new StartupRefused("CONFIGURATION", variable + " must be a host or host:port, not " + value);
        }
        URI parsed;
        try {
            parsed = new URI("x://" + authority);
        } catch (java.net.URISyntaxException e) {
            throw new StartupRefused("CONFIGURATION", variable + " must be a host or host:port, not " + value);
        }
        String host = parsed.getHost();
        if (host == null) throw new StartupRefused("CONFIGURATION", variable + " must be a host or host:port, not " + value);
        boolean loopback = isLoopback(host);
        boolean relaxed = mode != Config.Mode.PRODUCTION && loopback;
        if (plain && !relaxed) {
            throw new StartupRefused("CONFIGURATION", variable + " may use http:// only for a loopback host in development mode; destination "
                    + id + " is HTTPS");
        }
        int port = parsed.getPort() < 0 ? (int) declaredPort : parsed.getPort();
        if (port != declaredPort && !relaxed) {
            throw new StartupRefused("CONFIGURATION", variable + " names port " + port + ", but destination " + id + " declares port " + declaredPort);
        }
        URI base = URI.create((plain ? "http://" : "https://") + (host.contains(":") ? "[" + host + "]" : host) + ":" + port);
        if (d.hasNonNull("caBundle")) {
            return new Target(id, base, null, "destination " + id + " names CA bundle " + d.get("caBundle").asText()
                    + ", which the standalone runtime cannot load yet");
        }
        Set<String> pins = new HashSet<>();
        for (JsonNode p : d.path("spkiPins")) {
            String pin = p.asText();
            if (!pin.startsWith("sha256/")) {
                throw new StartupRefused("CONFIGURATION", "destination " + id + " pin " + pin + " must be sha256/<base64 of the SPKI digest>");
            }
            pins.add(pin);
        }
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.getDefault());
        if (!pins.isEmpty()) builder.sslContext(pinned(pins));
        return new Target(id, base, builder.build(), null);
    }

    static boolean isLoopback(String host) {
        if (host.equalsIgnoreCase("localhost")) return true;
        if (!host.matches("[0-9.]+|\\[?[0-9a-fA-F:]+\\]?")) return false;
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** The {@code sha256/<base64>} pin of a certificate's SubjectPublicKeyInfo. */
    static String pin(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded());
            return "sha256/" + Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * TLS that trusts a server only when a certificate in its chain carries one of the pins. The pins take the place
     * of the CA and host-name checks, as for an admin-pinned SPKI in governed mode.
     */
    private static SSLContext pinned(Set<String> pins) {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            X509TrustManager system = null;
            for (TrustManager t : factory.getTrustManagers()) if (t instanceof X509TrustManager x) system = x;
            X509Certificate[] issuers = system == null ? new X509Certificate[0] : system.getAcceptedIssuers();
            X509ExtendedTrustManager manager = new X509ExtendedTrustManager() {
                private void check(X509Certificate[] chain) throws CertificateException {
                    if (chain == null || chain.length == 0) throw new CertificateException("the server sent no certificate");
                    for (X509Certificate c : List.of(chain)) if (pins.contains(pin(c))) return;
                    throw new CertificateException("no certificate in the server's chain matches the destination's SPKI pins");
                }

                @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException { check(chain); }
                @Override public void checkServerTrusted(X509Certificate[] chain, String auth, Socket s) throws CertificateException { check(chain); }
                @Override public void checkServerTrusted(X509Certificate[] chain, String auth, SSLEngine e) throws CertificateException { check(chain); }
                @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("not a server"); }
                @Override public void checkClientTrusted(X509Certificate[] chain, String auth, Socket s) throws CertificateException { throw new CertificateException("not a server"); }
                @Override public void checkClientTrusted(X509Certificate[] chain, String auth, SSLEngine e) throws CertificateException { throw new CertificateException("not a server"); }
                @Override public X509Certificate[] getAcceptedIssuers() { return issuers; }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {manager}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
