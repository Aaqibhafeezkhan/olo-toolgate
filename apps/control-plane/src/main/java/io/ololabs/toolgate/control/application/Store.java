// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.util.function.Function;

/** Transaction port. Data, audit and idempotency must share a commit/rollback boundary. */
public interface Store {
    <T> T transaction(TenantId tenant, boolean write, Function<Session, T> work);
    record ConsoleDeviceRecord(String sessionId,String userId,String deviceId,String nonceDigest,String codeDigest,String csr,String fingerprint,long expiresAt,long verifiedAt){}
    interface Session {
        default EnterpriseStore enterprise() { throw Failure.unavailable(); }
        default FleetStore fleet() { throw Failure.unavailable(); }
        default BuilderStore builder() { throw Failure.unavailable(); }
        default McpStore mcp() { throw Failure.unavailable(); }
        default PackageStore packages() { throw Failure.unavailable(); }
        default RunEventStore runEvents() { throw Failure.unavailable(); }
        Directory load();
        /** Digest of this tenant's current directory; adapters may reuse a version-checked immutable snapshot. */
        default String adoptionDigest(TenantId tenant, Codec codec) { return EndpointAdoptions.digest(codec, tenant, load()); }
        /** Immutable setup identity, separate from editable roles and identity bindings. */
        default String bootstrapAdminUserId() { return null; }
        default void protectBootstrapAdmin(String userId) { throw Failure.unavailable(); }
        default ConsoleDeviceRecord consoleDevice(String sessionId){return null;}
        default void saveConsoleDevice(ConsoleDeviceRecord record){throw Failure.unavailable();}
        boolean used(io.ololabs.toolgate.control.domain.Ids.RecordId id);
        void save(Directory before, Directory after);
        default void observeIdentity(io.ololabs.toolgate.contracts.ControlIdentityBinding identity){throw Failure.unavailable();}
        Reply replay(String actor, String key, String digest);
        void remember(String actor, String key, String digest, Reply reply);
        void audit(String actor, String operation, String target, long revision, String requestId, String digest);
        String auditPage(long after, int limit);
        long bundleSequence();
        BundleRecord bundle(long sequence);
        void publishBundle(BundleRecord bundle);
        long approvalClock(long now);
        EnrollmentRecord enrollment(String id);
        EnrollmentRecord enrollmentCode(String codeDigest);
        void saveEnrollment(EnrollmentRecord enrollment);
        long pendingEnrollments(long now);
        java.util.List<EnrollmentRecord> enrollments(long now);
        void pruneEnrollments(long now);
        void deleteEnrollments(String deviceId);
        EndpointRecord endpoint(String deviceId);
        EndpointRecord endpointKey(String fingerprint);
        void saveEndpoint(EndpointRecord endpoint);
        default EndpointConfiguration endpointConfiguration(String deviceId) { throw Failure.unavailable(); }
        default void saveEndpointConfiguration(EndpointConfiguration configuration) { throw Failure.unavailable(); }
        /** Current tenant settings document, or null before the first save. */
        default String serverSettings() { throw Failure.unavailable(); }
        default void saveServerSettings(long revision, String document) { throw Failure.unavailable(); }
    }
    /** Immutable signed wire bytes and compiler bytes, committed with audit/replay. */
    record BundleRecord(long sequence, String document, String policy, long directoryRevision) {}
    record Reply(int status, String body, long revision) {}
    /** Device codes are hashed; CSR and certificate data are public key material. */
    record EnrollmentRecord(String id, String codeDigest, String deviceDigest, String document,
                            String csr, String userId, String certificate, long expiresAt, long lastPoll) {}
    record EndpointRecord(String id, String fingerprint, String document, String csr,
                          String reportDigest, String acknowledgment) {}
    record EndpointConfiguration(String deviceId, long sourceRevision, String document, String acknowledgedDigest, String localTools) {}
}
