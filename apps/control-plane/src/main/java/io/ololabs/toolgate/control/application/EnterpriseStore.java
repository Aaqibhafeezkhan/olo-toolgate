// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import java.util.List;

/** All methods run inside the tenant transaction which also commits audit and directory state. */
public interface EnterpriseStore {
    record Operation(String id,String state,boolean legacy,long revision) {}
    record Attempt(String id,String operationId,String state,Long consumedAt,long issuedAt,long expiresAt,long revision,long evidenceCount) {}
    default Operation operation(String id){throw Failure.unavailable();}
    default List<Attempt> attempts(String operationId,long now){throw Failure.unavailable();}
    default void saveRecoveryScope(String attempt,RecoveryAdmissionPolicy.Scope scope){throw Failure.unavailable();}
    default RecoveryAdmissionPolicy.Scope recoveryScope(String attempt){throw Failure.unavailable();}
    record Recovery(String operationId,long fromRevision,long toRevision,String proof,long createdAt,String document) {}
    /** Called only after fresh domain authorization in the same tenant writer transaction. */
    default EnterpriseInvocation rebind(EnterpriseInvocation previous,OperationRebindingPolicy.Proof proof,long now){throw Failure.unavailable();}
    default Recovery recordRecovery(EnterpriseInvocation previous,OperationRebindingPolicy.Proof proof,long now){throw Failure.unavailable();}
    default boolean hasRecovery(String operation){throw Failure.unavailable();}
    default boolean recoveryCoversAttempt(String operation,String attempt){throw Failure.unavailable();}
    default List<Recovery> recoveries(String operation,int limit){throw Failure.unavailable();}
    /** Trusted journal projection only. Never deserialize these recovery facts from a caller or executor. */
    default OperationRebindingPolicy.Facts recoveryFacts(String invocation){throw Failure.unavailable();}
    record BusinessKey(String tool,String namespace,String identity,String hash,String fingerprint,String agent,String user,String invocation,long retainUntil,Long tombstonedAt,String source,Long dedupeExpiresAt,String outcome) {
        public BusinessKey(String tool,String namespace,String identity,String hash,String fingerprint,String agent,String user,String invocation,long retainUntil,Long tombstonedAt) {
            this(tool,namespace,identity,hash,fingerprint,agent,user,invocation,retainUntil,tombstonedAt,"HEADER",null,null);
        }
        public BusinessKey(String tool,String namespace,String identity,String hash,String fingerprint,String agent,String user,String invocation,long retainUntil,Long tombstonedAt,String source,Long dedupeExpiresAt) {
            this(tool,namespace,identity,hash,fingerprint,agent,user,invocation,retainUntil,tombstonedAt,source,dedupeExpiresAt,null);
        }
    }
    default BusinessKey businessKey(String tool,String namespace,String identity,String hash){throw Failure.unavailable();}
    default void saveBusinessKey(BusinessKey value,long now){throw Failure.unavailable();}
    default BusinessKey businessKeyForInvocation(String invocation){throw Failure.unavailable();}
    default void replaceBusinessKey(BusinessKey previous,BusinessKey next,long now){throw Failure.unavailable();}
    default List<BusinessKey> businessKeyHistory(String tool,String namespace,String identity,String hash,int limit){throw Failure.unavailable();}
    default int tombstoneExpiredBusinessKeys(long now,int limit){throw Failure.unavailable();}
    default boolean businessInvocation(String invocation){throw Failure.unavailable();}
    record ExecutionLease(String invocationId,String fencingToken,String deviceId,long expiresAt) {}
    default ExecutionLease executionLease(String invocationId){throw Failure.unavailable();}
    default void saveExecutionLease(ExecutionLease lease){throw Failure.unavailable();}
    /** Immutable original report bytes, retained independently of current execution authority. */
    default void saveExecutionEvidence(String invocationId,String fence,String digest,String document,long recordedAt){throw Failure.unavailable();}
    /** Merges the caller's declared correlation into the invocation's first Insights fact (ADR 031); a no-op when Insights is off. */
    default void insightsContext(String invocationId,io.ololabs.toolgate.contracts.v2.ExecutionContext context){}
    Long userCredentialDeadline(String id);
    void userCredentialDeadline(String id,long expiresAt);
    boolean operationCancelled(String id);
    void cancelOperation(String id);
    long authorizationEpoch();
    EnterpriseOperationalStatus operationalStatus(long now);
    EnterpriseInvocation invocation(String id);
    List<EnterpriseInvocation> invocations(String after,int limit);
    List<EnterpriseInvocation> expiredInvocations(long now,int limit);
    EnterpriseReconciliation reconciliation(String id);
    void saveReconciliation(EnterpriseReconciliation value,long expectedRevision);
    void saveInvocation(EnterpriseInvocation invocation,long createdAt,long expectedRevision);
    long invocationsSince(long since);
    EnterpriseApproval approval(String id);
    List<EnterpriseApproval> approvals(String after,int limit);
    void saveApproval(EnterpriseApproval approval,long expectedRevision);
    Nonce nonce(String id);
    /** Non-execution capability nonce (for the existing service permit boundary). */
    void reserveNonce(Nonce nonce);
    default void reserveExecutionNonce(Nonce nonce){throw Failure.unavailable();}
    void consumeNonce(String id,long now);
    boolean credentialUsed(String sha256);
    void rememberCredential(String sha256);
    boolean recoveryApplied(String digest);
    void rememberRecovery(String digest);
    EnterpriseConfigurationChange configuration(String id);
    List<EnterpriseConfigurationChange> configurations(String after,int limit);
    void saveConfiguration(EnterpriseConfigurationChange change,long expectedRevision);
    Secret secret(String name);
    List<Secret> secrets();
    void saveSecret(Secret secret);
    void acknowledge(EnterpriseAdoptionStatus status);
    List<EnterpriseAdoptionStatus> adoptions();
    void published(long revision,long now);
    String creator(String kind,String id);
    void rememberCreator(String kind,String id,String user);
    long budgetUsage(String budget,long since,String excludingInvocation);
    void reserveBudget(String budget,String invocation,long now);
    record Secret(String name,String toolGroupId,String deviceGroupId,long version,long keyVersion,String cipher) {}
    record VaultKey(long version,String state,String providerReference,String wrappedEncryptionKey,String wrappedRetryKey) {}
    VaultKey vaultKey(long version);
    VaultKey currentVaultKey();
    List<VaultKey> vaultKeys();
    void insertVaultKey(VaultKey key);
    void updateVaultKey(VaultKey key,String expectedState);
    record Nonce(String id,String invocationId,String evaluationDigest,long authorizationEpoch,long issuedAt,long expiresAt,Long consumedAt) {}
}
