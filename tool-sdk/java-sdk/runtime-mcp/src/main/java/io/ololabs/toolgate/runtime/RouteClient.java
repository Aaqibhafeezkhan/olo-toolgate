// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ololabs.toolgate.sdk.context.HttpClient;
import io.ololabs.toolgate.sdk.context.RawResponse;
import io.ololabs.toolgate.sdk.context.RouteCall;
import io.ololabs.toolgate.sdk.errors.PlatformError;
import io.ololabs.toolgate.sdk.errors.ToolConfigurationError;
import io.ololabs.toolgate.sdk.errors.UpstreamError;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * The routes HTTP client of http.md for one standalone invocation. It refuses anything the tool's descriptor does not
 * declare, applies the route's limits, adds the route's credentials from local secrets, and maps failures to
 * {@link UpstreamError} (errors.md §2). It never retries and never follows redirects.
 */
final class RouteClient implements HttpClient {
    static final Set<String> FORBIDDEN_HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "host");
    private static final Pattern VARIABLE = Pattern.compile("\\{([^}:]+)(?::((?:[^{}]|\\{[^{}]*\\})*))?\\}");
    private static final int ERROR_BODY_BYTES = 8 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Egress egress;
    private final Map<String, JsonNode> routes;
    private final Map<String, JsonNode> auths;
    private final Map<String, DeclaredValues.Entry> secrets;
    private final StandaloneContext context;
    private final Instant deadline;
    private final Redactor redactor;
    private String idempotencyHeader;
    private volatile String idempotencyValue;

    RouteClient(Egress egress, Map<String, JsonNode> routes, Map<String, JsonNode> auths, Map<String, DeclaredValues.Entry> secrets,
            StandaloneContext context, Instant deadline, Redactor redactor) {
        this.egress = egress;
        this.routes = routes;
        this.auths = auths;
        this.secrets = secrets;
        this.context = context;
        this.deadline = deadline;
        this.redactor = redactor;
    }

    /** Sends {@code value} in {@code header} on every route that allows that header, unless the tool set it (execution.md §4). */
    void idempotency(String header, String value) {
        this.idempotencyHeader = header;
        this.idempotencyValue = value;
    }

    /** Replaces the forwarded value, once the business key is reserved. */
    void idempotencyValue(String value) {
        this.idempotencyValue = value;
    }

    @Override
    public RouteCall route(String routeId) {
        JsonNode route = routes.get(routeId);
        if (route == null) throw refused("Route " + routeId + " is not declared for this tool");
        return new Call(route);
    }

    @Override public RouteCall get(String destinationId, String pathTemplate) { return convenience("GET", destinationId, pathTemplate); }
    @Override public RouteCall post(String destinationId, String pathTemplate) { return convenience("POST", destinationId, pathTemplate); }
    @Override public RouteCall put(String destinationId, String pathTemplate) { return convenience("PUT", destinationId, pathTemplate); }
    @Override public RouteCall patch(String destinationId, String pathTemplate) { return convenience("PATCH", destinationId, pathTemplate); }
    @Override public RouteCall delete(String destinationId, String pathTemplate) { return convenience("DELETE", destinationId, pathTemplate); }
    @Override public RouteCall head(String destinationId, String pathTemplate) { return convenience("HEAD", destinationId, pathTemplate); }

    /**
     * The URL-style form (http.md §3): method, destination and template must match exactly one of the tool's routes
     * with the same variable names. The build-time analyser proves this before packaging; this is the same match.
     */
    private RouteCall convenience(String method, String destinationId, String pathTemplate) {
        String wanted = withoutPatterns(pathTemplate);
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode r : routes.values()) {
            if (r.path("method").asText().equals(method) && r.path("destination").asText().equals(destinationId)
                    && withoutPatterns(r.path("path").asText()).equals(wanted)) {
                matches.add(r);
            }
        }
        if (matches.size() != 1) {
            throw refused(method + " " + destinationId + " " + pathTemplate + (matches.isEmpty()
                    ? " matches no route declared for this tool" : " matches more than one route"));
        }
        return new Call(matches.get(0));
    }

    static String withoutPatterns(String template) {
        return VARIABLE.matcher(template).replaceAll(m -> Matcher.quoteReplacement("{" + m.group(1) + "}"));
    }

    private static PlatformError refused(String message) {
        return new PlatformError(PlatformError.Code.ROUTE_REFUSED, message);
    }

    /** One call on a declared route. */
    private final class Call implements RouteCall {
        private final JsonNode route;
        private final String routeId;
        private final Map<String, String> variables = new LinkedHashMap<>();
        private final Map<String, String> patterns = new LinkedHashMap<>();
        private final Map<String, List<String>> query = new LinkedHashMap<>();
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private byte[] body;
        private String contentType;
        private long timeoutMs;

        Call(JsonNode route) {
            this.route = route;
            this.routeId = route.path("id").asText();
            this.timeoutMs = route.path("timeoutMs").asLong(10_000);
            Matcher m = VARIABLE.matcher(route.path("path").asText());
            while (m.find()) patterns.put(m.group(1), m.group(2));
        }

        @Override
        public RouteCall pathVar(String name, Object value) {
            if (!patterns.containsKey(name)) throw refused("Route " + routeId + " has no path variable " + name);
            if (value == null) throw refused("Path variable " + name + " of route " + routeId + " is null");
            String text = String.valueOf(value);
            String pattern = patterns.get(name);
            // A variable never matches "/", whatever its pattern (http.md §1).
            if (text.isEmpty() || text.contains("/") || pattern == null || !Pattern.matches(pattern, text)) {
                throw refused("Path variable " + name + " of route " + routeId + " does not match its pattern");
            }
            variables.put(name, text);
            return this;
        }

        @Override
        public RouteCall query(String name, Object value) {
            boolean allowed = false;
            for (JsonNode q : route.path("query")) allowed |= q.asText().equals(name);
            if (!allowed) throw refused("Route " + routeId + " does not allow query parameter " + name);
            if (value == null) return this;
            List<String> values = query.computeIfAbsent(name, k -> new ArrayList<>());
            if (value instanceof Iterable<?> many) {
                for (Object v : many) if (v != null) values.add(String.valueOf(v));
            } else {
                values.add(String.valueOf(value));
            }
            return this;
        }

        @Override
        public RouteCall header(String name, String value) {
            String lower = name.toLowerCase(Locale.ROOT);
            JsonNode auth = route.hasNonNull("auth") ? auths.get(route.get("auth").asText()) : null;
            String authHeader = auth == null ? "" : auth.path("header").asText("").toLowerCase(Locale.ROOT);
            if (FORBIDDEN_HEADERS.contains(lower) || lower.startsWith("x-forwarded-") || lower.equals(authHeader)) {
                throw refused("Header " + name + " is never allowed on a route");
            }
            boolean allowed = false;
            for (JsonNode h : route.path("headers")) allowed |= h.asText().equalsIgnoreCase(name);
            if (!allowed) throw refused("Route " + routeId + " does not allow header " + name);
            if (value == null) {
                headers.remove(name);
            } else {
                if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw refused("Header " + name + " has a line break");
                headers.put(name, value);
            }
            return this;
        }

        @Override
        public RouteCall body(Object value) {
            String method = route.path("method").asText();
            if (method.equals("GET") || method.equals("HEAD")) throw refused("Route " + routeId + " is " + method + " and takes no body");
            List<String> types = new ArrayList<>();
            for (JsonNode t : route.path("requestContentTypes")) types.add(t.asText());
            String json = types.stream().filter(t -> t.equals("application/json") || t.endsWith("+json")).findFirst().orElse(null);
            if (value == null) {
                body = null;
                contentType = null;
            } else if (value instanceof byte[] bytes) {
                if (types.isEmpty()) throw refused("Route " + routeId + " declares no request content type");
                body = bytes.clone();
                contentType = types.get(0);
            } else if (json != null) {
                try {
                    body = JSON.writeValueAsBytes(Converter.toJson(value));
                } catch (IOException e) {
                    throw new IllegalArgumentException("the body of route " + routeId + " is not JSON", e);
                }
                contentType = json;
            } else if (value instanceof CharSequence text && !types.isEmpty()) {
                body = text.toString().getBytes(StandardCharsets.UTF_8);
                contentType = types.get(0);
            } else {
                throw refused("Route " + routeId + " takes " + types + ", not " + value.getClass().getSimpleName());
            }
            if (body != null && body.length > route.path("maxRequestBytes").asLong(1_048_576)) {
                throw refused("The body is larger than the maxRequestBytes of route " + routeId);
            }
            return this;
        }

        @Override
        public RouteCall timeout(Duration timeout) {
            // A call may only shorten the route timeout (http.md §2).
            if (timeout != null && !timeout.isNegative() && !timeout.isZero()) timeoutMs = Math.min(route.path("timeoutMs").asLong(10_000), timeout.toMillis());
            return this;
        }

        @Override
        public <T> T send(Class<T> type) {
            Response r = exchange();
            if (type == byte[].class) return type.cast(r.body);
            if (type == String.class) return type.cast(new String(r.body, StandardCharsets.UTF_8));
            if (type == Void.class || type == void.class) return null;
            if (r.body.length == 0) return null;
            JsonNode tree;
            try {
                tree = JSON.readTree(r.body);
            } catch (IOException e) {
                throw new IllegalStateException("the response on route " + routeId + " is not JSON");
            }
            return type.cast(Converter.fromJson(tree, type));
        }

        @Override
        public RawResponse sendRaw() {
            return exchange();
        }

        private Response exchange() {
            for (String name : patterns.keySet()) {
                if (!variables.containsKey(name)) throw refused("Path variable " + name + " of route " + routeId + " is not set");
            }
            String destinationId = route.path("destination").asText();
            Egress.Target target = egress.target(destinationId);
            if (target == null || target.problem != null) {
                throw new ToolConfigurationError(target == null ? "destination " + destinationId + " is not declared" : target.problem);
            }
            long left = deadline.toEpochMilli() - System.currentTimeMillis();
            if (left <= 0) throw new PlatformError(PlatformError.Code.DEADLINE_EXCEEDED, "The invocation deadline has passed");
            long budget = Math.max(1, Math.min(timeoutMs, left));

            String method = route.path("method").asText();
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(target.base)).timeout(Duration.ofMillis(budget));
            headers.forEach(request::header);
            if (idempotencyHeader != null && !headers.containsKey(idempotencyHeader)) {
                boolean allowed = false;
                for (JsonNode h : route.path("headers")) allowed |= h.asText().equalsIgnoreCase(idempotencyHeader);
                if (allowed) request.header(idempotencyHeader, idempotencyValue);
            }
            String authorization = authorization(target);
            if (authorization != null) {
                JsonNode auth = auths.get(route.get("auth").asText());
                String header = auth.path("header").asText("");
                request.header(header.isEmpty() ? "Authorization" : header, authorization);
            }
            request.header("User-Agent", "toolgate-sdk-java");
            AtomicBoolean sent = new AtomicBoolean();
            if (body != null || method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                if (contentType != null) request.header("Content-Type", contentType);
                request.method(method, new Counted(body == null ? new byte[0] : body, sent));
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            long cap = route.path("maxResponseBytes").asLong(4_194_304);
            CompletableFuture<HttpResponse<byte[]>> future = target.client.sendAsync(request.build(), info -> new Capped(cap));
            context.onCancel(() -> future.cancel(true));
            HttpResponse<byte[]> response;
            try {
                response = future.get(budget, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new PlatformError(PlatformError.Code.CANCELLED, "The invocation was cancelled");
            } catch (TimeoutException e) {
                future.cancel(true);
                throw upstream("UPSTREAM_TIMEOUT", null, null, uncertain(method, body != null, sent), null);
            } catch (ExecutionException e) {
                throw failed(e.getCause(), method, sent);
            }
            int status = response.statusCode();
            String requestId = response.headers().firstValue("x-request-id").or(() -> response.headers().firstValue("request-id")).orElse(null);
            if (status < 200 || status > 299) {
                byte[] b = response.body();
                String text = new String(b, 0, Math.min(b.length, ERROR_BODY_BYTES), StandardCharsets.UTF_8);
                throw upstream(status >= 500 ? "UPSTREAM_5XX" : "UPSTREAM_4XX", status, requestId, false, text);
            }
            return new Response(status, response.headers().map(), response.body());
        }

        private URI uri(URI base) {
            Matcher m = VARIABLE.matcher(route.path("path").asText());
            StringBuilder path = new StringBuilder();
            while (m.find()) m.appendReplacement(path, Matcher.quoteReplacement(encode(variables.get(m.group(1)))));
            m.appendTail(path);
            StringBuilder q = new StringBuilder();
            for (Map.Entry<String, List<String>> e : query.entrySet()) {
                for (String v : e.getValue()) {
                    q.append(q.length() == 0 ? '?' : '&').append(encode(e.getKey())).append('=').append(encode(v));
                }
            }
            return URI.create(base + path.toString() + q);
        }

        /** The credential the route's auth adds, from local secrets (http.md §5); null for a route without auth. */
        private String authorization(Egress.Target target) {
            if (!route.hasNonNull("auth")) return null;
            JsonNode auth = auths.get(route.get("auth").asText());
            String id = auth.path("id").asText();
            String type = auth.path("type").asText();
            String secretName = auth.path("secret").asText("");
            JsonNode secret = secretName.isEmpty() || secrets.get(secretName) == null ? null : secrets.get(secretName).value();
            if (!secretName.isEmpty() && secret == null) {
                throw new ToolConfigurationError("secret " + secretName + " of auth " + id + " has no value; set TOOLGATE_SECRET_"
                        + secretName.toUpperCase(Locale.ROOT));
            }
            switch (type) {
                case "API_KEY" -> {
                    if (secret == null || !secret.isTextual()) throw new ToolConfigurationError("auth " + id + " needs a text secret");
                    return secret.asText();
                }
                case "BASIC" -> {
                    String pair;
                    if (secret != null && secret.isTextual() && secret.asText().contains(":")) pair = secret.asText();
                    else if (secret != null && secret.path("username").isTextual() && secret.path("password").isTextual()) {
                        pair = secret.get("username").asText() + ":" + secret.get("password").asText();
                    } else {
                        throw new ToolConfigurationError("auth " + id + " needs a secret {username, password} or user:password");
                    }
                    return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
                }
                case "OAUTH2_CLIENT_CREDENTIALS" -> {
                    return "Bearer " + token(auth, secret);
                }
                default -> throw new ToolConfigurationError("auth " + id + " of type " + type + " is not available in the standalone runtime");
            }
        }

        /** An OAuth 2.0 client-credentials token for the auth, cached until shortly before it expires. */
        private String token(JsonNode auth, JsonNode secret) {
            String id = auth.path("id").asText();
            if (secret == null || !secret.path("tokenUrl").isTextual() || !secret.path("clientId").isTextual() || !secret.path("clientSecret").isTextual()) {
                throw new ToolConfigurationError("auth " + id + " needs a secret {tokenUrl, clientId, clientSecret}");
            }
            String key = id + "\n" + secret;
            Egress.Token cached = egress.tokens.get(key);
            if (cached != null && cached.expiresAtMs() > System.currentTimeMillis()) {
                redactor.add(JSON.getNodeFactory().textNode(cached.value()));
                return cached.value();
            }
            URI url;
            try {
                url = URI.create(secret.get("tokenUrl").asText());
            } catch (IllegalArgumentException e) {
                throw new ToolConfigurationError("auth " + id + " tokenUrl is not a URL");
            }
            boolean plainAllowed = egress.mode != Config.Mode.PRODUCTION && url.getHost() != null && Egress.isLoopback(url.getHost());
            if (!"https".equals(url.getScheme()) && !("http".equals(url.getScheme()) && plainAllowed)) {
                throw new ToolConfigurationError("auth " + id + " tokenUrl must be https");
            }
            StringBuilder form = new StringBuilder("grant_type=client_credentials");
            List<String> scopes = new ArrayList<>();
            for (JsonNode s : auth.path("scopes")) scopes.add(s.asText());
            if (!scopes.isEmpty()) form.append("&scope=").append(encode(String.join(" ", scopes)));
            if (auth.hasNonNull("audience")) form.append("&audience=").append(encode(auth.get("audience").asText()));
            String client = encodeForm(secret.get("clientId").asText()) + ":" + encodeForm(secret.get("clientSecret").asText());
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofMillis(Math.max(1, Math.min(10_000, deadline.toEpochMilli() - System.currentTimeMillis()))))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(client.getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();
            HttpResponse<byte[]> response;
            try {
                response = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
                        .send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PlatformError(PlatformError.Code.CANCELLED, "The invocation was cancelled");
            } catch (HttpTimeoutException e) {
                throw upstream("UPSTREAM_TIMEOUT", null, null, false, null);
            } catch (IOException e) {
                throw upstream("UPSTREAM_CONNECTION", null, null, false, null);
            }
            if (response.statusCode() != 200) {
                throw upstream(response.statusCode() >= 500 ? "UPSTREAM_5XX" : "UPSTREAM_4XX", response.statusCode(), null, false, null);
            }
            JsonNode body;
            try {
                body = JSON.readTree(response.body());
            } catch (IOException e) {
                body = null;
            }
            if (body == null || !body.path("access_token").isTextual()) {
                throw new ToolConfigurationError("the token endpoint of auth " + id + " returned no access_token");
            }
            String token = body.get("access_token").asText();
            redactor.add(JSON.getNodeFactory().textNode(token));
            long lifetime = body.path("expires_in").canConvertToLong() ? body.get("expires_in").asLong() : 300;
            egress.tokens.put(key, new Egress.Token(token, System.currentTimeMillis() + Math.max(0, lifetime - 30) * 1000));
            return token;
        }

        private UpstreamError failed(Throwable cause, String method, AtomicBoolean sent) {
            if (cause instanceof TooLarge) {
                return upstream("UPSTREAM_CONNECTION", null, null, false, null, "The response on route " + routeId + " is larger than its maxResponseBytes");
            }
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException || cause instanceof SSLException) {
                return upstream("UPSTREAM_CONNECTION", null, null, false, null);
            }
            if (cause instanceof HttpTimeoutException) return upstream("UPSTREAM_TIMEOUT", null, null, uncertain(method, body != null, sent), null);
            return upstream("UPSTREAM_CONNECTION", null, null, uncertain(method, body != null, sent), null);
        }

        private UpstreamError upstream(String code, Integer status, String requestId, boolean effectUncertain, String text) {
            return new UpstreamError(code, routeId, status, requestId, effectUncertain, text);
        }

        private UpstreamError upstream(String code, Integer status, String requestId, boolean effectUncertain, String text, String why) {
            context.log().warn(why);
            return upstream(code, status, requestId, effectUncertain, text);
        }
    }

    /**
     * Whether a failed request may have had its effect: true once the request was fully handed to the connection. A
     * request with a body counts as sent when its last byte was written; one without counts as sent once connected.
     */
    private static boolean uncertain(String method, boolean hadBody, AtomicBoolean sent) {
        return sent.get() || !hadBody && !(method.equals("POST") || method.equals("PUT") || method.equals("PATCH"));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~");
    }

    private static String encodeForm(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** A complete response. */
    private record Response(int status, Map<String, List<String>> headers, byte[] body) implements RawResponse {}

    /** The response body exceeded the route's maxResponseBytes. */
    private static final class TooLarge extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** A request body that records when its last byte was handed over. */
    private static final class Counted implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher delegate;
        private final long length;
        private final AtomicBoolean sent;

        Counted(byte[] bytes, AtomicBoolean sent) {
            this.delegate = HttpRequest.BodyPublishers.ofByteArray(bytes);
            this.length = bytes.length;
            this.sent = sent;
        }

        @Override public long contentLength() { return length; }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            delegate.subscribe(new Flow.Subscriber<ByteBuffer>() {
                @Override public void onSubscribe(Flow.Subscription s) { subscriber.onSubscribe(s); }
                @Override public void onNext(ByteBuffer item) { subscriber.onNext(item); }
                @Override public void onError(Throwable t) { subscriber.onError(t); }

                @Override
                public void onComplete() {
                    sent.set(true);
                    subscriber.onComplete();
                }
            });
        }
    }

    /** Collects a response body and stops at the route's maxResponseBytes. */
    private static final class Capped implements HttpResponse.BodySubscriber<byte[]> {
        private final long cap;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        Capped(long cap) {
            this.cap = cap;
        }

        @Override public CompletionStage<byte[]> getBody() { return result; }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer b : items) {
                if (out.size() + (long) b.remaining() > cap) {
                    subscription.cancel();
                    result.completeExceptionally(new TooLarge());
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        }

        @Override public void onError(Throwable t) { result.completeExceptionally(t); }
        @Override public void onComplete() { result.complete(out.toByteArray()); }
    }
}
