// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** Immutable content-addressed build outputs; callers must authorize every read. */
public interface BuilderArtifacts {
    String put(byte[] bytes) throws java.io.IOException;
    byte[] read(String digest) throws java.io.IOException;
}
