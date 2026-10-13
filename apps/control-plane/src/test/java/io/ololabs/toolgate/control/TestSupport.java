// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.util.*;

/** Explicit reviewed group graph for storage/enrollment tests; never a runtime authorization bypass. */
final class TestSupport {
    static DirectoryService.Actor seed(Store store,Codec codec,Ids.TenantId tenant,String user) {
        store.transaction(tenant,true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());GroupGraph.defaults(entries,codec);
            put(entries,codec,Ids.Kind.USER,new ControlUser(user,"Reviewed administrator",true,1L));GroupGraph.addDefault(entries,codec,Ids.Kind.USER,user);
            var all=new GroupSelection(List.of(),true);var conditions=new EnterpriseConditions(0L,0L,List.of(),List.of(),List.of(),List.of(),false,false,null,null);
            var ceiling=new EnterpriseScope(all,all,List.of(),true,Arrays.stream(ResourceKind.values()).map(kind->new EnterpriseResourceRule(kind,"",EnterpriseResourceMatch.ANY)).toList(),conditions);
            var actions=List.of("read","create","update","delete","enable","disable","recover","manage-role","grant","import","export","audit","simulate","publish","policy","attest","rotate-credential","approve-operation","approve-device","revoke-device","deploy","build","approve-configuration");
            var rules=Arrays.stream(EnterpriseGroupType.values()).map(kind->new EnterpriseManagementRule(actions,kind,all,List.of(ceiling),conditions,null)).toList();
            put(entries,codec,Ids.Kind.ROLE,new ControlRole("test-recovery-role","Reviewed recovery role",true,1L,UserRole.SUPER_ADMIN,EnterpriseRoleType.MANAGEMENT,rules));
            put(entries,codec,Ids.Kind.TEAM,new ControlTeam("test-recovery-team","Reviewed administrators",true,1L,List.of(user),List.of("test-recovery-role")));
            tx.save(before,new Directory(before.revision()+1,entries));return null;});
        return new DirectoryService(store,codec,512,1048576).portalActor(tenant,"a".repeat(64),user);
    }
    static void put(Map<Ids.RecordId,Directory.Entry> entries,Codec codec,Ids.Kind kind,Object model){var entry=codec.entry(kind,codec.json(model));entries.put(entry.id(),entry);}
}
