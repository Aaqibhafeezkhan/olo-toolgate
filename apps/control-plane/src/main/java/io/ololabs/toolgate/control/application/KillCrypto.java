// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Fleet-custody signatures, with disjoint event and delivery types. Never execution authority. */
public interface KillCrypto {
    java.util.List<io.ololabs.toolgate.contracts.v2.KillTrustKey> publicKeys();
    String signEvent(ObjectNode unsigned);
    ObjectNode verifyEvent(String signature);
    String signPush(ObjectNode unsigned);
    ObjectNode verifyPush(String signature);
}
