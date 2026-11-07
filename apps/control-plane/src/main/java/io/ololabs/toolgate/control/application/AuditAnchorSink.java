// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/**
 * ADR 061 external anchor publication. A sink is outside Control's database trust boundary
 * and must be write-once: an existing object is never replaced or deleted through this port.
 *
 * <p>Implementations: {@code LocalAnchorSink} (append-only directory, for development and for a
 * separately administered mount). Specified, not yet shipped: a customer object-store bucket with
 * compliance-mode object lock and retention (publish = conditional create, If-None-Match: *),
 * and a transparency log (publish = append, receipt = signed inclusion promise/proof).
 */
public interface AuditAnchorSink {
    /** Where an anchor was durably published; {@code contentSha256} is the hex SHA-256 of the object bytes. */
    record Receipt(String sink, String location, String contentSha256) {}

    /** Refusal to replace an existing object with different content. */
    final class Conflict extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Conflict(String name) { super("Anchor object already exists with different content: " + name, null, false, false); }
    }

    /** Stable short identifier recorded with each anchor (for example {@code local}, {@code s3-object-lock}). */
    String kind();

    /**
     * Durably creates {@code name}. Publishing identical bytes again returns the existing receipt;
     * different bytes raise {@link Conflict}. Implementations must not overwrite under any race.
     */
    Receipt publish(String name, byte[] content);

    /** The published bytes, or null when absent. */
    byte[] read(String name);
}
