// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** A build is separate from tool invocation: it grants no install, activation or runtime authority. */
public interface SdkBuildWorker {
    record Outputs(byte[] packageBytes, byte[] sourceArchive) {
        public Outputs { packageBytes = packageBytes.clone(); sourceArchive = sourceArchive.clone(); }
        @Override public byte[] packageBytes() { return packageBytes.clone(); }
        @Override public byte[] sourceArchive() { return sourceArchive.clone(); }
    }
    Outputs build(byte[] canonicalProject) throws java.io.IOException, InterruptedException;
}
