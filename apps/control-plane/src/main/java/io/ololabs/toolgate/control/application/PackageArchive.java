// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.packagearchive.PackageRejected;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

/** Control's view of the shared `.tgpkg` validator in backend-libs/package-archive (plan.md §7.2, §7.6). A rejected
 *  package is a validation failure (413 when it is too large), and the manifest is read again with Control's codec so
 *  the rest of Control keeps working with Jackson. */
public final class PackageArchive {
    public static final String CHECKSUMS = io.ololabs.toolgate.packagearchive.PackageArchive.CHECKSUMS,
        MANIFEST = io.ololabs.toolgate.packagearchive.PackageArchive.MANIFEST;
    public static final int MAX_PACKAGE_BYTES = io.ololabs.toolgate.packagearchive.PackageArchive.MAX_PACKAGE_BYTES;

    /** A package that passed every check. */
    public record Verified(Map<String, byte[]> files, JsonNode manifest, String packageDigest, String descriptorSetDigest) {}

    private PackageArchive() {}

    public static Verified validate(byte[] data, Function<String, JsonNode> json) {
        io.ololabs.toolgate.packagearchive.PackageArchive.Verified verified;
        try { verified = io.ololabs.toolgate.packagearchive.PackageArchive.validate(data); }
        catch (PackageRejected rejected) {
            if (rejected.status() == 413) throw new Failure(ErrorCode.VALIDATION, 413, "Package too large");
            throw Failure.validation();
        }
        JsonNode manifest = json.apply(new String(verified.manifestBytes(), StandardCharsets.UTF_8));
        return new Verified(verified.files(), manifest, verified.packageDigest(), verified.descriptorSetDigest());
    }
}
