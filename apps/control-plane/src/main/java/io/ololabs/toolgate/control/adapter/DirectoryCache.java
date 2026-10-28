// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.domain.Directory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Local and optional shared acceleration: every lookup first reads the tenant's durable version. */
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
        private boolean sharedPublished;
        Entry(Version version, Directory directory, long created) {
            this.version = version; this.directory = directory; this.created = created;
            bytes = 256 + directory.entries().values().stream()
                .mapToLong(e -> 256L + e.document().length() * 2L + e.references().size() * 128L).sum();
        }
        synchronized String digest(Supplier<String> compute) {
            if (digest == null) digest = compute.get();
            return digest;
        }
        synchronized boolean publishShared() {
            if (sharedPublished) return false;
            sharedPublished = true; return true;
        }
    }
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final LongSupplier clock;
    private final SharedDirectoryCache shared;
    private long bytes;
    DirectoryCache() { this(System::nanoTime, null); }
    DirectoryCache(LongSupplier clock) { this(clock, null); }
    DirectoryCache(SharedDirectoryCache shared) { this(System::nanoTime, shared); }
    private DirectoryCache(LongSupplier clock, SharedDirectoryCache shared) { this.clock = clock; this.shared = shared; }
    Entry entry(Version version, Directory directory) { return new Entry(version, directory, clock.getAsLong()); }
    boolean fresh(Entry entry) { return clock.getAsLong() - entry.created < TTL_NANOS; }
    Entry get(String tenant, Version version) {
        var local = local(tenant, version);
        if (local != null || shared == null || version.generation() < 0) return local;
        var directory = shared.get(tenant, version);
        if (directory == null) return null;
        var loaded = entry(version, directory); loaded.publishShared();
        return loaded;
    }
    private synchronized Entry local(String tenant, Version version) {
        var entry = entries.get(tenant);
        if (entry != null && !fresh(entry)) { invalidate(tenant); return null; }
        return entry != null && entry.version.equals(version) ? entry : null;
    }
    void put(String tenant, Entry entry) {
        if (remember(tenant, entry) && shared != null && entry.publishShared()) shared.put(tenant, entry);
    }
    private synchronized boolean remember(String tenant, Entry entry) {
        if (entry.version.generation() < 0 || entry.bytes > MAX_BYTES || !fresh(entry)) return false;
        var previous = entries.get(tenant);
        // An older transaction completing later cannot displace a newer committed generation.
        if (previous != null && previous.version.generation() > entry.version.generation()) return false;
        invalidate(tenant); entries.put(tenant, entry); bytes += entry.bytes;
        while (entries.size() > MAX_TENANTS || bytes > MAX_BYTES) invalidate(entries.keySet().iterator().next());
        return true;
    }
    synchronized void invalidate(String tenant) {
        var previous = entries.remove(tenant);
        if (previous != null) bytes -= previous.bytes;
    }
}
