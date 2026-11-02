// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** Internal port: issuance/dispatch require current execution authority; late outcomes require the bound service identity. */
public interface EffectStore {
    record Binding(String effectId,String operationId,String attemptId,String routeId,String requestDigest,
                   String deviceId,String brokerId,String handlerId,String authNonce,long expiresAt,long outcomeDeadline) {}
    record Generation(Binding binding,long generation,String state,long revision,String consumer,String claimToken) {}
    /** False on replay: observing a previous authorization is not permission to dispatch it again. */
    record Authorization(Generation generation,boolean newAuthority) {}
    /** Exact reconstructed request, retained without raw admitted arguments. */
    record Intent(String effectId,String requestDigest,String argumentsDigest,String profileDigest,String classification,String document,long recordedAt) {}
    default void recordIntent(Intent intent){throw Failure.unavailable();}
    default Intent intent(String effectId){throw Failure.unavailable();}
    Generation byNonce(String nonce);
    default Generation current(String effectId){throw Failure.unavailable();}
    Authorization authorize(Binding binding,long now);
    Generation release(String nonce,String device,String broker,String handler,long now);
    Generation sending(String nonce,String device,String broker,String handler,long now);
    Authorization consume(String nonce,String requestDigest,String consumer,String claimToken,long now);
    Generation targetOutcome(String nonce,String consumer,String claimToken,String outcome,String resultDigest,long now);
    /** Broker observations are retained but cannot settle consumed, fenced work. */
    void observe(String nonce,String outcome,String resultDigest,long now);
    void abandon(String operationId);
    int expire(long now);
}
