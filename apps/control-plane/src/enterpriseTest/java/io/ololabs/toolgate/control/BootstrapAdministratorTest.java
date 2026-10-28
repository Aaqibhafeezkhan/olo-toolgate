// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapAdministratorTest {
    @TempDir java.nio.file.Path temp;
    EnterpriseAdministrationTest fixture;
    DirectoryService directory;
    DirectoryService.Actor owner,second;
    ContractCodec codec;

    @BeforeEach void setup() {
        fixture=new EnterpriseAdministrationTest();fixture.temp=temp;fixture.seedReviewedRecovery();
        directory=fixture.directory;owner=fixture.administrator;codec=fixture.graph.codec;
        // A second live Super Admin means last-admin protection alone would permit removal.
        directory.mutate(owner,Kind.TEAM,"recovery-team","UPDATE",codec.json(new ControlTeam("recovery-team","Owners",true,1L,List.of("admin","alice"),List.of("recovery-role"))),1,"assign-second","request");
        second=directory.portalActor(owner.tenant(),"b".repeat(64),"alice");
        fixture.store.transaction(owner.tenant(),true,tx->{tx.protectBootstrapAdmin("admin");return null;});
    }

    @Test void firstAdminCannotBeDeletedOrDisabledEvenByAnotherSuperAdmin() {
        var before=fixture.store.transaction(owner.tenant(),false,tx->tx.load());
        for(var actor:List.of(owner,second))for(var operation:List.of("DELETE","UPDATE")) {
            var body=operation.equals("DELETE")?null:codec.json(new ControlUser("admin","Admin",false,1L));
            var failure=assertThrows(Failure.class,()->directory.mutate(actor,Kind.USER,"admin",operation,body,1,UUID.randomUUID().toString(),"request"));
            assertEquals(409,failure.status());assertTrue(failure.getMessage().contains("login created during setup"));
            assertEquals(before,fixture.store.transaction(owner.tenant(),false,tx->tx.load()));
        }
    }

    @Test void importsAndReviewedPreviewsCannotRemoveOrDisableFirstAdmin() {
        var exported=directory.export(second,false);
        // Backups carry no users, so Replace cannot remove the setup administrator; a file that lists it disabled is refused.
        var admin=(com.fasterxml.jackson.databind.node.ObjectNode)codec.value(directory.get(second,Ids.Kind.USER,"admin").body());admin.put("enabled",false);
        for(boolean dryRun:List.of(false,true)) {
            var snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)codec.value(exported.body());assertTrue(snapshot.get("users").isEmpty());((com.fasterxml.jackson.databind.node.ArrayNode)snapshot.get("users")).add(admin);
            var body=codec.json(Map.of("snapshot",snapshot,"mode","REPLACE","dryRun",dryRun));
            var failure=assertThrows(Failure.class,()->directory.importConfig(second,body,false,exported.revision(),UUID.randomUUID().toString(),"request"));
            assertEquals(409,failure.status());assertTrue(failure.getMessage().contains("login created during setup"));
        }
        var replace=codec.json(Map.of("snapshot",codec.value(exported.body()),"mode","REPLACE","dryRun",true));assertEquals(200,directory.importConfig(second,replace,false,exported.revision(),"replace-keeps-admin","request").status());
        var changes=new ConfigurationChanges(fixture.store,codec,Clock.systemUTC(),512,1048576);
        var disable=new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.UPDATE,ControlEntityKind.USER,"admin",codec.json(new ControlUser("admin","Admin",false,1L)),1L);
        assertEquals(409,assertThrows(Failure.class,()->changes.mutate(second,disable,"direct-disable","request")).status());
        assertEquals(409,assertThrows(Failure.class,()->changes.create(second,disable,"draft-disable","request")).status());
        assertEquals(exported.revision(),directory.export(second,false).revision());
    }

    @Test void emailAndNameRemainEditableAndOrdinaryUsersCanStillBeDisabledOrDeleted() {
        var user=codec.model(directory.mutate(second,Kind.USER,"admin","UPDATE",codec.json(new ControlUser("admin","Installation owner",true,1L,"owner@example.com",null,null)),1,"edit-owner","request").body(),ControlUser.class);
        assertEquals("owner@example.com",user.email());assertEquals("Installation owner",user.name());
        directory.mutate(second,Kind.USER,"ordinary","CREATE",codec.json(new ControlUser("ordinary","Ordinary",true,1L)),0,"create-ordinary","request");
        assertEquals(200,directory.mutate(second,Kind.USER,"ordinary","UPDATE",codec.json(new ControlUser("ordinary","Ordinary",false,1L)),1,"disable-ordinary","request").status());
        assertEquals(204,directory.mutate(second,Kind.USER,"ordinary","DELETE",null,2,"delete-ordinary","request").status());
        assertThrows(Failure.class,()->codec.entry(Kind.USER,codec.json(new ControlUser("bad-email","Invalid",false,1L,"not-an-email",null,null))));
    }

    @Test void liveSuperAdminRoleAssignmentGrantsAuthorityAndAccountProtectionDoesNot() {
        assertTrue(second.superAdmin());assertTrue(directory.portalSuper(second,"alice"));
        var role=codec.model(directory.get(second,Kind.ROLE,"recovery-role").body(),ControlRole.class);
        directory.mutate(second,Kind.ROLE,role.id(),"UPDATE",codec.json(new ControlRole(role.id(),role.name(),true,role.revision(),UserRole.SUPER_ADMIN,role.roleType(),List.of())),role.revision(),"empty-super-rules","request");
        var cached=new DirectoryService.Actor(second.tenant(),second.id(),true,false,"alice");
        assertEquals(201,directory.mutate(cached,Kind.USER,"created-by-second","CREATE",codec.json(new ControlUser("created-by-second","Created",false,1L)),0,"second-create","request").status());
        // Move the protected account out of the privileged Team. Protection is not a bypass.
        var membership=new GroupMembership(EnterpriseMemberType.USER,"admin",List.of("team-default"),directory.export(second,false).revision());
        assertEquals(409,assertThrows(Failure.class,()->directory.setMemberships(second,Kind.USER,"admin",codec.json(membership),membership.revision(),"remove-role","request")).status());
        assertTrue(directory.portalSuper(owner,"admin"));

    }

    @Test void identityBindingRemovalAndRestartCannotRemoveAccountProtection() {
        var identity=new ControlIdentityBinding("installed-admin","Reviewed identity",true,1L,"admin","https://quickstart.local","admin",1L,1L,1L,1L,"REVIEWED_INSTALLATION",0L);
        directory.mutate(second,Kind.IDENTITY_BINDING,identity.id(),"CREATE",codec.json(identity),0,"create-identity","request");
        assertEquals(409,assertThrows(Failure.class,()->directory.mutate(second,Kind.IDENTITY_BINDING,identity.id(),"DELETE",null,1,"remove-identity","request")).status());
        var reopened=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(temp.resolve("state.sqlite"))),codec);
        reopened.transaction(owner.tenant(),false,tx->{assertEquals("admin",tx.bootstrapAdminUserId());return null;});
        reopened.transaction(owner.tenant(),true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());entries.remove(Kind.USER.id("admin"));assertThrows(Failure.class,()->tx.save(before,new Directory(before.revision()+1,entries)));return null;});
        reopened.transaction(owner.tenant(),true,tx->{assertThrows(Failure.class,()->tx.protectBootstrapAdmin("alice"));return null;});
    }

    @Test void ordinaryRolesAndTheirGrantsRemainEditable() {
        var role=new ControlRole("ordinary-role","Ordinary role",true,1L,UserRole.BASIC,EnterpriseRoleType.HUMAN,List.of());
        directory.mutate(second,Kind.ROLE,role.id(),"CREATE",codec.json(role),0,"create-role","request");
        var grant=new ControlAccessGrant("ordinary-grant","Ordinary grant",true,1L,EnterpriseSourceType.ROLE,role.id(),EnterpriseGrantPurpose.HUMAN,fixture.graph.scope("tools-a","devices-a"));
        directory.mutate(second,Kind.GRANT,grant.id(),"CREATE",codec.json(grant),0,"create-grant","request");
        assertEquals(200,directory.mutate(second,Kind.GRANT,grant.id(),"UPDATE",codec.json(new ControlAccessGrant(grant.id(),grant.name(),false,1L,grant.sourceType(),grant.sourceId(),grant.purpose(),grant.scope())),1,"edit-grant","request").status());
        assertEquals(200,directory.mutate(second,Kind.ROLE,role.id(),"UPDATE",codec.json(new ControlRole(role.id(),"Updated role",false,1L,role.portalRole(),role.roleType(),List.of())),1,"edit-role","request").status());
    }

    @Test void restartPreservesAdminProtectionWithoutReseedingUsersEmailOrRoles() throws Exception {
        var before=directory.export(second,false).body();
        var reopened=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(temp.resolve("state.sqlite"))),codec);
        assertEquals(before,new DirectoryService(reopened,codec,512,1048576).export(second,false).body());
        reopened.transaction(owner.tenant(),false,tx->{assertEquals("admin",tx.bootstrapAdminUserId());return null;});
    }
}
