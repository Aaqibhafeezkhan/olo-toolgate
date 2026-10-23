// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.util.*;

/** Deployment guards for an OLO platform installation. Empty sets disable both rules.
 *  <ul><li>M3.5: a reserved service principal (the OLO workers' own agent) acts only as itself; it is never a delegated
 *  agent nor a hop in a delegation chain, so user authority cannot be laundered through it.</li>
 *  <li>M3.8: a scope that reaches a protected tool group (explicitly or through the tool-group wildcard) names its actions
 *  and never uses the ANY resource selector.</li></ul> */
public record OloProtections(Set<String> reservedAgents,Set<String> protectedToolGroups) {
    public static final OloProtections NONE=new OloProtections(Set.of(),Set.of());
    public OloProtections { reservedAgents=Set.copyOf(reservedAgents);protectedToolGroups=Set.copyOf(protectedToolGroups); }
    public boolean reserved(String agentId) { return agentId!=null&&reservedAgents.contains(agentId); }
    public void workload(ControlWorkloadBinding b) {
        if(reserved(b.agentId())&&b.mode()!=RequestMode.SERVICE)throw new IllegalArgumentException("Reserved service principal is SERVICE only");
    }
    public void scope(EnterpriseScope s) {
        if(protectedToolGroups.isEmpty())return;
        boolean covers=s.toolGroups().all()||s.toolGroups().ids().stream().anyMatch(protectedToolGroups::contains);
        if(covers&&(s.allActions()||s.resources().stream().anyMatch(r->r.match()==EnterpriseResourceMatch.ANY)))
            throw new IllegalArgumentException("Protected tool groups require explicit actions and resources");
    }
}
