// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.List;

/** Insert-only revocation journal under the serialized tenant transaction. */
public interface KillStore {
    record Component(String kind,String id){}
    void registerGateway(String id,long at);
    boolean registeredGateway(String id);
    List<Component> components();
    long retainedCount();
    long archivedCount();
    record EmergencyRequest(String id,String digest,String event,String certificate,long notAfter,long at){}
    EmergencyRequest emergency(String id);
    void recordEmergency(EmergencyRequest request);
    record Proposal(String id,String requester,String kind,long epoch,String digest,String document,long createdAt,long expiresAt){}
    record Closure(String state,String event,long at){}
    Proposal proposal(String id);
    List<Proposal> proposals(String after,int limit);
    void propose(Proposal proposal);
    List<io.ololabs.toolgate.contracts.v2.KillReview> reviews(String id);
    void review(String id,io.ololabs.toolgate.contracts.v2.KillReview review);
    Closure closure(String id);
    void close(String id,Closure closure);
    record Event(String id,String issuer,long counter,String kind,String scope,String value,String mode,long epoch,long issuedAt,String document){}
    record Scope(String operation,String attempt,String device,KillPolicy.Subject subject){}
    Scope scope(String attempt);
    void bindScope(Scope scope);
    record Acknowledgement(String kind,String component,long epoch,long at,String path){}
    long nextCounter(String issuer);
    void append(Event event);
    Event byId(String id);
    List<Event> visible();
    List<Event> effective();
    void supersede(String kill,String recovery);
    void archive(String id,long at);
    void acknowledge(Acknowledgement value);
    List<Acknowledgement> acknowledgements();
    /** True only when every intervening epoch is accounted for by a committed kill/recovery. */
    boolean killOnlySince(long previous,long current);
}
