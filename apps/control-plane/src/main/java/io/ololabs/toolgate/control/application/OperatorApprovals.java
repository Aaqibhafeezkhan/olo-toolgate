// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.List;

/** Offline operator approval is distinct from TLS identity, human review and execution authority. */
public interface OperatorApprovals {
    record Approved(BreakGlassApprovalClaims claims,List<String> operators) {
        public Approved {operators=List.copyOf(operators);}
    }
    Approved verify(Ids.TenantId tenant,String requestId,BreakGlassRequest request,long now);
}
