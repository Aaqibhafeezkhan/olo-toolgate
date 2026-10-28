// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.ControlIdentityBinding;
import io.ololabs.toolgate.contracts.ControlUser;
import io.ololabs.toolgate.control.application.DirectoryService;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.UserCredentials;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserCredentialsTest {
    private static final Ids.TenantId TENANT = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant");
    private static final String OLO = "https://olo.example/backend", EXCHANGE = "toolgate-token-exchange";
    @TempDir Path temp;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    private final ContractCodec codec = new ContractCodec();
    private final DirectoryService.Actor backend = new DirectoryService.Actor(TENANT, "b".repeat(64), false);
    private final KeyPair oloKey = rsa(), credentialKey = rsa();

    @Test void enabledUserReceivesCredentialBoundToSessionEpochAndRevocationAdvancesIt() throws Exception {
        try (var source = SqliteState.openPooled(temp.resolve("exchange.sqlite"))) {
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec);
            var credentials = credentials(store);
            // First verified access registers the user disabled; nothing is issued until an administrator enables it.
            var pending = assertThrows(Failure.class, () -> exchange(credentials, assertion("user-1", "olo-session-assertion+jwt", "olo-1")));
            assertEquals(403, pending.status());
            String userId = "user-" + DirectoryService.digest(TENANT.value() + "\n" + OLO + "\nuser-1").substring(0, 40);
            enable(store, userId);

            var issued = exchange(credentials, assertion("user-1", "olo-session-assertion+jwt", "olo-1"));
            assertTrue(issued.expiresInSeconds() > 0 && issued.expiresInSeconds() <= 120);
            var claims = verify(issued.accessToken());
            assertEquals(userId, claims.get("sub").asText());
            assertEquals(TENANT.value(), claims.get("tid").asText());
            assertEquals("binding-olo", claims.get("bid").asText());
            assertEquals("toolgate-control", claims.get("iss").asText());
            assertEquals("toolgate-gateway", claims.get("aud").asText());
            assertEquals(1, claims.get("sep").asLong());

            now.addAndGet(5_000);
            credentials.revoke(backend, "user-1");
            assertEquals(2, binding(store).sessionEpoch());
            // An assertion minted before the revocation is refused; a fresh one carries the new epoch.
            var stale = assertion("user-1", "olo-session-assertion+jwt", "olo-1", now.get() / 1000 - 2);
            assertEquals(401, assertThrows(Failure.class, () -> exchange(credentials, stale)).status());
            assertEquals(2, verify(exchange(credentials, assertion("user-1", "olo-session-assertion+jwt", "olo-1")).accessToken()).get("sep").asLong());
        }
    }

    @Test void refusesForgedOrMisdirectedAssertions() throws Exception {
        try (var source = SqliteState.openPooled(temp.resolve("refusals.sqlite"))) {
            var credentials = credentials(new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec));
            assertRefused("invalid_grant", () -> exchange(credentials, assertion("user-1", "JWT", "olo-1")));
            assertRefused("invalid_grant", () -> exchange(credentials, assertion("user-1", "olo-session-assertion+jwt", "other-kid")));
            var good = assertion("user-1", "olo-session-assertion+jwt", "olo-1");
            var tampered = good.substring(0, good.lastIndexOf('.') + 1) + "A" + good.substring(good.lastIndexOf('.') + 2);
            assertRefused("invalid_grant", () -> exchange(credentials, tampered));
            var unsigned = encode("{\"alg\":\"none\",\"typ\":\"olo-session-assertion+jwt\",\"kid\":\"olo-1\"}") + "." + good.split("\\.")[1] + ".AA";
            assertRefused("invalid_grant", () -> exchange(credentials, unsigned));
            assertRefused("unsupported_grant_type", () -> credentials.exchange(backend, "password", good, UserCredentials.JWT_TOKEN_TYPE, null, null));
            assertRefused("invalid_target", () -> credentials.exchange(backend, UserCredentials.GRANT_TYPE, good, UserCredentials.JWT_TOKEN_TYPE, "elsewhere", null));
            var signedByOther = sign(rsa(), "{\"alg\":\"RS256\",\"typ\":\"olo-session-assertion+jwt\",\"kid\":\"olo-1\"}", good.split("\\.")[1]);
            assertRefused("invalid_grant", () -> exchange(credentials, signedByOther));
        }
    }

    private UserCredentials credentials(PostgresStore store) {
        var settings = new UserCredentials.Settings("toolgate-control", "toolgate-gateway", 120, "binding-olo", OLO, EXCHANGE, "olo-1", oloKey.getPublic());
        return new UserCredentials(store, codec, settings, UserCredentialBootstrap.signer((RSAPrivateCrtKey) credentialKey.getPrivate(), "credential-1"), clock);
    }

    private UserCredentials.Issued exchange(UserCredentials credentials, String assertion) {
        return credentials.exchange(backend, UserCredentials.GRANT_TYPE, assertion, UserCredentials.JWT_TOKEN_TYPE, "toolgate-gateway", null);
    }

    private String assertion(String subject, String typ, String kid) throws Exception { return assertion(subject, typ, kid, now.get() / 1000); }

    private String assertion(String subject, String typ, String kid, long iat) throws Exception {
        String payload = "{\"iss\":\"" + OLO + "\",\"aud\":\"" + EXCHANGE + "\",\"sub\":\"" + subject + "\",\"iat\":" + iat + ",\"exp\":" + (iat + 60) + "}";
        return sign(oloKey, "{\"alg\":\"RS256\",\"typ\":\"" + typ + "\",\"kid\":\"" + kid + "\"}", encode(payload));
    }

    private static String sign(KeyPair key, String header, String encodedPayload) throws Exception {
        String input = encode(header) + "." + encodedPayload;
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private JsonNode verify(String token) throws Exception {
        String[] parts = token.split("\\.");
        assertEquals("{\"alg\":\"RS256\",\"typ\":\"toolgate-user-credential+jws\",\"kid\":\"credential-1\"}", new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(credentialKey.getPublic());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])));
        return (JsonNode) codec.value(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    private void enable(PostgresStore store, String userId) {
        store.transaction(TENANT, true, tx -> {
            var before = tx.load(); var entries = new HashMap<>(before.entries());
            var id = Ids.Kind.USER.id(userId); var user = codec.model(entries.get(id).document(), ControlUser.class);
            entries.put(id, codec.entry(Ids.Kind.USER, codec.json(new ControlUser(user.id(), user.name(), true, user.revision() + 1))));
            tx.save(before, new Directory(before.revision() + 1, entries)); return null;
        });
    }

    private ControlIdentityBinding binding(PostgresStore store) {
        return store.transaction(TENANT, false, tx -> tx.load().entries().values().stream().filter(e -> e.id().kind() == Ids.Kind.IDENTITY_BINDING)
                .map(e -> codec.model(e.document(), ControlIdentityBinding.class)).findFirst().orElseThrow());
    }

    private static void assertRefused(String error, org.junit.jupiter.api.function.Executable call) {
        assertEquals(error, assertThrows(UserCredentials.Refused.class, call).error());
    }

    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }

    private static KeyPair rsa() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
