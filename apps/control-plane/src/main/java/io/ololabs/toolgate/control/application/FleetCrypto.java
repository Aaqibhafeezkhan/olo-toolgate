// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;

/** Separate release and organization domains; exact verified bytes remain the artifact identity. */
public interface FleetCrypto {
    record Verified<T>(T model, byte[] bytes) {
        public Verified { bytes=bytes.clone(); }
        @Override public byte[] bytes(){return bytes.clone();}
    }
    Verified<FleetPackageDocument> release(FleetSignedDocument signed);
    FleetArtifactGrantClaims grant(FleetSignedDocument signed);
    FleetSignedDocument desired(FleetDesiredDocument document);
    FleetSignedDocument grant(FleetArtifactGrantClaims document);
}
