// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The authority commit boundary protects setup identity, not just the last account count. */
final class AdministratorInvariantTest {
    @TempDir Path directory;
    private final ContractCodec codec=new ContractCodec();
    private final Ids.TenantId tenant=io.ololabs.toolgate.control.fixtures.Tenants.id("invariant");
    private PostgresStore store(boolean protect){
        var store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve(UUID.randomUUID()+".sqlite"))),codec);
        TestSupport.seed(store,codec,tenant,"setup");
        if(protect)store.transaction(tenant,true,tx->{tx.protectBootstrapAdmin("setup");return null;});
        return store;
    }
    private void change(PostgresStore store,Consumer<Map<Ids.RecordId,Directory.Entry>> edit){
        store.transaction(tenant,true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());edit.accept(entries);tx.save(before,new Directory(before.revision()+1,entries));return null;});
    }
    private void second(PostgresStore store){change(store,entries->{
        TestSupport.put(entries,codec,Ids.Kind.USER,new ControlUser("second","Second administrator",true,1L));
        GroupGraph.addDefault(entries,codec,Ids.Kind.USER,"second");
        TestSupport.put(entries,codec,Ids.Kind.TEAM,new ControlTeam("second-admins","Other administrators",true,1L,List.of("second"),List.of("test-recovery-role")));
    });}
    private void denied(Runnable change){assertEquals(409,assertThrows(Failure.class,change::run).status());}

    @Test void protectedLoginCannotBeDeletedDisabledOrLoseAuthorityEvenWithAnotherAdmin(){
        var store=store(true);second(store);
        denied(()->change(store,e->e.remove(Ids.Kind.USER.id("setup"))));
        denied(()->change(store,e->TestSupport.put(e,codec,Ids.Kind.USER,new ControlUser("setup","Setup",false,2L))));
        denied(()->change(store,e->e.remove(Ids.Kind.TEAM.id("test-recovery-team"))));
        denied(()->change(store,e->TestSupport.put(e,codec,Ids.Kind.TEAM,new ControlTeam("test-recovery-team","Disabled",false,2L,List.of("setup"),List.of("test-recovery-role")))));
        denied(()->change(store,e->TestSupport.put(e,codec,Ids.Kind.ROLE,new ControlRole("test-recovery-role","Demoted",true,2L,UserRole.ADMINISTRATOR,EnterpriseRoleType.MANAGEMENT,List.of()))));
        boolean setupIsAdmin=store.transaction(tenant,false,tx->new ManagementAccess(codec).superAdministrator(tx.load(),"setup"));assertTrue(setupIsAdmin);
    }
    @Test void fallbackAllowsReplacementButNeverRemovalOfLastEffectiveAdmin(){
        var store=store(false);
        denied(()->change(store,e->e.remove(Ids.Kind.TEAM.id("test-recovery-team"))));
        second(store);
        change(store,e->e.remove(Ids.Kind.TEAM.id("test-recovery-team")));
        denied(()->change(store,e->e.remove(Ids.Kind.TEAM.id("second-admins"))));
    }
    @Test void renamingAndRestartDoNotRemoveSetupProtection(){
        var store=store(true);
        change(store,e->TestSupport.put(e,codec,Ids.Kind.USER,new ControlUser("setup","Renamed administrator",true,2L)));
        assertEquals("setup",store.transaction(tenant,false,Store.Session::bootstrapAdminUserId));
        denied(()->change(store,e->e.remove(Ids.Kind.USER.id("setup"))));
        store.transaction(tenant,true,tx->{assertThrows(Failure.class,()->tx.protectBootstrapAdmin("second"));return null;});
    }
    @Test void protectedLoginCannotLoseItsLastExistingIdentity(){
        var store=store(true);
        change(store,e->TestSupport.put(e,codec,Ids.Kind.IDENTITY_BINDING,new ControlIdentityBinding("setup-login","Setup login",true,1L,"setup","https://issuer.example","setup",1L,1L,1L,1L,"REVIEWED_INSTALLATION",0L)));
        denied(()->change(store,e->e.remove(Ids.Kind.IDENTITY_BINDING.id("setup-login"))));
    }
    @Test void fallbackPreservesASignInPathAndConcurrentRemovalsHaveOnlyOneWinner() throws Exception {
        var store=store(false);second(store);
        change(store,e->TestSupport.put(e,codec,Ids.Kind.IDENTITY_BINDING,new ControlIdentityBinding("setup-login","Setup login",true,1L,"setup","https://issuer.example","setup",1L,1L,1L,1L,"REVIEWED_INSTALLATION",0L)));
        denied(()->change(store,e->e.remove(Ids.Kind.IDENTITY_BINDING.id("setup-login"))));
        change(store,e->TestSupport.put(e,codec,Ids.Kind.IDENTITY_BINDING,new ControlIdentityBinding("second-login","Second login",true,1L,"second","https://issuer.example","second",1L,1L,1L,1L,"REVIEWED_INSTALLATION",0L)));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var tasks=new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for(var team:List.of("test-recovery-team","second-admins"))tasks.add(()->{try{change(store,e->e.remove(Ids.Kind.TEAM.id(team)));return true;}catch(Failure denied){assertEquals(409,denied.status());return false;}});
            var results=pool.invokeAll(tasks);assertNotEquals(results.get(0).get(),results.get(1).get());
        }
    }

}
