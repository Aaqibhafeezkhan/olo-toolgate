// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** Platform wrapping boundary. References select only operator-configured keys, never arbitrary URLs or paths. */
public interface VaultWrappingProvider {
    String currentReference();
    String wrap(String reference,byte[] plaintext,byte[] context);
    byte[] unwrap(String reference,String wrapped,byte[] context);
}
