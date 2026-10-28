// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * RFC 8693 token exchange for platform users (OLO integration, M3.4).
 *
 * <p>A trusted platform backend, authenticated to Control with its own service role, exchanges a short-lived <em>session
 * assertion</em> it signed for a verified user, and receives a short-lived ToolGate <em>user credential</em> that the
 * Gateway accepts in {@code HUMAN} mode.
 * <ul>
 *   <li>The assertion is verified against one pinned public key with an exact header: no key URLs, no algorithm choice.</li>
 *   <li>The user is resolved by {@link IdentityIntake} from the verified {@code (iss, sub)}: an unknown user is registered
 *       disabled and must be enabled by an administrator, and assertions issued before the session cutoff are refused.</li>
 *   <li>The credential carries the user's current session epoch, so {@link #revoke} (which bumps the epoch) stops every
 *       outstanding credential for that user at Control's next authorization.</li>
 *   <li>The credential key is dedicated to this purpose; it never signs permits, bundles or identity tokens.</li>
 * </ul>
 */
public final class UserCredentials {

    public static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    public static final String JWT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt";
    public static final String ASSERTION_TYPE = "olo-session-assertion+jwt";
    public static final String CREDENTIAL_TYPE = "toolgate-user-credential+jws";
    public static final long MAX_LIFETIME_SECONDS = 300;
    static final long CLOCK_SKEW_SECONDS = 30;

    /**
     * @param issuer          {@code iss} of issued credentials; the Gateway pins it
     * @param audience        {@code aud} of issued credentials (the Gateway)
     * @param lifetimeSeconds credential lifetime, 1 to 300 seconds
     * @param bindingId       execution binding the credential is used with
     * @param subjectIssuer   {@code iss} the session assertion must carry (the platform backend)
     * @param subjectAudience {@code aud} the session assertion must carry (this exchange)
     * @param subjectKeyId    {@code kid} of the platform backend's assertion key
     * @param subjectKey      the platform backend's pinned RSA public key
     */
    public record Settings(String issuer, String audience, long lifetimeSeconds, String bindingId, String subjectIssuer,
                           String subjectAudience, String subjectKeyId, PublicKey subjectKey) {
        public Settings {
            Ids.valid(issuer);
            Ids.valid(audience);
            Ids.valid(bindingId);
            Ids.valid(subjectKeyId);
            Objects.requireNonNull(subjectKey, "subjectKey");
            if (subjectIssuer == null || subjectIssuer.isBlank() || subjectIssuer.length() > 512
                    || subjectAudience == null || subjectAudience.isBlank() || subjectAudience.length() > 512) {
                throw new IllegalArgumentException("Invalid assertion issuer or audience");
            }
            if (lifetimeSeconds < 1 || lifetimeSeconds > MAX_LIFETIME_SECONDS) {
                throw new IllegalArgumentException("User credential lifetime must be 1-300 seconds");
            }
        }
    }

    /** RS256 signing with the dedicated user-credential key. */
    public interface Signer {
        String keyId();

        /** The base64url signature of {@code signingInput}. */
        String sign(byte[] signingInput);
    }

    /** RFC 8693 §2.2.1 response fields. */
    public record Issued(String accessToken, long expiresInSeconds) {
    }

    /** A refusal that maps to an RFC 6749 §5.2 error code. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String error;

        Refused(String error, String description) {
            super(description);
            this.error = error;
        }

        public String error() {
            return error;
        }
    }

    private final Store store;
    private final Codec codec;
    private final Settings settings;
    private final Signer signer;
    private final Clock clock;
    private final String header;

    public UserCredentials(Store store, Codec codec, Settings settings, Signer signer, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.codec = Objects.requireNonNull(codec);
        this.settings = Objects.requireNonNull(settings);
        this.signer = Objects.requireNonNull(signer);
        this.clock = Objects.requireNonNull(clock);
        this.header = encode("{\"alg\":\"RS256\",\"typ\":\"" + CREDENTIAL_TYPE + "\",\"kid\":\"" + Ids.valid(signer.keyId()) + "\"}");
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Exchanges a session assertion for a user credential.
     *
     * @param client the authenticated platform backend (its service role was checked by the adapter)
     */
    public Issued exchange(DirectoryService.Actor client, String grantType, String subjectToken, String subjectTokenType,
                           String audience, String requestedTokenType) {
        if (!GRANT_TYPE.equals(grantType)) {
            throw new Refused("unsupported_grant_type", "grant_type must be token-exchange");
        }
        if (!JWT_TOKEN_TYPE.equals(subjectTokenType)) {
            throw new Refused("invalid_request", "subject_token_type must be " + JWT_TOKEN_TYPE);
        }
        if (requestedTokenType != null && !requestedTokenType.isBlank() && !JWT_TOKEN_TYPE.equals(requestedTokenType)) {
            throw new Refused("invalid_request", "requested_token_type must be " + JWT_TOKEN_TYPE);
        }
        if (audience != null && !audience.isBlank() && !settings.audience().equals(audience)) {
            throw new Refused("invalid_target", "audience is not served by this exchange");
        }

        long nowMs = clock.millis();
        long now = nowMs / 1000;
        JsonNode claims = verifiedAssertion(subjectToken);
        String issuer = text(claims, "iss");
        String subject = text(claims, "sub");
        long issuedAt = number(claims, "iat");
        long expiresAt = number(claims, "exp");
        if (!settings.subjectIssuer().equals(issuer) || !audienceContains(claims.get("aud"), settings.subjectAudience())) {
            throw new Refused("invalid_grant", "assertion issuer or audience mismatch");
        }
        if (subject == null || subject.isBlank() || subject.length() > 256
                || expiresAt <= now || issuedAt > now + CLOCK_SKEW_SECONDS || expiresAt - issuedAt > MAX_LIFETIME_SECONDS
                || issuedAt < now - MAX_LIFETIME_SECONDS) {
            throw new Refused("invalid_grant", "assertion subject or lifetime invalid");
        }

        // Resolves (iss, sub) to a ToolGate user; refuses assertions issued before the user's session cutoff.
        var identity = new IdentityIntake(store, codec).observe(client.tenant(), issuer, subject, null, null,
                Math.min(issuedAt, now) * 1000, expiresAt * 1000, nowMs);
        if (!identity.enabled()) {
            throw new Failure(ErrorCode.FORBIDDEN, 403, "User awaits administrator enablement");
        }

        long expires = Math.min(now + settings.lifetimeSeconds(), expiresAt);
        String jti = UUID.randomUUID().toString();
        var payload = codec.json(java.util.Map.of(
                "iss", settings.issuer(),
                "aud", settings.audience(),
                "sub", identity.userId(),
                "tid", client.tenant().value(),
                "sep", identity.sessionEpoch(),
                "bid", settings.bindingId(),
                "iat", now,
                "exp", expires,
                "jti", jti));
        String signingInput = header + "." + encode(payload);
        String token = signingInput + "." + signer.sign(signingInput.getBytes(StandardCharsets.US_ASCII));

        store.transaction(client.tenant(), true, tx -> {
            tx.audit(client.id(), "USER_CREDENTIAL_ISSUED", "users:" + identity.userId(), tx.load().revision(), jti,
                    DirectoryService.digest(token));
            return null;
        });
        return new Issued(token, expires - now);
    }

    /**
     * Revokes every session of the platform user {@code subject}: bumps the identity binding's session epoch and sets the
     * session cutoff to now. Outstanding credentials are refused at Control's next authorization, and assertions issued
     * before now are refused by {@link #exchange}.
     */
    public void revoke(DirectoryService.Actor client, String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 256) {
            throw new Refused("invalid_request", "subject is required");
        }
        new IdentityIntake(store, codec).revokeSessions(client.tenant(), settings.subjectIssuer(), subject, clock.millis(),
                client.id());
    }

    private JsonNode verifiedAssertion(String token) {
        if (token == null || token.length() > 16384 || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            throw new Refused("invalid_grant", "malformed subject_token");
        }
        String[] parts = token.split("\\.");
        JsonNode head = json(parts[0]);
        Set<String> fields = new java.util.HashSet<>();
        head.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("alg", "typ", "kid")) || !"RS256".equals(text(head, "alg"))
                || !ASSERTION_TYPE.equals(text(head, "typ")) || !settings.subjectKeyId().equals(text(head, "kid"))) {
            throw new Refused("invalid_grant", "subject_token header not accepted");
        }
        try {
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(settings.subjectKey());
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new Refused("invalid_grant", "subject_token signature invalid");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new Refused("invalid_grant", "subject_token signature invalid");
        }
        return json(parts[1]);
    }

    private JsonNode json(String part) {
        try {
            Object value = codec.value(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8));
            if (value instanceof JsonNode node && node.isObject()) {
                return node;
            }
        } catch (RuntimeException ignored) {
            // Falls through to the refusal below.
        }
        throw new Refused("invalid_grant", "subject_token is not a JSON object");
    }

    private static boolean audienceContains(JsonNode aud, String expected) {
        if (aud == null) {
            return false;
        }
        if (aud.isTextual()) {
            return expected.equals(aud.asText());
        }
        if (aud.isArray()) {
            for (JsonNode value : aud) {
                if (value.isTextual() && expected.equals(value.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || !value.isIntegralNumber()) {
            throw new Refused("invalid_grant", "assertion claim " + field + " missing");
        }
        return value.asLong();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
