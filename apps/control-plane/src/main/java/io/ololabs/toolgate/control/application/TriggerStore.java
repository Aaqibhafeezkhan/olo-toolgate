// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.List;

/**
 * Tenant-scoped trigger records (ADR 058) sharing the enclosing Store transaction, audit and idempotency commit. A removed
 * assignment or instance keeps its row with a tombstone state. Only retention purges delete, and only {@link #PURGEABLE}
 * kinds (plan 12.1).
 */
public interface TriggerStore {
    /**
     * ASSIGNMENT by name; INSTANCE by {@code <assignment uid>/<executor id>}; DESIRED by executor id; LEASE by
     * {@code <executor id>/<instance id>}. Ingest (module 5): EVENT by {@code <instance id>/<epoch>/<outboxSeq>} (both
     * zero-padded to 16 digits, so a page is in delivery order); KEY, the {@code (source, id)} dedupe index, by
     * {@code <instance id>/<sha256 of the id>}; EPOCH by {@code <instance id>/<epoch>}; CHECKPOINT by instance id.
     * Router (module 10): OUTBOX by the EVENT id, written with the event and removed once routed; ROUTER {@code state}
     * (the next stream sequence); LOG by the 16-digit stream sequence (the cursor space); DELIVERY by
     * {@code <consumer>/<sequence>}; DUE by {@code <due time>/<delivery id>} for push consumers; EXPIRY by
     * {@code <purge time>/<kind>/<id>}; SETTINGS {@code tenant}. Subscriptions (module 12): SUBSCRIPTION by name,
     * CURSOR by subscription uid, TICKET by the ticket's SHA-256.
     */
    enum Kind { ASSIGNMENT, INSTANCE, DESIRED, LEASE, EVENT, KEY, EPOCH, CHECKPOINT,
        OUTBOX, ROUTER, LOG, DELIVERY, DUE, EXPIRY, SETTINGS, SUBSCRIPTION, CURSOR, TICKET }
    java.util.Set<Kind> PURGEABLE = java.util.EnumSet.of(Kind.OUTBOX, Kind.LOG, Kind.EVENT, Kind.KEY, Kind.DELIVERY, Kind.DUE, Kind.EXPIRY, Kind.CURSOR, Kind.TICKET);
    record Row(String id,long revision,String document) {}
    Row get(Kind kind,String id);
    /** Rows whose id starts with {@code prefix} and sorts after {@code after}, in id order. */
    List<Row> page(Kind kind,String prefix,String after,int limit);
    long count(Kind kind);
    /** Expected zero inserts; positive compares and swaps, and the new revision must be expected + 1. */
    void save(Kind kind,Row row,long expectedRevision);
    /** Deletes a {@link #PURGEABLE} row at exactly this revision; false when it is already gone. */
    boolean delete(Kind kind,String id,long expectedRevision);
}
