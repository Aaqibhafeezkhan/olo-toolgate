// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** Immutable descriptor mirror. Implementations bound origin, bytes, time and concurrency. */
public interface ArtifactStore {
    byte[] download(String digest,long expectedBytes);
    /** Stores exact release bytes under their digest, once; a store that only reads an external mirror refuses. */
    default void mirror(String digest,byte[] bytes){throw Failure.unavailable();}
}
