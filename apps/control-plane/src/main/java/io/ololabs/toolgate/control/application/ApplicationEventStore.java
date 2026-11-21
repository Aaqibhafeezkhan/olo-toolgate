// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.*;
import java.util.List;

/** Tenant-scoped notifications and leased webhook cursors in the enclosing transaction. */
public interface ApplicationEventStore {
    void append(ApplicationEventSource source, String id, String key, ApplicationEventKind kind, long now, String data);
    List<ApplicationEvent> page(ApplicationEventSource source, String id, long after, int limit);
    record Subscription(String id, String userId, String principalId, String identityDigest, String sourceType, String sourceId,
                        String sourceKey, String eventKinds, String endpoint, long expiresAt, long keyVersion, String secret,
                        String state, long cursor, int attempts, long nextAttempt, String leaseId, long leaseUntil, String requestDigest) {}
    Subscription get(String id);
    List<Subscription> owned(String userId, String after, int limit);
    void insert(Subscription subscription);
    void prune(long before);
    void state(String id, String state);
    List<Subscription> due(long now, int limit);
    boolean lease(String id, String leaseId, long now);
    void acknowledge(String id, String leaseId, long cursor, long nextAttempt);
    void retry(String id, String leaseId, int attempts, long nextAttempt, String state);
}
