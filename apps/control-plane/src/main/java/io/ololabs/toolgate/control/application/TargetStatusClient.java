// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.*;
import java.util.Map;

/** Read-only outbound port. A response alone cannot mutate a ledger or authorize execution. */
public interface TargetStatusClient {
    record Request(ServiceProfile profile,String routeId,String targetKey,Map<String,String> credentials) {
        public Request { credentials=Map.copyOf(credentials); }
        @Override public String toString(){return "TargetStatusRequest[route="+routeId+", credentials=<redacted>, key=<redacted>]";}
    }
    TargetEffectStatusReply lookup(Request request);
}
