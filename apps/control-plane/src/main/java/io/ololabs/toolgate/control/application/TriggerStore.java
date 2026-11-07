// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.List;

/**
 * Tenant-scoped trigger records (ADR 058) sharing the enclosing Store transaction, audit and idempotency commit. Rows are
 * never deleted; a removed assignment or instance keeps its row with a tombstone state.
 */
public interface TriggerStore {
    /** ASSIGNMENT by name; INSTANCE by {@code <assignment uid>/<executor id>}; DESIRED by executor id; LEASE by instance id. */
    enum Kind { ASSIGNMENT, INSTANCE, DESIRED, LEASE }
    record Row(String id,long revision,String document) {}
    Row get(Kind kind,String id);
    /** Rows whose id starts with {@code prefix} and sorts after {@code after}, in id order. */
    List<Row> page(Kind kind,String prefix,String after,int limit);
    long count(Kind kind);
    /** Expected zero inserts; positive compares and swaps, and the new revision must be expected + 1. */
    void save(Kind kind,Row row,long expectedRevision);
}
