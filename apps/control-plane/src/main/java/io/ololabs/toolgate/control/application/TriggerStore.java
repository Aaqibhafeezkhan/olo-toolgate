// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.List;

/**
 * Tenant-scoped trigger records (ADR 058) sharing the enclosing Store transaction, audit and idempotency commit. Rows are
 * never deleted; a removed assignment or instance keeps its row with a tombstone state.
 */
public interface TriggerStore {
    /**
     * ASSIGNMENT by name; INSTANCE by {@code <assignment uid>/<executor id>}; DESIRED by executor id; LEASE by
     * {@code <executor id>/<instance id>}. Ingest (module 5): EVENT by {@code <instance id>/<epoch>/<outboxSeq>} (both
     * zero-padded to 16 digits, so a page is in delivery order); KEY, the {@code (source, id)} dedupe index, by
     * {@code <instance id>/<sha256 of the id>}; EPOCH by {@code <instance id>/<epoch>}; CHECKPOINT by instance id.
     */
    enum Kind { ASSIGNMENT, INSTANCE, DESIRED, LEASE, EVENT, KEY, EPOCH, CHECKPOINT }
    record Row(String id,long revision,String document) {}
    Row get(Kind kind,String id);
    /** Rows whose id starts with {@code prefix} and sorts after {@code after}, in id order. */
    List<Row> page(Kind kind,String prefix,String after,int limit);
    long count(Kind kind);
    /** Expected zero inserts; positive compares and swaps, and the new revision must be expected + 1. */
    void save(Kind kind,Row row,long expectedRevision);
}
