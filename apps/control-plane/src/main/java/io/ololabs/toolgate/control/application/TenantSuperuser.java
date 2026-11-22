// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import java.util.*;

/** Control derives live tenant human authority; workload and effect authority stay independent. */
public final class TenantSuperuser {
    private TenantSuperuser() {}

    public static EnterpriseScope scope() {
        var all = new GroupSelection(List.of(), true);
        var conditions = new EnterpriseConditions(0L, 0L, List.of(), List.of(), List.of(), List.of(), false, false, null, null);
        return new EnterpriseScope(all, all, List.of(), true, Arrays.stream(ResourceKind.values())
            .map(kind -> new EnterpriseResourceRule(kind, "", EnterpriseResourceMatch.ANY)).toList(), conditions);
    }

    public static List<ControlAccessGrant> grants(Directory directory, Codec codec, String user) {
        if (!new ManagementAccess(codec).superAdministrator(directory, user)) return List.of();
        var result = new ArrayList<ControlAccessGrant>();
        for (var team : GroupGraph.memberships(directory, codec, Kind.USER, user, true)) {
            for (var roleId : GroupGraph.roles(directory.entries().get(Kind.TEAM.id(team)), codec)) {
                var entry = directory.entries().get(Kind.ROLE.id(roleId));
                if (entry == null || !entry.enabled()) continue;
                var role = codec.model(entry.document(), ControlRole.class);
                if (role.roleType() != EnterpriseRoleType.MANAGEMENT || role.portalRole() != UserRole.SUPER_ADMIN) continue;
                for (var purpose : List.of(EnterpriseGrantPurpose.HUMAN, EnterpriseGrantPurpose.SECRET)) {
                    var id = "super-admin-" + DirectoryService.digest(codec.json(List.of(user, team, roleId, purpose.name()))).substring(0, 32);
                    result.add(new ControlAccessGrant(id, "Live tenant Super Admin", true, 1L, EnterpriseSourceType.TEAM,
                        team, purpose, scope()));
                }
            }
        }
        return List.copyOf(result);
    }
}
