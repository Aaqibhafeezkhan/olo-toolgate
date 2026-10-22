// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import static org.junit.jupiter.api.Assertions.*;
import io.ololabs.toolgate.contracts.ControlUser;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedDirectoryCacheTest {
    @TempDir Path temp;
    private static final byte[] KEY = new byte[32];
    private static final Ids.TenantId TENANT = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant-a");
    private static final ContractCodec CODEC = new ContractCodec();

    private static Directory directory(String name) {
        var entry = CODEC.entry(Ids.Kind.USER, CODEC.json(new ControlUser("user", name, true, 1L)));
        return new Directory(1, Map.of(entry.id(), entry));
    }
    private static SharedDirectoryCache shared(Endpoint endpoint) {
        return new SharedDirectoryCache(endpoint.client("localhost", null), CODEC, "test-deployment", KEY, 3600);
    }

    @Test void encryptedValuesBindTenantVersionNamespaceKeyAndExpiry() throws Exception {
        var now = new AtomicLong(1000);
        var cache = new SharedDirectoryCache(null, CODEC, "production", KEY, 60, now::get);
        var version = new DirectoryCache.Version(1, 2);
        var directory = directory("Private directory");
        String key = cache.cacheKey(TENANT.value(), version);
        byte[] value = cache.encode(key, directory);
        assertFalse(new String(value, StandardCharsets.UTF_8).contains("Private directory"));
        assertEquals(directory, cache.decode(key, version, value));
        assertThrows(java.security.GeneralSecurityException.class,
            () -> cache.decode(cache.cacheKey("other-tenant", version), version, value));
        assertThrows(java.security.GeneralSecurityException.class,
            () -> cache.decode(cache.cacheKey(TENANT.value(), new DirectoryCache.Version(1, 3)), version, value));
        var other = new SharedDirectoryCache(null, CODEC, "other-database", KEY, 60, now::get);
        assertThrows(java.security.GeneralSecurityException.class,
            () -> other.decode(other.cacheKey(TENANT.value(), version), version, value));
        byte[] rotated = KEY.clone(); rotated[0] = 1;
        var wrongKey = new SharedDirectoryCache(null, CODEC, "production", rotated, 60, now::get);
        assertThrows(java.security.GeneralSecurityException.class, () -> wrongKey.decode(key, version, value));
        byte[] corrupt = value.clone(); corrupt[corrupt.length - 1] ^= 1;
        assertThrows(java.security.GeneralSecurityException.class, () -> cache.decode(key, version, corrupt));
        now.addAndGet(60000);
        assertThrows(IOException.class, () -> cache.decode(key, version, value));
    }

    @Test void replicasReuseCommittedSnapshotsAndGenerationsInvalidateWithoutDeletingOldKeys() throws Exception {
        try (var endpoint = new Endpoint(null); var pool = SqliteState.openPooled(temp.resolve("replicas.sqlite"))) {
            var reads = new AtomicInteger();
            var source = recordingSource(pool, reads);
            var first = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), CODEC, new DirectoryCache(shared(endpoint)));
            var second = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), CODEC, new DirectoryCache(shared(endpoint)));
            update(first, "First");
            var before = first.transaction(TENANT, false, tx -> tx.load());
            int databaseReads = reads.get(), sets = endpoint.sets.get();
            assertEquals(before, second.transaction(TENANT, false, tx -> tx.load()));
            assertEquals(databaseReads, reads.get(), "Replica must use the external entry rather than scan directory rows");
            assertEquals(sets, endpoint.sets.get(), "Cache hits must not refresh remote expiry");
            assertEquals(3600, endpoint.lastTtl.get());
            update(second, "Updated");
            var after = first.transaction(TENANT, false, tx -> tx.load());
            assertNotEquals(before, after);
            assertTrue(after.entries().values().iterator().next().document().contains("Updated"));
            assertTrue(reads.get() > databaseReads);
            var third = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), CODEC, new DirectoryCache(shared(endpoint)));
            assertEquals(after, third.transaction(TENANT, false, tx -> tx.load()));
            // Identity observations can alter records without advancing configuration revision.
            try (var c = pool.getConnection(); var s = c.prepareStatement("UPDATE control_records SET revision=3,document=? WHERE tenant_id=?")) {
                s.setString(1, CODEC.json(new ControlUser("user", "Observed", false, 3L))); s.setString(2, TENANT.value()); s.executeUpdate();
            }
            var observed = second.transaction(TENANT, false, tx -> tx.load());
            assertEquals(after.revision(), observed.revision());
            assertFalse(observed.entries().values().iterator().next().enabled());
            assertNotEquals(after, observed);
        }
    }

    @Test void rollbackAndReadYourWritesNeverPublishUncommittedDirectoryData() throws Exception {
        try (var endpoint = new Endpoint(null); var pool = SqliteState.openPooled(temp.resolve("rollback.sqlite"))) {
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(pool), CODEC, new DirectoryCache(shared(endpoint)));
            update(store, "Committed"); store.transaction(TENANT, false, tx -> tx.load());
            int sets = endpoint.sets.get();
            assertThrows(IllegalStateException.class, () -> store.transaction(TENANT, true, tx -> {
                var before = tx.load(); tx.save(before, new Directory(before.revision() + 1, directory("Rolled back").entries()));
                assertTrue(tx.load().entries().values().iterator().next().document().contains("Rolled back"));
                throw new IllegalStateException();
            }));
            assertEquals(sets, endpoint.sets.get());
            var replica = new PostgresStore(pool, CODEC, new DirectoryCache(shared(endpoint)));
            assertTrue(replica.transaction(TENANT, false, tx -> tx.load()).entries().values().iterator().next().document().contains("Committed"));
        }
    }

    @Test void corruptOrUnavailableCacheFallsBackToDatabase() throws Exception {
        try (var endpoint = new Endpoint(null); var pool = SqliteState.openPooled(temp.resolve("fallback.sqlite"))) {
            var first = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(pool), CODEC, new DirectoryCache(shared(endpoint)));
            update(first, "Committed"); var expected = first.transaction(TENANT, false, tx -> tx.load());
            endpoint.values.replaceAll((key, value) -> new byte[40]);
            assertEquals(expected, new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(pool), CODEC, new DirectoryCache(shared(endpoint))).transaction(TENANT, false, tx -> tx.load()));
            endpoint.reject = true;
            assertEquals(expected, new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(pool), CODEC, new DirectoryCache(shared(endpoint))).transaction(TENANT, false, tx -> tx.load()));
        }
    }

    @Test void transportAuthenticatesBoundsValuesAndUsesTotalDeadline() throws Exception {
        try (var endpoint = new Endpoint(null)) {
            var client = endpoint.client("localhost", null);
            byte[] binary = {0, 13, 10, (byte) 255}; client.set("key", binary, 12);
            assertArrayEquals(binary, client.get("key")); assertNull(client.get("absent"));
            assertTrue(endpoint.auths.get() >= 3); assertEquals(12, endpoint.lastTtl.get());
            endpoint.reply = "$2097153\r\n";
            assertThrows(IOException.class, () -> client.get("key"));
            endpoint.reply = "$-2\r\n";
            assertThrows(IOException.class, () -> endpoint.client("localhost", null).get("key"));
            endpoint.reply = "$4\r\n"; // Endpoint waits instead of sending the promised bulk bytes.
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> new RedisCacheClient("localhost", endpoint.port(), "default", new byte[] {1}, null, 100).get("key"));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        }
    }

    @Test void tlsVerifiesCertificateTrustAndEndpointHostname() throws Exception {
        var tls = tls();
        try (var endpoint = new Endpoint(tls)) {
            var client = endpoint.client("localhost", tls);
            client.set("tls", new byte[] {7}, 60); assertArrayEquals(new byte[] {7}, client.get("tls"));
            assertThrows(IOException.class, () -> endpoint.client("127.0.0.1", tls).get("tls"));
            assertThrows(IOException.class, () -> endpoint.client("localhost", SSLContext.getDefault()).get("tls"));
        }
    }

    @Test void localDefaultNeedsNoExternalSecretsAndInvalidRedisSettingsFailEarly() {
        assertNotNull(DirectoryCacheConfig.create(config(Map.of()), CODEC));
        for (var overrides : List.of(Map.of("mode", "unknown"), Map.of("mode", "redis"),
                Map.of("mode", "redis", "host", "redis://user:secret@host"), Map.of("mode", "redis", "tls", "false"),
                Map.of("mode", "redis", "timeout-ms", "5000"), Map.of("mode", "redis", "namespace", "unsafe space"))) {
            var error = assertThrows(IllegalArgumentException.class, () -> DirectoryCacheConfig.create(config(overrides), CODEC));
            assertFalse(error.getMessage().contains("secret@"));
        }
    }

    @Test void optionalRealRedisEndpointRoundTripsBinaryValues() throws Exception {
        String port = System.getenv("CONTROL_TEST_CACHE_PORT");
        org.junit.jupiter.api.Assumptions.assumeTrue(port != null, "Requires isolated Redis/Valkey endpoint");
        var client = new RedisCacheClient("localhost", Integer.parseInt(port), "default",
            System.getenv("CONTROL_TEST_CACHE_PASSWORD").getBytes(StandardCharsets.UTF_8), null, 1000);
        String key = "toolgate-test:" + UUID.randomUUID();
        byte[] value = {0, (byte) 255, 13, 10}; client.set(key, value, 1); assertArrayEquals(value, client.get(key));
        try (var pool = SqliteState.openPooled(temp.resolve("real-redis.sqlite"))) {
            var scans = new AtomicInteger(); var source = recordingSource(pool, scans);
            String namespace = "test-" + UUID.randomUUID();
            var cache = new SharedDirectoryCache(client, CODEC, namespace, KEY, 60);
            var first = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), CODEC, new DirectoryCache(cache));
            update(first, "Real shared cache"); var expected = first.transaction(TENANT, false, tx -> tx.load());
            int before = scans.get();
            var second = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), CODEC, new DirectoryCache(new SharedDirectoryCache(client, CODEC, namespace, KEY, 60)));
            assertEquals(expected, second.transaction(TENANT, false, tx -> tx.load()));
            assertEquals(before, scans.get());
        }
        Thread.sleep(1100); assertNull(client.get(key));
    }

    @Test void databaseFailureCannotUseAnAvailableSharedCacheAsAuthority() throws Exception {
        var pool = SqliteState.openPooled(temp.resolve("database-down.sqlite"));
        try (var endpoint = new Endpoint(null)) {
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(pool), CODEC, new DirectoryCache(shared(endpoint)));
            update(store, "Committed"); store.transaction(TENANT, false, tx -> tx.load());
            assertFalse(endpoint.values.isEmpty()); pool.close();
            var replica = new PostgresStore(pool, CODEC, new DirectoryCache(shared(endpoint)));
            assertThrows(io.ololabs.toolgate.control.application.Failure.class, () -> replica.transaction(TENANT, false, tx -> tx.load()));
        } finally { pool.close(); }
    }

    private static org.eclipse.microprofile.config.Config config(Map<String, String> settings) {
        return (org.eclipse.microprofile.config.Config) java.lang.reflect.Proxy.newProxyInstance(
            SharedDirectoryCacheTest.class.getClassLoader(), new Class<?>[] {org.eclipse.microprofile.config.Config.class},
            (proxy, method, args) -> {
                if (!method.getName().equals("getOptionalValue")) throw new UnsupportedOperationException();
                String key = ((String) args[0]).replace("toolgate.control.cache.", "");
                return Optional.ofNullable(settings.get(key));
            });
    }
    private static void update(PostgresStore store, String name) {
        store.transaction(TENANT, true, tx -> {
            var before = tx.load(); tx.save(before, new Directory(before.revision() + 1, directory(name).entries())); return null;
        });
    }
    private static javax.sql.DataSource recordingSource(javax.sql.DataSource source, AtomicInteger reads) {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(SharedDirectoryCacheTest.class.getClassLoader(),
            new Class<?>[] {javax.sql.DataSource.class}, (proxy, method, args) -> {
                try {
                    var result = method.invoke(source, args);
                    if (!(result instanceof java.sql.Connection connection)) return result;
                    return java.lang.reflect.Proxy.newProxyInstance(SharedDirectoryCacheTest.class.getClassLoader(),
                        new Class<?>[] {java.sql.Connection.class}, (p, m, a) -> {
                            if (m.getName().equals("prepareStatement") && ((String) a[0]).startsWith("SELECT kind,document")) reads.incrementAndGet();
                            try { return m.invoke(connection, a); }
                            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        });
                } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
    }

    private static SSLContext tls() throws Exception {
        var keys = java.security.KeyPairGenerator.getInstance("RSA"); keys.initialize(2048); var key = keys.generateKeyPair();
        var name = new org.bouncycastle.asn1.x500.X500Name("CN=localhost"); long now = System.currentTimeMillis();
        var certificate = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(name, java.math.BigInteger.ONE,
            new Date(now - 1000), new Date(now + 600000), name, key.getPublic());
        certificate.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
            new org.bouncycastle.asn1.x509.GeneralNames(new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName, "localhost")));
        var cert = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(certificate.build(
            new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(key.getPrivate())));
        var store = java.security.KeyStore.getInstance("PKCS12"); store.load(null);
        store.setKeyEntry("server", key.getPrivate(), new char[0], new java.security.cert.Certificate[] {cert});
        var km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()); km.init(store, new char[0]);
        var tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); tm.init(store);
        var context = SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(), tm.getTrustManagers(), null); return context;
    }

    /** Independent RESP endpoint exercises the real socket protocol without requiring Docker for unit tests. */
    private static final class Endpoint implements AutoCloseable {
        final ServerSocket server;
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final Map<String, byte[]> values = new ConcurrentHashMap<>();
        final AtomicInteger auths = new AtomicInteger(), sets = new AtomicInteger(), lastTtl = new AtomicInteger();
        volatile boolean reject;
        volatile String reply;
        Endpoint(SSLContext tls) throws IOException {
            server = tls == null ? new ServerSocket() : tls.getServerSocketFactory().createServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            workers.submit(() -> {
                while (!server.isClosed()) {
                    try { var socket = server.accept(); sockets.add(socket); workers.submit(() -> serve(socket)); }
                    catch (IOException closed) { break; }
                }
            });
        }
        int port() { return server.getLocalPort(); }
        RedisCacheClient client(String host, SSLContext tls) { return new RedisCacheClient(host, port(), "default", new byte[] {1}, tls, 2000); }
        private void serve(Socket socket) {
            try (socket; var input = new DataInputStream(socket.getInputStream()); var output = socket.getOutputStream()) {
                socket.setSoTimeout(3000);
                var auth = read(input);
                if (!Arrays.equals(auth.getFirst(), "AUTH".getBytes(StandardCharsets.UTF_8)) || auth.size() != 3) return;
                auths.incrementAndGet(); output.write((reject ? "-WRONGPASS\r\n" : "+OK\r\n").getBytes(StandardCharsets.UTF_8)); output.flush();
                if (reject) return;
                var args = read(input); String command = new String(args.getFirst(), StandardCharsets.UTF_8);
                String key = new String(args.get(1), StandardCharsets.UTF_8);
                if (command.equals("SET")) {
                    if (!new String(args.get(3), StandardCharsets.UTF_8).equals("EX")) return;
                    values.put(key, args.get(2)); sets.incrementAndGet(); lastTtl.set(Integer.parseInt(new String(args.get(4), StandardCharsets.UTF_8)));
                    output.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
                } else if (reply != null) {
                    output.write(reply.getBytes(StandardCharsets.UTF_8)); output.flush(); input.read(); return;
                } else {
                    byte[] value = values.get(key);
                    output.write((value == null ? "$-1\r\n" : "$" + value.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                    if (value != null) { output.write(value); output.write("\r\n".getBytes(StandardCharsets.UTF_8)); }
                }
                output.flush();
            } catch (IOException expectedDisconnect) {
                // Timeout and failed TLS tests deliberately disconnect without a response.
            } finally { sockets.remove(socket); }
        }
        private static List<byte[]> read(DataInputStream input) throws IOException {
            String header = line(input);
            if (!header.startsWith("*")) throw new IOException();
            int count = Integer.parseInt(header.substring(1)); var result = new ArrayList<byte[]>();
            for (int i = 0; i < count; i++) {
                int size = Integer.parseInt(line(input).substring(1));
                result.add(input.readNBytes(size)); if (!line(input).isEmpty()) throw new IOException();
            }
            return result;
        }
        private static String line(InputStream input) throws IOException {
            var buffer = new ByteArrayOutputStream();
            for (int next; (next = input.read()) != '\r';) { if (next < 0) throw new EOFException(); buffer.write(next); }
            if (input.read() != '\n') throw new IOException(); return buffer.toString(StandardCharsets.UTF_8);
        }
        @Override public void close() throws IOException {
            server.close(); for (var socket : sockets) socket.close(); workers.close();
        }
    }
}
