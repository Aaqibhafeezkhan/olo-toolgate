// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** Tenant-scoped immutable desired revisions and a separately activated publication pointer. */
public interface WorkflowProfileStore {
    record Revision(String profileId, String digest, String document, long keyVersion, String state, String reviewedBy) {}
    record Publication(String profileId, String digest, long generation) {}
    Revision revision(String profileId, String digest);
    java.util.List<Revision> page(String afterProfileId, String afterDigest, int limit);
    void insert(Revision revision);
    void review(String profileId, String digest, String reviewer);
    Publication publication(String profileId);
    java.util.List<Publication> publications(String afterProfileId, int limit);
    void publish(Publication publication, long expectedGeneration);
    /** Compiler receipts are preparation evidence, never runtime authorization. */
    String graphBuild(String profileId, String digest, String workflowId);
    void graphBuild(String profileId, String digest, String workflowId, String document);
    record DevicePlacement(String profileId, String deviceId, long generation) {}
    DevicePlacement devicePlacement(String profileId);
    /** Immutable selection, committed before rollout; concurrent retries return the same selection. */
    DevicePlacement placeDevice(String profileId, String deviceId);
    /** A resource can be owned by one profile for its lifetime; omission never releases ownership. */
    record Ownership(String profileId, String definitionDigest) {}
    Ownership owner(String kind, String id);
    void own(String kind, String id, String profileId, String definitionDigest);
}
