// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.contracts.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Atomic group administration. Recheck scope before replay and protect membership/recovery invariants. */
public final class DirectoryService {
    private final Store store; private final Codec codec; private final int maxRecords,maxBytes; private final ManagementAccess management; private final java.time.Clock lifecycleClock;
    public DirectoryService(Store store,Codec codec,int maxRecords,int maxBytes) {this(store,codec,maxRecords,maxBytes,java.time.Clock.systemUTC());}
    public DirectoryService(Store store,Codec codec,int maxRecords,int maxBytes,java.time.Clock lifecycleClock) {this.store=store;this.codec=codec;this.maxRecords=maxRecords;this.maxBytes=maxBytes;management=new ManagementAccess(codec);this.lifecycleClock=lifecycleClock;}
    public record Actor(TenantId tenant,String id,boolean admin,boolean superAdmin,String userId) {
        public Actor(TenantId tenant,String id,boolean admin) {this(tenant,id,admin,false,null);}
        public Actor(TenantId tenant,String id,boolean admin,boolean superAdmin) {this(tenant,id,admin,superAdmin,null);}
        public Actor {Objects.requireNonNull(tenant);if(id==null||!id.matches("[a-f0-9]{64}"))throw Failure.validation();if(userId!=null)Ids.valid(userId);}
        public void requireAdmin() {if(!admin||userId==null)throw ManagementAccess.denied();}
    }
    public void requirePortal(Actor actor,String userId) {
        if(userId==null)throw ManagementAccess.denied();
        store.transaction(actor.tenant(),false,tx->{var access=management.access(tx.load(),userId);if(access.portalRole()!=UserRole.SUPER_ADMIN&&(access.portalRole()==UserRole.BASIC||access.grants().stream().noneMatch(r->ManagementAccess.current(r.conditions(),System.currentTimeMillis()))))throw ManagementAccess.denied();return null;});
    }
    public boolean portalSuper(Actor actor,String userId) {if(userId==null)return false;return store.transaction(actor.tenant(),false,tx->management.access(tx.load(),userId).portalRole()==UserRole.SUPER_ADMIN);}
    public Actor portalActor(TenantId tenant,String principal,String userId) {
        return store.transaction(tenant,false,tx->{var access=management.access(tx.load(),userId);return new Actor(tenant,principal,access.portalRole()!=UserRole.BASIC,access.portalRole()==UserRole.SUPER_ADMIN,userId);});
    }
    private void require(Actor actor,Directory directory,String action,Directory.Entry entry) {actor.requireAdmin();management.requireEntry(directory,actor.userId(),action,entry,System.currentTimeMillis());}
    private boolean recovery(Directory directory) {
        return directory.entries().values().stream().filter(e->e.id().kind()==Kind.USER&&e.enabled()).anyMatch(e->{var a=management.access(directory,e.id().value());return a.portalRole()==UserRole.SUPER_ADMIN;});
    }
    private Directory validated(Store.Session tx,Directory before,long revision,Map<Ids.RecordId,Directory.Entry> entries) {
        try {var after=new Directory(revision,entries);BootstrapAdministrator.validate(tx,after);after.validate(maxRecords,maxBytes);codec.validatePolicies(after);if(recovery(before)&&!recovery(after))throw Failure.conflict();return after;}catch(IllegalArgumentException failure){throw Failure.conflict();}
    }
    private void secretReferences(Store.Session tx,Directory after){for(var secret:tx.enterprise().secrets())if(!after.entries().containsKey(Kind.TOOL_GROUP.id(secret.toolGroupId()))||!after.entries().containsKey(Kind.DEVICE_GROUP.id(secret.deviceGroupId())))throw Failure.conflict();}
    public Store.Reply get(Actor actor,Kind kind,String id) {return store.transaction(actor.tenant(),false,tx->{var d=tx.load();var e=d.entries().get(kind.id(id));if(e==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Record not found");require(actor,d,"read",e);return new Store.Reply(200,e.document(),e.revision());});}
    public Store.Reply page(Actor actor,Kind kind,String cursor,int limit) {
        actor.requireAdmin();if(limit<1||limit>100)throw Failure.validation();String after="";
        if(cursor!=null)try {if(cursor.length()>512)throw Failure.validation();var p=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\n",-1);if(p.length!=3||!p[0].equals(actor.tenant().value())||!p[1].equals(kind.name()))throw Failure.validation();after=Ids.valid(p[2]);}catch(IllegalArgumentException failure){throw Failure.validation();}
        final String position=after;
        return store.transaction(actor.tenant(),false,tx->{var d=tx.load();var rows=d.entries().values().stream().filter(e->e.id().kind()==kind&&e.id().value().compareTo(position)>0).sorted(Comparator.comparing(e->e.id().value())).filter(e->{try {require(actor,d,"read",e);return true;}catch(Failure denied){if(denied.status()!=403)throw denied;return false;}}).limit(limit+1L).toList();var body=new HashMap<String,Object>();body.put("items",rows.stream().limit(limit).map(e->codec.value(e.document())).toList());if(rows.size()>limit)body.put("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString((actor.tenant().value()+"\n"+kind.name()+"\n"+rows.get(limit-1).id().value()).getBytes(StandardCharsets.UTF_8)));return new Store.Reply(200,codec.json(body),d.revision());});
    }
    public Store.Reply export(Actor actor,boolean yaml) {actor.requireAdmin();return store.transaction(actor.tenant(),false,tx->{var d=tx.load();management.requireAll(d,actor.userId(),"export",System.currentTimeMillis());return new Store.Reply(200,codec.snapshot(actor.tenant(),d,yaml),d.revision());});}
    public Store.Reply audit(Actor actor,long after,int limit) {actor.requireAdmin();if(after<0||limit<1||limit>100)throw Failure.validation();return store.transaction(actor.tenant(),false,tx->{management.requireAll(tx.load(),actor.userId(),"audit",System.currentTimeMillis());return new Store.Reply(200,tx.auditPage(after,limit),0);});}
    public Store.Reply mutate(Actor actor,Kind kind,String id,String operation,String document,long expected,String key,String requestId) {
        actor.requireAdmin();Ids.valid(key);Ids.valid(requestId);if(!Set.of("CREATE","UPDATE","DELETE").contains(operation))throw Failure.validation();
        var typedId=kind.id(id);if(GroupGraph.protectedDefault(typedId)&&operation.equals("DELETE"))throw Failure.conflict();
        var input=operation.equals("DELETE")?null:codec.entry(kind,document);if(input!=null&&!input.id().equals(typedId))throw Failure.validation();
        var hash=digest(operation+"\n"+kind+"\n"+id+"\n"+expected+"\n"+(input==null?"":input.document()));
        return store.transaction(actor.tenant(),true,tx->{
            var before=tx.load();var old=before.entries().get(typedId);if(input==null&&old==null)throw missingFailure();require(actor,before,operation.toLowerCase(Locale.ROOT),input==null?old:input);
            if(old!=null)require(actor,before,operation.toLowerCase(Locale.ROOT),old);
            var replay=tx.replay(actor.id(),key,hash);if(replay!=null)return replay;
            lifecycle(tx,old,input);
            if(GroupGraph.group(kind))management.requireAllocation(before,before,actor.userId(),input==null?old:input,System.currentTimeMillis());
            if(operation.equals("CREATE")){if(old!=null||tx.used(typedId)||input==null||input.revision()!=1)throw Failure.conflict();}
            else if(old==null||old.revision()!=expected||input!=null&&input.revision()!=expected)throw Failure.conflict();
            if(GroupGraph.group(kind)&&input!=null) {
                // Membership and role changes require authority over every affected source/destination group.
                var prior=old==null?List.<String>of():GroupGraph.members(old,codec);var next=GroupGraph.members(input,codec);
                if(!prior.equals(next))for(var member:union(prior,next)) {var e=before.entries().get(GroupGraph.entityKind(kind).id(member));if(e!=null)require(actor,before,"update",e);}
                if(!GroupGraph.roles(input,codec).equals(old==null?List.of():GroupGraph.roles(old,codec)))management.requireAll(before,actor.userId(),"manage-role",System.currentTimeMillis());
            }
            if(kind==Kind.USER&&input!=null&&(old==null&&input.enabled()||old!=null&&old.enabled()!=input.enabled()))require(actor,before,input.enabled()?"enable":"disable",input);
            var entries=new HashMap<>(before.entries());GroupGraph.defaults(entries,codec);
            if(input==null){entries.remove(typedId);if(GroupGraph.individual(kind))GroupGraph.removeMember(entries,codec,kind,id);}else entries.put(typedId,codec.revision(input,old==null?1:old.revision()+1));
            if(old==null&&input!=null&&GroupGraph.individual(kind))GroupGraph.addDefault(entries,codec,kind,id);
            if((kind==Kind.USER||kind==Kind.AGENT)&&input!=null&&input.enabled()&&(old==null||!old.enabled()))for(var group:GroupGraph.memberships(new Directory(before.revision(),entries),codec,kind,id,false))management.requireAllocation(before,new Directory(before.revision(),entries),actor.userId(),entries.get(GroupGraph.groupKind(kind).id(group)),System.currentTimeMillis());
            if(kind==Kind.USER&&old!=null&&old.enabled()!=inputEnabled(input))invalidateSessions(entries,id);
            if(kind==Kind.AGENT&&old!=null&&old.enabled()!=inputEnabled(input))invalidateWorkloads(entries,id,null);
            var after=validated(tx,before,before.revision()+1,entries);secretReferences(tx,after);tx.save(before,after);auditChanges(tx,actor,before,after,requestId,operation,hash);
            var reply=input==null?new Store.Reply(204,"",after.revision()):new Store.Reply(old==null?201:200,after.entries().get(typedId).document(),after.entries().get(typedId).revision());tx.remember(actor.id(),key,hash,reply);return reply;
        });
    }
    private static boolean inputEnabled(Directory.Entry input) {return input!=null&&input.enabled();}
    private static List<String> union(List<String> a,List<String> b) {var values=new TreeSet<>(a);values.addAll(b);return List.copyOf(values);}
    private void invalidateSessions(Map<Ids.RecordId,Directory.Entry> entries,String user) {
        for(var e:List.copyOf(entries.values()))if(e.id().kind()==Kind.IDENTITY_BINDING) {var b=codec.model(e.document(),ControlIdentityBinding.class);if(b.userId().equals(user))entries.put(e.id(),codec.entry(Kind.IDENTITY_BINDING,codec.json(new ControlIdentityBinding(b.id(),b.name(),b.enabled(),b.revision()+1,b.userId(),b.issuer(),b.subject(),b.sessionEpoch()+1,b.firstSeenUnixMs(),b.lastAttemptUnixMs(),b.attemptCount(),b.registrationReason(),Math.max(b.sessionsValidAfterUnixMs(),lifecycleClock.millis()/1000*1000+1000)))));}
        invalidateWorkloads(entries,null,user);
    }
    private void invalidateWorkloads(Map<Ids.RecordId,Directory.Entry> entries,String agent,String user) {
        for(var entry:List.copyOf(entries.values()))if(entry.id().kind()==Kind.WORKLOAD_BINDING){var b=codec.model(entry.document(),ControlWorkloadBinding.class);
            if(Objects.equals(b.agentId(),agent)||user!=null&&user.equals(b.delegatedUserId()))entries.put(entry.id(),codec.entry(Kind.WORKLOAD_BINDING,codec.json(new ControlWorkloadBinding(b.id(),b.name(),false,b.revision()+1,b.agentId(),b.mode(),b.issuer(),b.subject(),b.audience(),b.credentialSha256(),b.credentialEpoch()+1,b.expiresAtUnixMs(),b.delegatedUserId(),b.parentBindingId(),b.delegatedSessionEpoch()))));
        }
    }
    private void lifecycle(Store.Session tx,Directory.Entry old,Directory.Entry next) {
        if(next==null)return;
        if(next.id().kind()==Kind.IDENTITY_BINDING&&old!=null){var a=codec.model(old.document(),ControlIdentityBinding.class);var b=codec.model(next.document(),ControlIdentityBinding.class);
            if(!a.userId().equals(b.userId())||!a.issuer().equals(b.issuer())||!a.subject().equals(b.subject())||!java.util.Objects.equals(a.firstSeenUnixMs(),b.firstSeenUnixMs())
                ||b.sessionEpoch()<a.sessionEpoch()||b.sessionsValidAfterUnixMs()<a.sessionsValidAfterUnixMs()||b.attemptCount()<a.attemptCount()||b.lastAttemptUnixMs()<a.lastAttemptUnixMs())throw Failure.conflict();
            if(a.enabled()!=b.enabled()||!java.util.Objects.equals(a.sessionEpoch(),b.sessionEpoch()))if(b.sessionEpoch()<=a.sessionEpoch()||b.sessionsValidAfterUnixMs()<lifecycleClock.millis()/1000*1000+1000)throw Failure.conflict();
        }
        if(next.id().kind()==Kind.WORKLOAD_BINDING){var b=codec.model(next.document(),ControlWorkloadBinding.class);
            if(old==null){if(tx.enterprise().credentialUsed(b.credentialSha256()))throw Failure.conflict();return;}
            var a=codec.model(old.document(),ControlWorkloadBinding.class);
            if(!a.agentId().equals(b.agentId())||b.credentialEpoch()<a.credentialEpoch())throw Failure.conflict();
            boolean security=a.enabled()!=b.enabled()||a.mode()!=b.mode()||!a.issuer().equals(b.issuer())||!a.subject().equals(b.subject())||!a.audience().equals(b.audience())
                ||!java.util.Objects.equals(a.expiresAtUnixMs(),b.expiresAtUnixMs())||!Objects.equals(a.delegatedUserId(),b.delegatedUserId())||!Objects.equals(a.parentBindingId(),b.parentBindingId())||!Objects.equals(a.delegatedSessionEpoch(),b.delegatedSessionEpoch());
            boolean rotated=!a.credentialSha256().equals(b.credentialSha256());
            if((security||rotated)&&b.credentialEpoch()<=a.credentialEpoch()||rotated&&tx.enterprise().credentialUsed(b.credentialSha256())||!a.enabled()&&b.enabled()&&!rotated)throw Failure.conflict();
        }
    }
    public Store.Reply importConfig(Actor actor,String document,boolean yaml,long expected,String key,String requestId) {
        actor.requireAdmin();Ids.valid(key);Ids.valid(requestId);var input=codec.input(document,yaml,actor.tenant());if(input.directory().revision()!=expected)throw Failure.conflict();var hash=digest("IMPORT\n"+expected+"\n"+input.replace()+"\n"+input.dryRun()+"\n"+codec.snapshot(actor.tenant(),input.directory(),false));
        return store.transaction(actor.tenant(),true,tx->{
            var before=tx.load();management.requireAll(before,actor.userId(),"import",System.currentTimeMillis());
            // Authorization precedes replay; imports may not restore old credential/session epochs.
            for(var e:input.directory().entries().values())require(actor,before,"update",e);
            var replay=input.dryRun()?null:tx.replay(actor.id(),key,hash);if(replay!=null)return replay;if(before.revision()!=expected)throw Failure.conflict();
            var entries=input.replace()?new HashMap<Ids.RecordId,Directory.Entry>():new HashMap<>(before.entries());
            for(var proposed:input.directory().entries().values()) {var old=before.entries().get(proposed.id());var e=importObservations(old,proposed);if(old==null&&tx.used(e.id()))throw Failure.conflict();lifecycle(tx,old,e);var normalized=codec.revision(e,old==null?1:old.revision());entries.put(e.id(),old!=null&&old.document().equals(normalized.document())?old:codec.revision(e,old==null?1:old.revision()+1));}
            for(var e:before.entries().values()){var next=entries.get(e.id());if(GroupGraph.individual(e.id().kind())&&e.enabled()!=inputEnabled(next)){if(e.id().kind()==Kind.USER)invalidateSessions(entries,e.id().value());if(e.id().kind()==Kind.AGENT)invalidateWorkloads(entries,e.id().value(),null);}}
            // Import is reviewed as a complete graph; missing memberships are rejected rather than guessed.
            var changes=changes(before,new Directory(before.revision(),entries));var after=validated(tx,before,before.revision()+(changes.isEmpty()||input.dryRun()?0:1),entries);secretReferences(tx,after);
            for(var group:after.entries().values())if(GroupGraph.group(group.id().kind())) {
                var old=before.entries().get(group.id());var prior=old==null?List.<String>of():GroupGraph.members(old,codec);
                if(GroupGraph.members(group,codec).stream().anyMatch(member->!prior.contains(member))||!GroupGraph.roles(group,codec).equals(old==null?List.of():GroupGraph.roles(old,codec)))management.requireAllocation(before,after,actor.userId(),group,System.currentTimeMillis());
            }
            for(var e:before.entries().values())if(GroupGraph.protectedDefault(e.id())&&!entries.containsKey(e.id()))throw Failure.conflict();
            for(var e:after.entries().values()) {var old=before.entries().get(e.id());if(old==null)continue;if(e.id().kind()==Kind.IDENTITY_BINDING&&(codec.model(e.document(),ControlIdentityBinding.class).sessionEpoch()<codec.model(old.document(),ControlIdentityBinding.class).sessionEpoch()||codec.model(e.document(),ControlIdentityBinding.class).sessionsValidAfterUnixMs()<codec.model(old.document(),ControlIdentityBinding.class).sessionsValidAfterUnixMs())||e.id().kind()==Kind.WORKLOAD_BINDING&&codec.model(e.document(),ControlWorkloadBinding.class).credentialEpoch()<codec.model(old.document(),ControlWorkloadBinding.class).credentialEpoch())throw Failure.conflict();}
            var reply=new Store.Reply(200,codec.result(!input.dryRun(),after.revision(),changes),after.revision());if(!input.dryRun()){tx.save(before,after);auditChanges(tx,actor,before,after,requestId,"IMPORT",hash);tx.remember(actor.id(),key,hash,reply);}return reply;
        });
    }
    private Directory.Entry importObservations(Directory.Entry old,Directory.Entry proposed) {
        if(old==null||proposed.id().kind()!=Kind.IDENTITY_BINDING)return proposed;
        var live=codec.model(old.document(),ControlIdentityBinding.class);var desired=codec.model(proposed.document(),ControlIdentityBinding.class);
        // Verified login observations change without a configuration revision. Import cannot replace them.
        return codec.entry(Kind.IDENTITY_BINDING,codec.json(new ControlIdentityBinding(desired.id(),desired.name(),desired.enabled(),desired.revision(),desired.userId(),desired.issuer(),desired.subject(),desired.sessionEpoch(),desired.firstSeenUnixMs(),live.lastAttemptUnixMs(),live.attemptCount(),live.registrationReason(),desired.sessionsValidAfterUnixMs())));
    }
    private List<Map<String,String>> changes(Directory before,Directory after) {
        var result=new ArrayList<Map<String,String>>();var keys=new HashSet<>(before.entries().keySet());keys.addAll(after.entries().keySet());keys.stream().sorted(Comparator.comparing((Ids.RecordId i)->i.kind().name()).thenComparing(Ids.RecordId::value)).forEach(id->{var a=before.entries().get(id);var b=after.entries().get(id);if(!Objects.equals(a,b))result.add(Map.of("kind",id.kind().name(),"id",id.value(),"operation",a==null?"CREATE":b==null?"DELETE":"UPDATE"));});return result;
    }
    private void auditChanges(Store.Session tx,Actor actor,Directory before,Directory after,String requestId,String operation,String hash) {
        tx.audit(actor.id(),operation,"directory",after.revision(),requestId,hash);for(var c:changes(before,after))tx.audit(actor.id(),c.get("operation"),Kind.valueOf(c.get("kind")).path()+":"+c.get("id"),after.revision(),requestId,digest(codec.json(c)));
    }
    public Store.Reply memberships(Actor actor,Kind kind,String id) {return store.transaction(actor.tenant(),false,tx->{var d=tx.load();var e=d.entries().get(kind.id(id));if(e==null)throw missingFailure();require(actor,d,"read",e);return membership(d,kind,id);});}
    private static Failure missingFailure() {return new Failure(ErrorCode.NOT_FOUND,404,"Record not found");}
    private Store.Reply membership(Directory d,Kind kind,String id) {return new Store.Reply(200,codec.json(new GroupMembership(EnterpriseMemberType.valueOf(kind.name()),id,GroupGraph.memberships(d,codec,kind,id,false),d.revision())),d.revision());}
    public Store.Reply setMemberships(Actor actor,Kind kind,String id,String document,long expected,String key,String requestId) {
        actor.requireAdmin();Ids.valid(key);Ids.valid(requestId);var input=codec.model(document,GroupMembership.class);if(!id.equals(input.entityId())||!kind.name().equals(input.entityType().name())||input.revision()!=expected)throw Failure.validation();var hash=digest("MEMBERSHIPS\n"+codec.json(input));
        return store.transaction(actor.tenant(),true,tx->{var before=tx.load();var entity=before.entries().get(kind.id(id));if(entity==null)throw missingFailure();require(actor,before,"update",entity);for(var group:input.groupIds()){management.require(before,actor.userId(),"update",GroupGraph.groupKind(kind),group,System.currentTimeMillis());management.requireAllocation(before,before,actor.userId(),before.entries().get(GroupGraph.groupKind(kind).id(group)),System.currentTimeMillis());}var replay=tx.replay(actor.id(),key,hash);if(replay!=null)return replay;if(before.revision()!=expected)throw Failure.conflict();var entries=new HashMap<>(before.entries());GroupGraph.setMemberships(entries,codec,kind,id,input.groupIds());var after=validated(tx,before,before.revision()+1,entries);tx.save(before,after);auditChanges(tx,actor,before,after,requestId,"MEMBERSHIP_UPDATE",hash);var reply=membership(after,kind,id);tx.remember(actor.id(),key,hash,reply);return reply;});
    }
    public Store.Reply simulate(Actor actor,String document) {actor.requireAdmin();var input=codec.model(document,EnterpriseEvaluation.class);return store.transaction(actor.tenant(),false,tx->{management.requireAll(tx.load(),actor.userId(),"simulate",System.currentTimeMillis());var decision=new EnterpriseEvaluator(codec).evaluate(actor.tenant(),tx.load(),input);return new Store.Reply(200,codec.json(decision),tx.load().revision());});}
    public Store.Reply shadow(Actor actor,String document){actor.requireAdmin();var request=codec.model(document,EnterpriseShadowRequest.class);return store.transaction(actor.tenant(),false,tx->{management.requireAll(tx.load(),actor.userId(),"simulate",lifecycleClock.millis());
        var proposed=codec.input(codec.json(Map.of("snapshot",request.snapshot(),"mode","REPLACE","dryRun",true)),false,actor.tenant()).directory();proposed.validate(maxRecords,maxBytes);codec.validatePolicies(proposed);
        var decision=new EnterpriseEvaluator(codec).evaluateWithQuotas(actor.tenant(),proposed,request.evaluation(),budget->tx.enterprise().budgetUsage(budget,Math.max(0,request.evaluation().nowUnixMs()-60000),null));
        boolean expansion=request.observedLegacyDecision()==Decision.BLOCK&&decision.decision()!=Decision.BLOCK||request.observedLegacyDecision()==Decision.ASK&&decision.decision()==Decision.ALLOW;
        return new Store.Reply(200,codec.json(new EnterpriseShadowResult(decision,request.observedLegacyDecision(),request.legacyEvidenceDigest(),digest(codec.json(request.snapshot())),expansion)),tx.load().revision());});}
    public static String digest(String value) {return digest(value.getBytes(StandardCharsets.UTF_8));}
    public static String digest(byte[] value) {try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
