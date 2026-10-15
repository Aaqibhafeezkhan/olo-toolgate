// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.security.*;
import java.security.spec.*;
import java.math.BigInteger;
import java.time.Clock;
import java.util.*;

/** Operator-only bootstrap/recovery, independently reviewed under external pinned keys. No HTTP bypass. */
public final class ReviewedRecovery {
    private final Store store;private final Codec codec;private final Clock clock;
    public ReviewedRecovery(Store store,Codec codec,Clock clock){this.store=store;this.codec=codec;this.clock=clock;}
    public void apply(String document,List<FleetTrustKey> trust){
        var packet=codec.model(document,EnterpriseReviewedRecovery.class);var authorization=packet.authorization();
        var tenant=new Ids.TenantId(authorization.tenantId());
        var digest=DirectoryService.digest(codec.json(packet));
        byte[] signed=("OLO ToolGate recovery v1\n"+codec.json(authorization)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var reviewers=new HashSet<String>();var moduli=new HashSet<BigInteger>();
        try{for(var proof:packet.proofs()){
            var key=trust.stream().filter(k->k.kid().equals(proof.keyId())).findFirst().orElseThrow(ManagementAccess::denied);
            var modulus=new BigInteger(1,Base64.getUrlDecoder().decode(key.n()));var exponent=new BigInteger(1,Base64.getUrlDecoder().decode(key.e()));
            if(modulus.bitLength()<2048||modulus.bitLength()>8192||!exponent.equals(BigInteger.valueOf(65537))||!reviewers.add(key.kid())||!moduli.add(modulus))throw ManagementAccess.denied();
            var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus,exponent)));verifier.update(signed);
            if(!verifier.verify(Base64.getUrlDecoder().decode(proof.signature())))throw ManagementAccess.denied();
        }}catch(GeneralSecurityException|IllegalArgumentException invalid){throw ManagementAccess.denied();}
        store.transaction(tenant,true,tx->{if(tx.enterprise().recoveryApplied(digest))return null;
            long now=clock.millis();if(authorization.issuedAtUnixMs()>now||authorization.expiresAtUnixMs()<=now||authorization.expiresAtUnixMs()-authorization.issuedAtUnixMs()>3600000)throw ManagementAccess.denied();
            var before=tx.load();boolean initial=before.revision()==0&&before.entries().isEmpty()&&tx.enterprise().authorizationEpoch()==0;if(reviewers.size()<(initial?1:2))throw ManagementAccess.denied();if(!java.util.Objects.equals(before.revision(),authorization.expectedRevision())||!authorization.snapshot().tenantId().equals(tenant.value())||authorization.snapshot().revision()!=before.revision())throw Failure.conflict();
            var input=codec.input(codec.json(Map.of("snapshot",authorization.snapshot(),"mode","REPLACE","dryRun",false)),false,tenant).directory();
            var entries=new HashMap<>(before.entries());
            for(var e:input.entries().values()){
                var old=before.entries().get(e.id());if(e.equals(old))continue;
                // Emergency review may restore administration, never introduce runtime grants or replace stable identity facts.
                if(!Set.of(Ids.Kind.USER,Ids.Kind.TEAM,Ids.Kind.ROLE,Ids.Kind.IDENTITY_BINDING,Ids.Kind.AGENT_GROUP,Ids.Kind.TOOL_GROUP,Ids.Kind.DEVICE_GROUP).contains(e.id().kind())&&!(initial&&(e.id().kind()==Ids.Kind.TOOL&&!e.enabled()||e.id().kind()==Ids.Kind.AGENT&&!e.enabled()||e.id().kind()==Ids.Kind.EXTRACTOR||e.id().kind()==Ids.Kind.BINDING)))throw ManagementAccess.denied();
                if(old==null&&tx.used(e.id()))throw Failure.conflict();
                if(!before.entries().isEmpty()&&Set.of(Ids.Kind.AGENT_GROUP,Ids.Kind.TOOL_GROUP,Ids.Kind.DEVICE_GROUP).contains(e.id().kind()))throw ManagementAccess.denied();
                if(e.id().kind()==Ids.Kind.USER&&old==null&&!before.entries().isEmpty()){var defaults=before.entries().get(Ids.Kind.TEAM.id("team-default"));if(!GroupGraph.roles(defaults,codec).isEmpty()||before.entries().values().stream().filter(g->g.id().kind()==Ids.Kind.GRANT).map(g->codec.model(g.document(),ControlAccessGrant.class)).anyMatch(g->g.sourceType()==EnterpriseSourceType.TEAM&&g.sourceId().equals("team-default")))throw ManagementAccess.denied();}
                if(e.id().kind()==Ids.Kind.USER&&old!=null&&(!old.enabled()&&e.enabled()||!codec.model(old.document(),ControlUser.class).equals(codec.model(e.document(),ControlUser.class))))throw ManagementAccess.denied();
                if(e.id().kind()==Ids.Kind.TEAM){var team=codec.model(e.document(),ControlTeam.class);
                    if(!GroupGraph.protectedDefault(e.id())){if(team.roleIds().isEmpty())throw ManagementAccess.denied();for(var roleId:team.roleIds()){var roleEntry=input.entries().get(Ids.Kind.ROLE.id(roleId));if(roleEntry==null||codec.model(roleEntry.document(),ControlRole.class).roleType()!=EnterpriseRoleType.MANAGEMENT)throw ManagementAccess.denied();}
                        if(before.entries().values().stream().filter(g->g.id().kind()==Ids.Kind.GRANT).map(g->codec.model(g.document(),ControlAccessGrant.class)).anyMatch(g->g.sourceType()==EnterpriseSourceType.TEAM&&g.sourceId().equals(team.id()))||before.entries().values().stream().filter(g->g.id().kind()==Ids.Kind.DELEGATION).map(g->codec.model(g.document(),ControlDelegation.class)).anyMatch(g->g.teamId().equals(team.id())))throw ManagementAccess.denied();
                    }else if(old!=null)throw ManagementAccess.denied();
                }
                if(e.id().kind()==Ids.Kind.ROLE){var role=codec.model(e.document(),ControlRole.class);if(role.roleType()!=EnterpriseRoleType.MANAGEMENT||role.managementRules().isEmpty()||!before.entries().isEmpty()&&role.managementRules().stream().anyMatch(r->r.conditions().expiresAtUnixMs()==0||r.conditions().expiresAtUnixMs()>authorization.expiresAtUnixMs()||r.conditions().notBeforeUnixMs()<authorization.issuedAtUnixMs()))throw ManagementAccess.denied();}
                if(e.id().kind()==Ids.Kind.IDENTITY_BINDING&&old!=null){var a=codec.model(old.document(),ControlIdentityBinding.class);var b=codec.model(e.document(),ControlIdentityBinding.class);if(!a.userId().equals(b.userId())||!a.issuer().equals(b.issuer())||!a.subject().equals(b.subject())||b.sessionEpoch()<=a.sessionEpoch()||b.sessionsValidAfterUnixMs()<now)throw ManagementAccess.denied();}
                entries.put(e.id(),codec.revision(e,old==null?1:old.revision()+1));
            }
            // Fresh installations include dormant presets. Recovery never restores or changes existing runtime rights.
            if(initial)StandardAccessPresets.install(entries,codec);
            var after=new Directory(before.revision()+1,entries);after.validate(512,1048576);codec.validatePolicies(after);
            var access=new ManagementAccess(codec);
            boolean recovery=after.entries().values().stream().filter(e->e.id().kind()==Ids.Kind.USER&&e.enabled()).anyMatch(e->access.superAdministrator(after,e.id().value()));
            if(!recovery)throw ManagementAccess.denied();
            if(initial){var management=new ManagementAccess(codec);var first=authorization.snapshot().users().stream().filter(ControlUser::enabled).filter(user->management.superAdministrator(after,user.id())).findFirst().orElseThrow(ManagementAccess::denied);tx.protectBootstrapAdmin(first.id());}
            tx.save(before,after);tx.enterprise().rememberRecovery(digest);
            tx.audit(DirectoryService.digest(String.join("\n",new TreeSet<>(reviewers))),"RECOVERY_APPLY","reviewed-group-graph",after.revision(),"recovery-"+digest.substring(0,32),digest);return null;});
    }
}
