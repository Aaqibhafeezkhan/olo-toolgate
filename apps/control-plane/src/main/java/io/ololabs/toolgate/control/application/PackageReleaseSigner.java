// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** The package-release authority (ADR 026): signs exact FleetPackageDocument bytes with a key separate from the
 *  organization fleet key. A release grants nothing; rollouts still need the organization signature. */
public interface PackageReleaseSigner {
    io.ololabs.toolgate.contracts.FleetSignedDocument sign(byte[] document);
}
