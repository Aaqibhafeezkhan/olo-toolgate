// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import java.util.*;

/** Management role classification is derived only from enabled Team membership. */
public final class RoleResolver {
    private final Codec codec;
    public RoleResolver(Codec codec) { this.codec=codec; }
    public record Access(UserRole portalRole,boolean managed,List<EnterpriseManagementRule> grants) {
        public boolean approver() { return grants.stream().anyMatch(r->r.actions().contains("operation.approve")); }
    }
    public Access resolve(Directory directory,ControlUser user) {
        var grants=new ArrayList<EnterpriseManagementRule>();var portal=UserRole.BASIC;
        if(!user.enabled())return new Access(portal,false,List.of()); var roles=new TreeSet<String>();
        for(var id:GroupGraph.memberships(directory,codec,Kind.USER,user.id(),true))roles.addAll(GroupGraph.roles(directory.entries().get(Kind.TEAM.id(id)),codec));
        for(var id:roles) {
            var entry=directory.entries().get(Kind.ROLE.id(id));if(entry==null||!entry.enabled())continue;
            var role=codec.model(entry.document(),ControlRole.class);if(role.roleType()!=EnterpriseRoleType.MANAGEMENT)continue;
            if(role.portalRole().ordinal()>portal.ordinal())portal=role.portalRole(); grants.addAll(role.managementRules());
        }
        return new Access(portal,!roles.isEmpty(),List.copyOf(grants));
    }
}
