// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import java.util.List;

/** All methods run inside the tenant transaction which also commits audit and directory state. */
public interface EnterpriseStore {
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
    void reserveNonce(Nonce nonce);
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
    record Nonce(String id,String invocationId,String evaluationDigest,long authorizationEpoch,long expiresAt,Long consumedAt) {}
}
