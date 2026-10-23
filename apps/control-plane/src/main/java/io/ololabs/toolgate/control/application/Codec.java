// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.util.Map;

/** Shared contract conversion port. Untrusted data is validated before producing domain entries. */
public interface Codec {
    Directory.Entry entry(Kind kind, String document);
    Directory.Entry revision(Directory.Entry entry, long revision);
    Import input(String document, boolean yaml, TenantId tenant);
    String snapshot(TenantId tenant, Directory directory, boolean yaml);
    /** Snapshot with file-level fields (settings, requiredIdentities) that are never directory records. */
    String snapshot(TenantId tenant, Directory directory, boolean yaml, Map<String, Object> fileFields);
    /** Canonical JSON import request for an already validated import, as a reviewed change replays it. */
    String document(TenantId tenant, Import input);
    String json(Object value);
    Object value(String document);
    void validatePolicies(Directory directory);
    /** OLO installation guards (M3.5, M3.8); none unless configured. */
    default OloProtections protections() { return OloProtections.NONE; }
    /** Validate canonical schema before decoding a generated shared model. */
    <T> T model(String document, Class<T> type);
    record Import(Directory directory, boolean replace, boolean dryRun, io.ololabs.toolgate.contracts.v2.ControlServerSettingsPatch settings,
                  java.util.List<io.ololabs.toolgate.contracts.ControlRequiredIdentity> requiredIdentities, Map<String, io.ololabs.toolgate.contracts.ControlIdentityResolution> identityResolution, boolean removeUnlistedMemberships) {
        public Import(Directory directory, boolean replace, boolean dryRun) { this(directory, replace, dryRun, null, java.util.List.of(), Map.of(), false); }
    }
    default String result(boolean applied, long revision, java.util.List<Map<String, String>> changes) {
        return json(Map.of("applied", applied, "revision", revision, "changes", changes));
    }
}
