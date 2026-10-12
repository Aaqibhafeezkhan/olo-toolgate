// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the few metadata entries the helper needs from a package the CLI has already accepted, and enforces the
 * Marketplace's own expanded-size limit, which is lower than the CLI's.
 *
 * <p>Only {@code toolgate-package.json} and the optional {@code marketplace/} files are kept in memory; every other
 * entry is counted and discarded. Nothing is written to disk and nothing is executed.
 */
public final class PackageReader {
    public static final String MANIFEST = "toolgate-package.json";
    public static final String LISTING = "marketplace/listing.json";
    public static final String README = "marketplace/README.md";
    private static final Set<String> KEPT = Set.of(MANIFEST, LISTING, README);
    private static final int MAX_KEPT_BYTES = 4 * 1024 * 1024;

    private PackageReader() {
    }

    public static Map<String, byte[]> read(byte[] archive, long maxExpandedBytes) {
        Map<String, byte[]> kept = new HashMap<>();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                boolean keep = KEPT.contains(entry.getName());
                ByteArrayOutputStream out = keep ? new ByteArrayOutputStream() : null;
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    total += n;
                    if (total > maxExpandedBytes) {
                        throw new HelperException(422, "SIZE",
                                "package expands to more than " + maxExpandedBytes / (1024 * 1024) + " MiB");
                    }
                    if (keep) {
                        if (out.size() + n > MAX_KEPT_BYTES) {
                            throw new HelperException(422, "SIZE", entry.getName() + " is too large");
                        }
                        out.write(buffer, 0, n);
                    }
                }
                if (keep) {
                    kept.put(entry.getName(), out.toByteArray());
                }
            }
        } catch (IOException e) {
            throw new HelperException(422, "ARCHIVE", "cannot read the archive: " + e.getMessage());
        }
        if (!kept.containsKey(MANIFEST)) {
            throw new HelperException(422, "MANIFEST", "toolgate-package.json is missing");
        }
        return kept;
    }
}
