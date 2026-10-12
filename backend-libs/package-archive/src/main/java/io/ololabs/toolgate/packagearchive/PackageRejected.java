// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.packagearchive;

/** A package that fails a rule. {@link #status} is the HTTP status an upload answers with: 413 for a package over
 *  {@link PackageArchive#MAX_PACKAGE_BYTES}, 400 for everything else. The message says which rule failed; it never
 *  contains package bytes beyond an entry name. */
public final class PackageRejected extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    public PackageRejected(int status, String reason) { super(reason, null, false, false); this.status = status; }
    PackageRejected(String reason) { this(400, reason); }

    public int status() { return status; }
}
