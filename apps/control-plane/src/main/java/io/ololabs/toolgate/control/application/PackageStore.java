// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** Tenant-transactional server packages (platform-requirements.md §7, §8): sealed uploads by digest, the service profile and globals
 *  documents Tool Hosts fetch by digest, one current deployment per package id, and registered Tool Hosts. */
public interface PackageStore {
    /** document is the PackageVersion; content the exact `.tgpkg` bytes. */
    record Version(String digest,String packageId,String version,long uploadedAt,String document) {}
    Version version(String digest);
    java.util.List<Version> versions(int limit);
    byte[] content(String digest);
    void saveVersion(Version version,byte[] content);
    /** A canonical JSON document under its canonical digest. */
    String document(String digest);
    void saveDocument(String digest,String document);
    /** document is the PackageDeployment. */
    String deployment(String packageId);
    java.util.List<String> deployments();
    void saveDeployment(String packageId,String document);
    void deleteDeployment(String packageId);
    /** document is the ToolHostRegistration of the device. */
    String toolHost(String deviceId);
    void saveToolHost(String deviceId,String document);
    /** A dispatch the Gateway handed to Control for a Tool Host that fetches its calls. document holds the Dispatch and
     *  its ToolHostDispatchStatus as {dispatch, status}. */
    record Relayed(String invocationId,String hostId,String state,long expiresAt,String document) {}
    Relayed relayed(String invocationId);
    /** The oldest queued, unexpired dispatch for this host. */
    Relayed nextRelayed(String hostId,long now);
    void saveRelayed(Relayed relayed);
    /** The latest ProvisioningStatus of a deployment profile; generation grows with every change. */
    record Provisioning(String profileId,long generation,String profileDigest,String document) {}
    Provisioning provisioning(String profileId);
    /** Every applied profile's latest status, by profile id. */
    java.util.List<Provisioning> provisionings();
    /** Inserts generation 1, or replaces exactly the expected previous generation. */
    void saveProvisioning(Provisioning provisioning);
}
