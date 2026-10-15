// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.domain.Directory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Process-local acceleration only: every lookup must first read the tenant's durable version. */
final class DirectoryCache {
    static final long TTL_NANOS = Duration.ofMinutes(60).toNanos();
    private static final int MAX_TENANTS = 128;
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    record Version(long revision, long generation) {}
    static final class Entry {
        final Version version;
        final Directory directory;
        final long created;
        final long bytes;
        private String digest;
        Entry(Version version, Directory directory, long created) {
            this.version = version; this.directory = directory; this.created = created;
            bytes = 256 + directory.entries().values().stream()
                .mapToLong(e -> 256L + e.document().length() * 2L + e.references().size() * 128L).sum();
        }
        synchronized String digest(Supplier<String> compute) {
            if (digest == null) digest = compute.get();
            return digest;
        }
    }
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final LongSupplier clock;
    private long bytes;
    DirectoryCache() { this(System::nanoTime); }
    DirectoryCache(LongSupplier clock) { this.clock = clock; }
    Entry entry(Version version, Directory directory) { return new Entry(version, directory, clock.getAsLong()); }
    boolean fresh(Entry entry) { return clock.getAsLong() - entry.created < TTL_NANOS; }
    synchronized Entry get(String tenant, Version version) {
        var entry = entries.get(tenant);
        if (entry != null && !fresh(entry)) { invalidate(tenant); return null; }
        return entry != null && entry.version.equals(version) ? entry : null;
    }
    synchronized void put(String tenant, Entry entry) {
        if (entry.version.generation() < 0 || entry.bytes > MAX_BYTES || !fresh(entry)) return;
        var previous = entries.get(tenant);
        // An older transaction completing later cannot displace a newer committed generation.
        if (previous != null && previous.version.generation() > entry.version.generation()) return;
        invalidate(tenant); entries.put(tenant, entry); bytes += entry.bytes;
        while (entries.size() > MAX_TENANTS || bytes > MAX_BYTES) invalidate(entries.keySet().iterator().next());
    }
    synchronized void invalidate(String tenant) {
        var previous = entries.remove(tenant);
        if (previous != null) bytes -= previous.bytes;
    }
}
