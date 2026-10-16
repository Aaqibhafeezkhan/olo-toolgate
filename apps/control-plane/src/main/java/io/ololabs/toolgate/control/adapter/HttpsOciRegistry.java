// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.OciImages;
import io.ololabs.toolgate.control.application.OciRegistry;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** OCI Distribution v2 over HTTPS (ADR 026). Reads are verified against the requested digest; every write is by digest
 *  and is read back. Credentials come from a protected file and are sent as Basic, or exchanged for a bearer token when
 *  the registry challenges with one. Neither credentials nor tokens are logged. */
public final class HttpsOciRegistry implements OciRegistry {
    private static final String ACCEPT = String.join(",", OciImages.OCI_INDEX, OciImages.OCI_MANIFEST, OciImages.DOCKER_LIST, OciImages.DOCKER_MANIFEST);
    private static final int MAX_MANIFEST = 4 * 1024 * 1024;
    /** Upper bound for a base layer copied through Control when the registry cannot mount it. */
    private static final int MAX_COPIED_LAYER = 512 * 1024 * 1024;
    private static final Pattern CHALLENGE = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern REPOSITORY = Pattern.compile("[a-z0-9]+([._/-][a-z0-9]+)*"), DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    private final URI origin; private final String host; private final String basic; private final HttpClient client;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    /** origin: https://host[:port]; plain http only for a loopback development registry. */
    public HttpsOciRegistry(String origin, String credentials, javax.net.ssl.SSLContext tls) {
        var uri = URI.create(origin);
        boolean loopback = "http".equals(uri.getScheme()) && java.util.Set.of("127.0.0.1", "localhost").contains(uri.getHost());
        if (!("https".equals(uri.getScheme()) || loopback) || uri.getHost() == null || uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")
            || uri.getRawQuery() != null || uri.getRawUserInfo() != null) throw new IllegalArgumentException("Registry origin must be https://host[:port]");
        this.origin = URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
        this.host = uri.getRawAuthority();
        if (credentials != null && (!credentials.contains(":") || credentials.length() > 4096)) throw new IllegalArgumentException("Registry credentials must be user:secret");
        this.basic = credentials == null ? null : "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL);
        if (tls != null) builder.sslContext(tls);
        this.client = builder.build();
    }

    public String host() { return host; }
    private static String repository(String value) { if (value == null || !REPOSITORY.matcher(value).matches() || value.length() > 255) throw Failure.validation(); return value; }
    private static String digest(String value) { if (value == null || !DIGEST.matcher(value).matches()) throw Failure.validation(); return value; }

    private HttpResponse<byte[]> send(HttpRequest.Builder request, String scope) {
        try {
            var token = tokens.get(scope);
            if (token != null) request.setHeader("Authorization", "Bearer " + token); else if (basic != null) request.setHeader("Authorization", basic);
            var built = request.timeout(Duration.ofSeconds(120)).build();
            var response = client.send(built, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 401) return response;
            var challenge = response.headers().firstValue("WWW-Authenticate").orElse("");
            if (!challenge.regionMatches(true, 0, "Bearer ", 0, 7) || token != null) return response;
            var fields = new java.util.HashMap<String, String>(); var m = CHALLENGE.matcher(challenge); while (m.find()) fields.put(m.group(1), m.group(2));
            var realm = URI.create(fields.getOrDefault("realm", ""));
            if (!"https".equals(realm.getScheme()) && !("http".equals(realm.getScheme()) && "http".equals(origin.getScheme()))) return response;
            var query = new StringBuilder();
            if (fields.containsKey("service")) query.append("service=").append(URLEncoder.encode(fields.get("service"), StandardCharsets.UTF_8));
            for (var each : scope.split(" ")) query.append(query.isEmpty() ? "" : "&").append("scope=").append(URLEncoder.encode(each, StandardCharsets.UTF_8));
            var tokenRequest = HttpRequest.newBuilder(URI.create(realm + (realm.getRawQuery() == null ? "?" : "&") + query)).GET().timeout(Duration.ofSeconds(30));
            if (basic != null) tokenRequest.header("Authorization", basic);
            var issued = client.send(tokenRequest.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (issued.statusCode() != 200 || issued.body().length > 65536) throw Failure.unavailable();
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(issued.body());
            String value = body.path("token").asText(body.path("access_token").asText(""));
            if (value.isEmpty()) throw Failure.unavailable();
            tokens.put(scope, value);
            return client.send(HttpRequest.newBuilder(built, (name, v) -> !name.equalsIgnoreCase("Authorization")).header("Authorization", "Bearer " + value).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.io.IOException failure) { throw Failure.unavailable(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw Failure.unavailable(); }
    }
    private URI at(String path) { return origin.resolve(path); }
    private static String pull(String repository) { return "repository:" + repository + ":pull"; }
    private static String push(String repository) { return "repository:" + repository + ":pull,push"; }

    public Manifest manifest(String repository, String digest) {
        var response = send(HttpRequest.newBuilder(at("/v2/" + repository(repository) + "/manifests/" + digest(digest))).header("Accept", ACCEPT).GET(), pull(repository));
        if (response.statusCode() != 200 || response.body().length > MAX_MANIFEST || !OciImages.sha256(response.body()).equals(digest)) throw Failure.unavailable();
        var type = response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
        if (type.isEmpty() || type.equals("application/json")) {
            try { type = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).path("mediaType").asText(); } catch (java.io.IOException invalid) { throw Failure.unavailable(); }
        }
        return new Manifest(type, response.body());
    }

    public byte[] blob(String repository, String digest, int maxBytes) {
        var response = send(HttpRequest.newBuilder(at("/v2/" + repository(repository) + "/blobs/" + digest(digest))).GET(), pull(repository));
        if (response.statusCode() != 200 || response.body().length > maxBytes || !OciImages.sha256(response.body()).equals(digest)) throw Failure.unavailable();
        return response.body();
    }

    public boolean hasBlob(String repository, String digest) {
        int status = send(HttpRequest.newBuilder(at("/v2/" + repository(repository) + "/blobs/" + digest(digest))).method("HEAD", HttpRequest.BodyPublishers.noBody()), push(repository)).statusCode();
        if (status == 200) return true;
        if (status == 404) return false;
        throw Failure.unavailable();
    }

    public void mount(String repository, String digest, String from) {
        var response = send(HttpRequest.newBuilder(at("/v2/" + repository(repository) + "/blobs/uploads/?mount=" + digest(digest) + "&from=" + URLEncoder.encode(repository(from), StandardCharsets.UTF_8)))
            .POST(HttpRequest.BodyPublishers.noBody()), push(repository) + " " + pull(from));
        if (response.statusCode() == 201) return;
        // The registry did not mount across repositories: copy the verified blob instead.
        if (response.statusCode() == 202) { putBlob(repository, digest, blob(from, digest, MAX_COPIED_LAYER)); return; }
        throw Failure.unavailable();
    }

    public void putBlob(String repository, String digest, byte[] bytes) {
        if (!OciImages.sha256(bytes).equals(digest(digest))) throw Failure.validation();
        if (hasBlob(repository, digest)) return;
        var started = send(HttpRequest.newBuilder(at("/v2/" + repository(repository) + "/blobs/uploads/")).POST(HttpRequest.BodyPublishers.noBody()), push(repository));
        var location = started.headers().firstValue("Location").orElse(null);
        if (started.statusCode() != 202 || location == null) throw Failure.unavailable();
        var upload = at(location.startsWith("/") ? location : URI.create(location).getRawPath() + (URI.create(location).getRawQuery() == null ? "" : "?" + URI.create(location).getRawQuery()));
        var target = URI.create(upload + (upload.getRawQuery() == null ? "?" : "&") + "digest=" + URLEncoder.encode(digest, StandardCharsets.UTF_8));
        var finished = send(HttpRequest.newBuilder(target).header("Content-Type", "application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)), push(repository));
        if (finished.statusCode() != 201 || !hasBlob(repository, digest)) throw Failure.unavailable();
    }

    public void putManifest(String repository, String digest, String mediaType, byte[] bytes) {
        if (!OciImages.sha256(bytes).equals(digest(digest))) throw Failure.validation();
        var response = send(HttpRequest.newBuilder(at("/v2/" + repository(repository) + "/manifests/" + digest)).header("Content-Type", mediaType).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)), push(repository));
        if (response.statusCode() != 201) throw Failure.unavailable();
        if (!java.util.Arrays.equals(manifest(repository, digest).body(), bytes)) throw Failure.unavailable();
    }
}
