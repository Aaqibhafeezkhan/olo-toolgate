// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.SignedTriggerDesiredDocument;
import io.ololabs.toolgate.contracts.v2.TriggerDesiredDocument;

/**
 * The signed desired trigger state (ADR 058, plan 8.1): a compact JWS (RS256) by the organization deployment key, the
 * same key and keyring as the fleet desired document but its own {@code typ}, so neither verifies as the other.
 */
public interface TriggerCrypto {
    String TYPE = "toolgate-trigger-desired+jws";
    SignedTriggerDesiredDocument desired(TriggerDesiredDocument document);
    /** Verifies the signature against the organization keyring and the type, and returns the exact signed document. */
    TriggerDesiredDocument verify(SignedTriggerDesiredDocument signed);
}
