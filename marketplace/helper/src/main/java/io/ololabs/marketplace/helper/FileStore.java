// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reads upload bytes by Drupal's internal file reference, so any helper replica can serve any request and the helper
 * keeps no files of its own (implementation prompt §6.2).
 *
 * <ul>
 *   <li>{@code s3://<bucket>/<key>}: a GET signed with AWS Signature Version 4 against {@code MARKETPLACE_S3_ENDPOINT}.
 *       Only the configured bucket is readable.</li>
 *   <li>{@code file:<relative path>}: only when {@code MARKETPLACE_HELPER_FILE_ROOT} is set (tests and the seed check),
 *       and only a regular file inside that root.</li>
 * </ul>
 */
public final class FileStore {
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final Config config;
    private final HttpClient http;

    public FileStore(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public byte[] read(String ref) {
        if (ref == null || ref.isBlank() || ref.length() > 1024) {
            throw new HelperException(400, "INVALID_REQUEST", "fileRef is missing or too long");
        }
        if (ref.startsWith("s3://")) {
            return readS3(ref.substring("s3://".length()));
        }
        if (ref.startsWith("file:") && config.fileRoot() != null) {
            return readFile(ref.substring("file:".length()));
        }
        throw new HelperException(400, "INVALID_REQUEST", "unsupported fileRef");
    }

    private byte[] readFile(String relative) {
        try {
            Path root = config.fileRoot().toRealPath();
            Path path = root.resolve(relative).normalize();
            if (path.startsWith(root) && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new HelperException(404, "NOT_FOUND", "fileRef does not exist");
            }
            if (!path.startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !path.toRealPath().startsWith(root)) {
                throw new HelperException(400, "INVALID_REQUEST", "fileRef is outside the file root");
            }
            if (Files.size(path) > config.maxPackageBytes()) {
                throw tooLarge();
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new HelperException(404, "NOT_FOUND", "fileRef cannot be read");
        }
    }

    private HelperException tooLarge() {
        return new HelperException(422, "SIZE", "package is larger than " + config.maxPackageBytes() / (1024 * 1024) + " MiB");
    }

    private byte[] readS3(String rest) {
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new HelperException(400, "INVALID_REQUEST", "fileRef must be s3://<bucket>/<key>");
        }
        String bucket = rest.substring(0, slash);
        String key = rest.substring(slash + 1);
        if (config.s3Endpoint() == null || !bucket.equals(config.s3Bucket())) {
            throw new HelperException(400, "INVALID_REQUEST", "fileRef names a bucket the helper does not read");
        }
        if (key.contains("..") || key.startsWith("/")) {
            throw new HelperException(400, "INVALID_REQUEST", "unsafe object key");
        }
        try {
            URI endpoint = URI.create(config.s3Endpoint());
            String path = "/" + encode(bucket) + "/" + encodePath(key);
            URI uri = URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority() + path);
            String host = endpoint.getPort() == -1 ? endpoint.getHost() : endpoint.getHost() + ":" + endpoint.getPort();
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            String amzDate = AMZ_DATE.format(now);
            String day = amzDate.substring(0, 8);
            String scope = day + "/" + config.s3Region() + "/s3/aws4_request";
            String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
            String canonicalRequest = "GET\n" + path + "\n\n"
                    + "host:" + host + "\n"
                    + "x-amz-content-sha256:" + EMPTY_SHA256 + "\n"
                    + "x-amz-date:" + amzDate + "\n\n"
                    + signedHeaders + "\n" + EMPTY_SHA256;
            String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
            byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + config.s3SecretKey()).getBytes(StandardCharsets.UTF_8), day),
                    config.s3Region()), "s3"), "aws4_request");
            String signature = HexFormat.of().formatHex(hmac(signingKey, toSign));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("x-amz-content-sha256", EMPTY_SHA256)
                    .header("x-amz-date", amzDate)
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + config.s3AccessKey() + "/" + scope
                            + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 404) {
                    throw new HelperException(404, "NOT_FOUND", "fileRef does not exist");
                }
                if (response.statusCode() != 200) {
                    throw new HelperException(502, "UNAVAILABLE", "object storage returned " + response.statusCode());
                }
                return readBounded(body);
            }
        } catch (IOException e) {
            throw new HelperException(502, "UNAVAILABLE", "object storage is unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HelperException(503, "UNAVAILABLE", "interrupted");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > config.maxPackageBytes()) {
                throw tooLarge();
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static String encodePath(String key) {
        StringBuilder out = new StringBuilder();
        for (String segment : key.split("/", -1)) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(encode(segment));
        }
        return out.toString();
    }

    private static String sha256Hex(String text) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, String data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
