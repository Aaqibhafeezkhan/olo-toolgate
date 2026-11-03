// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** Consent metadata and reservations share the tenant writer transaction with effect authorization. */
public interface EffectConsentStore {
    record Pending(String consentId,String operationId,String digest,String document,long notBefore,long expiresAt){}
    record Approval(String reviewer,long authorizationEpoch,long approvedAt){}
    record Reservation(String consentId,long slotIndex,String effectId,String requestDigest){}
    Pending byId(String consentId);
    Pending forOperation(String operationId);
    void save(EffectConsentPreviews.Snapshot snapshot);
    Approval approval(String consentId);
    boolean revoked(String consentId);
    /** Caller independently authenticates the reviewer and checks current operation scope. */
    void approve(String consentId,String previewDigest,String reviewer,long epoch,long now);
    void revoke(String consentId,String reviewer,long now);
    /** Caller reconstructs mapped intent independently; the effect must already be AUTHORIZED in this transaction. */
    Reservation reserve(String consentId,long slotIndex,String operationId,String routeId,String mappedDigest,
        String effectId,String requestDigest,long epoch,long now);
}
