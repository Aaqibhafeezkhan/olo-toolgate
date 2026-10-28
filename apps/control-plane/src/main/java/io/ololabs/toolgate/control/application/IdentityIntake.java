// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import java.util.*;

/** Invoked only after signature/issuer/audience verification. Stable identity never comes from email. */
public final class IdentityIntake {
    private final Store store;private final Codec codec;
    public IdentityIntake(Store store,Codec codec) {this.store=store;this.codec=codec;}
    public record Identity(String userId,long sessionEpoch,boolean enabled) {}
    public Identity observe(Ids.TenantId tenant,String issuer,String subject,String claimedUser,Long claimedEpoch,long issuedAt,long expiresAt,long now) {
        if(issuer==null||issuer.length()>512||subject==null||subject.isBlank()||subject.length()>256||subject.chars().anyMatch(Character::isISOControl)||issuedAt>now||issuedAt<now-900000||expiresAt<=now||expiresAt-issuedAt>900000)throw new Failure(ErrorCode.UNAUTHORIZED,401,"Invalid identity claims");
        var stable=DirectoryService.digest(tenant.value()+"\n"+issuer+"\n"+subject);var bindingId=Kind.IDENTITY_BINDING.id("identity-"+stable.substring(0,40));
        return store.transaction(tenant,true,tx->{
            var before=tx.load();var entry=before.entries().get(bindingId);ControlIdentityBinding binding=null;
            if(entry==null)binding=before.entries().values().stream().filter(e->e.id().kind()==Kind.IDENTITY_BINDING).map(e->codec.model(e.document(),ControlIdentityBinding.class)).filter(b->b.issuer().equals(issuer)&&b.subject().equals(subject)).findFirst().orElse(null);
            else binding=codec.model(entry.document(),ControlIdentityBinding.class);
            if(binding!=null) {
                if(claimedUser!=null&&!claimedUser.equals(binding.userId())||claimedEpoch!=null&&!claimedEpoch.equals(binding.sessionEpoch())||issuedAt<binding.sessionsValidAfterUnixMs())throw new Failure(ErrorCode.UNAUTHORIZED,401,"Identity binding mismatch");
                var user=before.entries().get(Kind.USER.id(binding.userId()));if(user==null)throw Failure.unavailable();
                // Bound the number of metadata writes. Neither request arguments nor credentials are recorded.
                if(now-binding.lastAttemptUnixMs()>=60000) {
                    var b=new ControlIdentityBinding(binding.id(),binding.name(),binding.enabled(),binding.revision()+1,binding.userId(),binding.issuer(),binding.subject(),binding.sessionEpoch(),binding.firstSeenUnixMs(),now,Math.min(9007199254740991L,binding.attemptCount()+1),binding.registrationReason(),binding.sessionsValidAfterUnixMs());
                    tx.observeIdentity(b);
                }
                return new Identity(user.id().value(),binding.sessionEpoch(),user.enabled()&&binding.enabled());
            }
            // An unbound claimed user cannot attach an observed identity to an existing administrator.
            String userId="user-"+stable.substring(0,40);var id=Kind.USER.id(userId);
            if(tx.used(id)||tx.used(bindingId)||before.entries().containsKey(id)||before.entries().size()>506)throw Failure.conflict();
            var entries=new HashMap<>(before.entries());GroupGraph.defaults(entries,codec);
            entries.put(id,codec.entry(Kind.USER,codec.json(new ControlUser(userId,"Pending identity "+stable.substring(0,8),false,1L))));GroupGraph.addDefault(entries,codec,Kind.USER,userId);
            var observed=new ControlIdentityBinding(bindingId.value(),"Verified identity",true,1L,userId,issuer,subject,1L,now,now,1L,"FIRST_VERIFIED_ACCESS",0L);entries.put(bindingId,codec.entry(Kind.IDENTITY_BINDING,codec.json(observed)));
            var after=new Directory(before.revision()+1,entries);after.validate(512,1048576);codec.validatePolicies(after);tx.save(before,after);
            tx.audit(stable,"IDENTITY_REGISTER_DISABLED","users:"+userId,after.revision(),"identity-"+stable.substring(0,24),DirectoryService.digest("FIRST_VERIFIED_ACCESS"));
            return new Identity(userId,1,false);
        });
    }
    /** Ends every session of a verified identity: bumps its session epoch and moves the session cutoff to now.
     *  Saving the directory also advances the tenant authorization epoch, so outstanding permits are refused too.
     *  An identity that was never seen has nothing to revoke. */
    public boolean revokeSessions(Ids.TenantId tenant,String issuer,String subject,long now,String actor) {
        if(issuer==null||issuer.isBlank()||subject==null||subject.isBlank())throw Failure.validation();
        return store.transaction(tenant,true,tx->{
            var before=tx.load();
            var entry=before.entries().values().stream().filter(e->e.id().kind()==Kind.IDENTITY_BINDING)
                .filter(e->{var b=codec.model(e.document(),ControlIdentityBinding.class);return b.issuer().equals(issuer)&&b.subject().equals(subject);}).findFirst().orElse(null);
            if(entry==null)return false;
            var b=codec.model(entry.document(),ControlIdentityBinding.class);
            var revoked=new ControlIdentityBinding(b.id(),b.name(),b.enabled(),b.revision()+1,b.userId(),b.issuer(),b.subject(),
                Math.min(9007199254740991L,b.sessionEpoch()+1),b.firstSeenUnixMs(),b.lastAttemptUnixMs(),b.attemptCount(),b.registrationReason(),Math.max(b.sessionsValidAfterUnixMs(),now));
            var entries=new HashMap<>(before.entries());entries.put(entry.id(),codec.entry(Kind.IDENTITY_BINDING,codec.json(revoked)));
            var after=new Directory(before.revision()+1,entries);after.validate(512,1048576);codec.validatePolicies(after);tx.save(before,after);
            tx.audit(actor,"IDENTITY_SESSIONS_REVOKED","users:"+b.userId(),after.revision(),"identity-"+b.id(),DirectoryService.digest(Long.toString(revoked.sessionEpoch())));
            return true;
        });
    }
}
