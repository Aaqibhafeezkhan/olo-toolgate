// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** Tenant-scoped immutable desired revisions and a separately activated publication pointer. */
public interface WorkflowProfileStore {
    record Revision(String profileId, String digest, String document, long keyVersion, String state, String reviewedBy) {}
    record Publication(String profileId, String digest, long generation) {}
    Revision revision(String profileId, String digest);
    void insert(Revision revision);
    void review(String profileId, String digest, String reviewer);
    Publication publication(String profileId);
    void publish(Publication publication, long expectedGeneration);
    /** A resource can be owned by one profile for its lifetime; omission never releases ownership. */
    record Ownership(String profileId, String definitionDigest) {}
    Ownership owner(String kind, String id);
    void own(String kind, String id, String profileId, String definitionDigest);
}
