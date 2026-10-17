// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** The operator's OCI Distribution registry (ADR 026). Everything is addressed by digest; nothing resolves a tag. */
public interface OciRegistry {
    record Manifest(String mediaType, byte[] body) {}
    /** The registry host (and port) devices pull from, as written in image references. */
    String host();
    Manifest manifest(String repository, String digest);
    byte[] blob(String repository, String digest, int maxBytes);
    boolean hasBlob(String repository, String digest);
    /** Makes a blob of {@code from} available in {@code repository} without downloading it through Control. */
    void mount(String repository, String digest, String from);
    void putBlob(String repository, String digest, byte[] bytes);
    void putManifest(String repository, String digest, String mediaType, byte[] bytes);
}
