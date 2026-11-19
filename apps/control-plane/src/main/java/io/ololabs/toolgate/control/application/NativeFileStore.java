// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.List;
import io.ololabs.toolgate.contracts.v2.*;

/** Tenant-transaction-owned native intents and protected target witnesses (ADR 075). */
public interface NativeFileStore {
    record Qualification(String id,NativeFileQualification value,boolean enabled) {}
    record Scope(String operation,String attempt,String qualification,boolean writeAccess) {}
    record Intent(String effect,String operation,String attempt,long ordinal,String digest,NativeFileIntent value) {}
    List<Qualification> qualifications(String device,long now);
    Qualification qualification(String id);
    void qualify(String id,NativeFileQualification value);
    void revoke(String id);
    Scope scope(String attempt);
    void admit(Scope value,String document);
    Intent intent(String effect);
    void begin(Intent value,long now);
    List<Intent> intents(String operation);
    List<NativeFileEffectReceipt> receipts(String effect);
    void receipt(NativeFileEffectReceipt value,String digest,long now);
    boolean closed(String attempt);
    void close(String attempt,String document,long now);
}
