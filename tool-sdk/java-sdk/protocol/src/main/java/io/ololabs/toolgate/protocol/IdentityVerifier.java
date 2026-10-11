// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

/**
 * Adapter A4 (tool-sdk/spec/protocol-layer.md §3): turns a bearer credential into a {@link VerifiedIdentity} before
 * any dispatch. No SDK bearer support is used.
 */
public interface IdentityVerifier {
    /**
     * Verifies one bearer credential.
     *
     * @throws Rejected when the credential is not acceptable; the reason is for local diagnostics only
     */
    VerifiedIdentity verify(String bearer);

    /** A refused credential. The transport answers 401 whatever the reason. */
    final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Rejected(String reason) {
            super(reason, null, false, false);
        }
    }
}
