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
    public record Actor(TenantId tenant,String id,boolean admin,boolean superAdmin,String userId,String sessionId,Long sessionExpiresAt,String currentDeviceId) {
        public Actor(TenantId tenant,String id,boolean admin,boolean superAdmin,String userId){this(tenant,id,admin,superAdmin,userId,null,null,null);}
        public Actor(TenantId tenant,String id,boolean admin) {this(tenant,id,admin,false,null);}
        public Actor(TenantId tenant,String id,boolean admin,boolean superAdmin) {this(tenant,id,admin,superAdmin,null);}
        public Actor {Objects.requireNonNull(tenant);if(id==null||!id.matches("[a-f0-9]{64}"))throw Failure.validation();if(userId!=null)Ids.valid(userId);}
        public void requireAdmin() {if(!admin||userId==null)throw ManagementAccess.denied();}
        public Actor consoleSession(String session,long expires,String device){return new Actor(tenant,id,admin,superAdmin,userId,session,expires,device);}
    }
    public void requirePortal(Actor actor,String userId) {
        if(userId==null)throw ManagementAccess.denied();
        store.transaction(actor.tenant(),false,tx->{var access=management.access(tx.load(),userId);if(access.portalRole()!=UserRole.SUPER_ADMIN&&(access.portalRole()==UserRole.BASIC||access.grants().stream().noneMatch(r->ManagementAccess.current(r.conditions(),System.currentTimeMillis()))))throw ManagementAccess.denied();return null;});
    }
    public boolean portalSuper(Actor actor,String userId) {if(userId==null)return false;return store.transaction(actor.tenant(),false,tx->management.access(tx.load(),userId).portalRole()==UserRole.SUPER_ADMIN);}
    public Actor portalActor(TenantId tenant,String principal,String userId) {
        return store.transaction(tenant,false,tx->{var access=management.access(tx.load(),userId);return new Actor(tenant,principal,access.portalRole()!=UserRole.BASIC,access.portalRole()==UserRole.SUPER_ADMIN,userId);});
    }
    private void require(Actor actor,Store.Session tx,Directory directory,String action,Directory.Entry entry) {actor.requireAdmin();long now=System.currentTimeMillis();management.requireEntry(directory,actor.userId(),action,entry,now,ConsoleDeviceSessions.current(tx,codec,actor.sessionId(),actor.userId(),now));}
    private Directory validated(Store.Session tx,Directory before,long revision,Map<Ids.RecordId,Directory.Entry> entries) {
        try {var after=new Directory(revision,entries);after.validate(maxRecords,maxBytes);codec.validatePolicies(after);BootstrapAdministrator.validate(tx,before,after,codec);return after;}catch(IllegalArgumentException failure){throw Failure.conflict();}
    }
    private void secretReferences(Store.Session tx,Directory after){for(var secret:tx.enterprise().secrets())if(!after.entries().containsKey(Kind.TOOL_GROUP.id(secret.toolGroupId()))||!after.entries().containsKey(Kind.DEVICE_GROUP.id(secret.deviceGroupId())))throw Failure.conflict();}
    public Store.Reply get(Actor actor,Kind kind,String id) {return store.transaction(actor.tenant(),false,tx->{var d=tx.load();var e=d.entries().get(kind.id(id));if(e==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Record not found");require(actor,tx,d,"read",e);return new Store.Reply(200,e.document(),e.revision());});}
    public Store.Reply page(Actor actor,Kind kind,String cursor,int limit) {
        actor.requireAdmin();if(limit<1||limit>100)throw Failure.validation();String after="";
        if(cursor!=null)try {if(cursor.length()>512)throw Failure.validation();var p=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\n",-1);if(p.length!=3||!p[0].equals(actor.tenant().value())||!p[1].equals(kind.name()))throw Failure.validation();after=Ids.valid(p[2]);}catch(IllegalArgumentException failure){throw Failure.validation();}
        final String position=after;
        return store.transaction(actor.tenant(),false,tx->{var d=tx.load();var rows=d.entries().values().stream().filter(e->e.id().kind()==kind&&e.id().value().compareTo(position)>0).sorted(Comparator.comparing(e->e.id().value())).filter(e->{try {require(actor,tx,d,"read",e);return true;}catch(Failure denied){if(denied.status()!=403)throw denied;return false;}}).limit(limit+1L).toList();var body=new HashMap<String,Object>();body.put("items",rows.stream().limit(limit).map(e->codec.value(e.document())).toList());if(rows.size()>limit)body.put("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString((actor.tenant().value()+"\n"+kind.name()+"\n"+rows.get(limit-1).id().value()).getBytes(StandardCharsets.UTF_8)));return new Store.Reply(200,codec.json(body),d.revision());});
    }
    /** Kinds a backup carries. Users, identity bindings and device evidence stay with the instance and survive Replace. */
    public static boolean exported(Kind kind) {return kind!=Kind.USER&&kind!=Kind.IDENTITY_BINDING&&kind!=Kind.DEVICE_EVIDENCE;}
    /** Users are left out: teams lose userIds and requiredIdentities lists, by ID only, every user an exported record references.
     * Settings are Super Admin data and are included only for a Super Admin. */
    public Store.Reply export(Actor actor,boolean yaml) {actor.requireAdmin();return store.transaction(actor.tenant(),false,tx->{var d=tx.load();management.requireAll(d,actor.userId(),"configuration_backup.export",System.currentTimeMillis());
        var entries=new HashMap<Ids.RecordId,Directory.Entry>();var users=new TreeSet<String>();
        for(var e:d.entries().values()){if(!exported(e.id().kind()))continue;
            if(e.id().kind()==Kind.TEAM){var t=codec.model(e.document(),ControlTeam.class);users.addAll(t.userIds());entries.put(e.id(),codec.entry(Kind.TEAM,codec.json(new ControlTeam(t.id(),t.name(),t.enabled(),t.revision(),List.of(),t.roleIds()))));continue;}
            for(var r:e.references())if(r.kind()==Kind.USER)users.add(r.value());entries.put(e.id(),e);}
        var fields=new HashMap<String,Object>();
        if(management.globallyAllowed(d,actor.userId(),"server_configuration.read",System.currentTimeMillis())&&management.globallyAllowed(d,actor.userId(),"device_configuration.read",System.currentTimeMillis())){var s=new ServerSettingsService(null,codec,ClientCheckInPolicy.QUICKSTART).current(tx);if(s.deviceApproval().ownerUserId()!=null)users.add(s.deviceApproval().ownerUserId());fields.put("settings",new io.ololabs.toolgate.contracts.v2.ControlServerSettingsPatch(s.gateway(),s.deviceApproval(),s.configuration()));}
        fields.put("requiredIdentities",users.stream().map(u->new ControlRequiredIdentity(u,GroupGraph.memberships(d,codec,Kind.USER,u,false))).toList());
        return new Store.Reply(200,codec.snapshot(actor.tenant(),new Directory(d.revision(),entries),yaml,fields),d.revision());});}
    public Store.Reply audit(Actor actor,long after,int limit) {actor.requireAdmin();if(after<0||limit<1||limit>100)throw Failure.validation();return store.transaction(actor.tenant(),false,tx->{management.requireAll(tx.load(),actor.userId(),"audit.read",System.currentTimeMillis());return new Store.Reply(200,tx.auditPage(after,limit),0);});}
    public Store.Reply mutate(Actor actor,Kind kind,String id,String operation,String document,long expected,String key,String requestId) {
        actor.requireAdmin();Ids.valid(key);Ids.valid(requestId);if(!Set.of("CREATE","UPDATE","DELETE").contains(operation))throw Failure.validation();
        var typedId=kind.id(id);if(GroupGraph.protectedDefault(typedId)&&operation.equals("DELETE"))throw Failure.conflict();
        var input=operation.equals("DELETE")?null:codec.entry(kind,document);if(input!=null&&!input.id().equals(typedId))throw Failure.validation();
        var hash=digest(operation+"\n"+kind+"\n"+id+"\n"+expected+"\n"+(input==null?"":input.document()));
        return store.transaction(actor.tenant(),true,tx->{
            var before=tx.load();var old=before.entries().get(typedId);if(input==null&&old==null)throw missingFailure();authorizeMutation(actor,tx,before,old,input,operation);
            packaged(kind,old,input,operation);
            var replay=tx.replay(actor.id(),key,hash);if(replay!=null)return replay;
            lifecycle(tx,old,input);
            if(GroupGraph.group(kind))management.requireAllocation(before,before,actor.userId(),input==null?old:input,System.currentTimeMillis());
            if(operation.equals("CREATE")){if(old!=null||tx.used(typedId)||input==null||input.revision()!=1)throw Failure.conflict();}
            else if(old==null||old.revision()!=expected||input!=null&&input.revision()!=expected)throw Failure.conflict();
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
    /** Tools and their extractors come only from installed packages (ADR 027, docs/contracts/platform-requirements.md): this API may enable, disable or
     *  delete one, never create one or change what it runs, so no script can add a tool outside the package path. */
    private void packaged(Kind kind,Directory.Entry old,Directory.Entry input,String operation){
        if(kind!=Kind.TOOL&&kind!=Kind.EXTRACTOR)return;
        if(operation.equals("CREATE")||operation.equals("UPDATE")&&!Set.of("enabled").containsAll(changedFields(old,input)))
            throw new Failure(ErrorCode.VALIDATION,422,"Tools and extractors come only from installed packages; only enabled can change here");
    }
    /** Shared field/action authorization for direct changes and configuration imports. */
    private void authorizeMutation(Actor actor,Store.Session tx,Directory before,Directory.Entry old,Directory.Entry input,String operation){
        var kind=(input==null?old:input).id().kind();var action=mutationAction(kind,old,input,operation);
        require(actor,tx,before,action,input==null?old:input);if(old!=null)require(actor,tx,before,action,old);
            if(GroupGraph.group(kind)&&input!=null) {
                // Membership and role changes require authority over every affected source/destination group.
                var prior=old==null?List.<String>of():GroupGraph.members(old,codec);var next=GroupGraph.members(input,codec);
                if(!prior.equals(next)){require(actor,tx,before,ManagementPermissionCatalog.resource(kind)+".manage_members",input);for(var member:union(prior,next)) {var e=before.entries().get(GroupGraph.entityKind(kind).id(member));if(e!=null)require(actor,tx,before,"read",e);}}
                if(!GroupGraph.roles(input,codec).equals(old==null?List.of():GroupGraph.roles(old,codec)))require(actor,tx,before,ManagementPermissionCatalog.resource(kind)+".assign_roles",input);
            }
            if(input!=null&&(old==null&&input.enabled()||old!=null&&old.enabled()!=input.enabled()))require(actor,tx,before,input.enabled()?"enable":"disable",input);
            if(kind==Kind.ROLE&&input!=null&&(old==null||changedFields(old,input).stream().anyMatch(Set.of("managementRules","portalRole","roleType")::contains)))require(actor,tx,before,"role.manage_permissions",input);
    }
    private Set<String> changedFields(Directory.Entry before,Directory.Entry after){var a=(com.fasterxml.jackson.databind.JsonNode)codec.value(before.document());var b=(com.fasterxml.jackson.databind.JsonNode)codec.value(after.document());var fields=new HashSet<String>();a.fieldNames().forEachRemaining(fields::add);b.fieldNames().forEachRemaining(fields::add);fields.remove("revision");fields.removeIf(key->Objects.equals(a.get(key),b.get(key)));return fields;}
    private String mutationAction(Kind kind,Directory.Entry before,Directory.Entry after,String operation){
        if(!operation.equals("UPDATE")||before==null||after==null)return operation.toLowerCase(Locale.ROOT);
        var changed=changedFields(before,after);var feature=ManagementPermissionCatalog.resource(kind);
        if(changed.equals(Set.of("enabled")))return feature+(after.enabled()?".enable":".disable");
        if(GroupGraph.group(kind)){var field=switch(kind){case TEAM->"userIds";case AGENT_GROUP->"agentIds";case TOOL_GROUP->"toolIds";case DEVICE_GROUP->"deviceIds";default->throw Failure.validation();};if(changed.equals(Set.of(field)))return feature+".manage_members";if(changed.equals(Set.of("roleIds")))return feature+".assign_roles";}
        if(kind==Kind.ROLE&&!changed.isEmpty()&&Set.of("managementRules","portalRole","roleType").containsAll(changed))return "role.manage_permissions";
        return "update";
    }
    private static boolean inputEnabled(Directory.Entry input) {return input!=null&&input.enabled();}
    private static List<String> union(List<String> a,List<String> b) {var values=new TreeSet<>(a);values.addAll(b);return List.copyOf(values);}
    private void invalidateSessions(Map<Ids.RecordId,Directory.Entry> entries,String user) {
        for(var e:List.copyOf(entries.values()))if(e.id().kind()==Kind.IDENTITY_BINDING) {var b=codec.model(e.document(),ControlIdentityBinding.class);if(b.userId().equals(user))entries.put(e.id(),codec.entry(Kind.IDENTITY_BINDING,codec.json(new ControlIdentityBinding(b.id(),b.name(),b.enabled(),b.revision()+1,b.userId(),b.issuer(),b.subject(),b.sessionEpoch()+1,b.firstSeenUnixMs(),b.lastAttemptUnixMs(),b.attemptCount(),b.registrationReason(),Math.max(b.sessionsValidAfterUnixMs(),lifecycleClock.millis()/1000*1000+1000)))));}
        invalidateWorkloads(entries,null,user);
    }
    private void invalidateWorkloads(Map<Ids.RecordId,Directory.Entry> entries,String agent,String user) {
        for(var entry:List.copyOf(entries.values()))if(entry.id().kind()==Kind.WORKLOAD_BINDING){var b=codec.model(entry.document(),ControlWorkloadBinding.class);
            if(Objects.equals(b.agentId(),agent)||user!=null&&user.equals(b.delegatedUserId()))entries.put(entry.id(),codec.entry(Kind.WORKLOAD_BINDING,codec.json(new ControlWorkloadBinding(b.id(),b.name(),false,b.revision()+1,b.agentId(),b.mode(),b.issuer(),b.subject(),b.audience(),b.credentialSha256(),b.credentialEpoch()+1,b.expiresAtUnixMs(),b.delegatedUserId(),b.parentBindingId(),b.delegatedSessionEpoch(),b.passClaims(),b.needsReauthorization()))));
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
    /** Merge keeps existing records and updates duplicates; Replace also deletes records of exported kinds the file leaves out.
     * Users, team memberships, identity bindings, device evidence and default groups are never deleted. Every required identity
     * must exist or be resolved (EXISTING, MAP or PLACEHOLDER); resolved users are added to their ticked teams. */
    public Store.Reply importConfig(Actor actor,String document,boolean yaml,long expected,String key,String requestId) {
        actor.requireAdmin();Ids.valid(key);Ids.valid(requestId);var input=codec.input(document,yaml,actor.tenant());if(input.directory().revision()!=expected)throw Failure.conflict();var hash=digest("IMPORT\n"+expected+"\n"+codec.document(actor.tenant(),input));
        return store.transaction(actor.tenant(),true,tx->{
            var before=tx.load();management.requireAll(before,actor.userId(),"configuration_backup.import",System.currentTimeMillis());
            // Imported settings use the same feature permissions as direct settings edits.
            if(input.settings()!=null)ServerSettingsService.requirePatch(tx,codec,actor,input.settings());
            var resolved=resolve(before,input);var settings=resolved.settings();
            if(settings!=null&&settings.configuration()!=null){int current=ServerSettingsService.requiredReviews(tx,codec);if(current>0&&settings.configuration().requiredReviews()!=current)throw Failure.detail(409,ControlErrorDetailCode.REQUIRED_REVIEWS_CHANGE,List.of(ServerSettingsService.ENTITY));}
            // Authorization precedes replay; imports may not restore old credential/session epochs.
            for(var e:resolved.entries().values()){var old=before.entries().get(e.id());if(old==null||!changedFields(old,e).isEmpty())authorizeMutation(actor,tx,before,old,e,old==null?"CREATE":"UPDATE");else require(actor,tx,before,"read",old);}
            var replay=input.dryRun()?null:tx.replay(actor.id(),key,hash);if(replay!=null)return replay;if(before.revision()!=expected)throw Failure.conflict();
            var entries=retainedImportEntries(before,input);
            for(var proposed:resolved.entries().values()) {var old=before.entries().get(proposed.id());var e=importObservations(old,proposed);if(old==null&&tx.used(e.id()))throw Failure.conflict();lifecycle(tx,old,e);var normalized=codec.revision(e,old==null?1:old.revision());entries.put(e.id(),old!=null&&old.document().equals(normalized.document())?old:codec.revision(e,old==null?1:old.revision()+1));}
            restoreImportMemberships(before,input,resolved,entries);
            for(var old:before.entries().values())if(!entries.containsKey(old.id()))authorizeMutation(actor,tx,before,old,null,"DELETE");
            for(var e:before.entries().values()){var next=entries.get(e.id());if(GroupGraph.individual(e.id().kind())&&e.enabled()!=inputEnabled(next)){if(e.id().kind()==Kind.USER)invalidateSessions(entries,e.id().value());if(e.id().kind()==Kind.AGENT)invalidateWorkloads(entries,e.id().value(),null);}}
            if(input.replace()){var conflicts=new TreeSet<String>();for(var e:entries.values())for(var r:e.references())if(!entries.containsKey(r))conflicts.add(e.id().kind()+":"+e.id().value());if(!conflicts.isEmpty())throw Failure.detail(409,ControlErrorDetailCode.REPLACE_CONFLICTS,conflicts);}
            // Import is reviewed as a complete graph; missing memberships are rejected rather than guessed.
            var changes=new ArrayList<>(changes(before,new Directory(before.revision(),entries)));var after=validated(tx,before,before.revision()+(changes.isEmpty()||input.dryRun()?0:1),entries);secretReferences(tx,after);
            for(var group:after.entries().values())if(GroupGraph.group(group.id().kind())) {
                var old=before.entries().get(group.id());var prior=old==null?List.<String>of():GroupGraph.members(old,codec);
                if(GroupGraph.members(group,codec).stream().anyMatch(member->!prior.contains(member))||!GroupGraph.roles(group,codec).equals(old==null?List.of():GroupGraph.roles(old,codec)))management.requireAllocation(before,after,actor.userId(),group,System.currentTimeMillis());
            }
            for(var e:after.entries().values()) {var old=before.entries().get(e.id());if(old==null)continue;if(e.id().kind()==Kind.IDENTITY_BINDING&&(codec.model(e.document(),ControlIdentityBinding.class).sessionEpoch()<codec.model(old.document(),ControlIdentityBinding.class).sessionEpoch()||codec.model(e.document(),ControlIdentityBinding.class).sessionsValidAfterUnixMs()<codec.model(old.document(),ControlIdentityBinding.class).sessionsValidAfterUnixMs())||e.id().kind()==Kind.WORKLOAD_BINDING&&codec.model(e.document(),ControlWorkloadBinding.class).credentialEpoch()<codec.model(old.document(),ControlWorkloadBinding.class).credentialEpoch())throw Failure.conflict();}
            boolean settingsChange=settings!=null&&ServerSettingsService.changes(tx,codec,settings);if(settingsChange)changes.add(Map.of("kind","SETTINGS","id",ServerSettingsService.ENTITY,"operation","UPDATE"));
            var reply=new Store.Reply(200,codec.result(!input.dryRun(),after.revision(),changes),after.revision());
            if(!input.dryRun()){tx.save(before,after);if(settingsChange){var saved=ServerSettingsService.apply(tx,codec,settings,null,after);tx.audit(actor.id(),"SERVER_SETTINGS_IMPORT",ServerSettingsService.ENTITY,saved.revision(),requestId,hash);}auditChanges(tx,actor,before,after,requestId,"IMPORT",hash);tx.remember(actor.id(),key,hash,reply);}return reply;
        });
    }
    private record Resolved(Map<Ids.RecordId,Directory.Entry> entries,Map<String,Set<String>> teams,io.ololabs.toolgate.contracts.v2.ControlServerSettingsPatch settings){}
    /** Local identity state and protected defaults survive replacement, including read-only shadow projection. */
    private HashMap<Ids.RecordId,Directory.Entry> retainedImportEntries(Directory before,Codec.Import input) {
        var entries=new HashMap<Ids.RecordId,Directory.Entry>();
        for(var e:before.entries().values())if(!input.replace()||!exported(e.id().kind())||GroupGraph.protectedDefault(e.id()))entries.put(e.id(),e);
        return entries;
    }
    /** Memberships are only added: existing members stay, resolved users join their ticked teams, removal is explicit. */
    private void restoreImportMemberships(Directory before,Codec.Import input,Resolved resolved,Map<Ids.RecordId,Directory.Entry> entries) {
        for(var team:List.copyOf(entries.values()))if(team.id().kind()==Kind.TEAM) {
            var members=new LinkedHashSet<String>();var old=before.entries().get(team.id());if(old!=null)members.addAll(GroupGraph.members(old,codec));members.addAll(GroupGraph.members(team,codec));
            for(var r:resolved.teams().entrySet()){if(r.getValue().contains(team.id().value()))members.add(r.getKey());else if(input.removeUnlistedMemberships()&&!GroupGraph.protectedDefault(team.id()))members.remove(r.getKey());}
            for(var r:resolved.teams().values())for(var id:r)if(!entries.containsKey(Kind.TEAM.id(id)))throw Failure.validation();
            if(!List.copyOf(members).equals(GroupGraph.members(team,codec))){var t=codec.model(team.document(),ControlTeam.class);var next=codec.entry(Kind.TEAM,codec.json(new ControlTeam(t.id(),t.name(),t.enabled(),t.revision(),List.copyOf(members),t.roleIds())));entries.put(team.id(),old!=null&&old.document().equals(codec.revision(next,old.revision()).document())?old:codec.revision(next,old==null?1:old.revision()+1));}
        }
        for(var e:List.copyOf(entries.values()))if(e.id().kind()==Kind.USER&&GroupGraph.memberships(new Directory(before.revision(),entries),codec,Kind.USER,e.id().value(),false).isEmpty())GroupGraph.addDefault(entries,codec,Kind.USER,e.id().value());
    }
    private static final Set<String> USER_FIELDS=Set.of("ownerUserId","delegatedUserId","userId");
    /** Resolves required identities before anything is applied: MAP rewrites user references, PLACEHOLDER adds a disabled user
     * without credentials, and delegated bindings of either arrive disabled until their credential is rotated. */
    private Resolved resolve(Directory before,Codec.Import input) {
        var file=input.directory().entries();var unresolved=new TreeSet<String>();var mapped=new HashMap<String,String>();var placeholders=new TreeSet<String>();var teams=new TreeMap<String,Set<String>>();
        java.util.function.Predicate<String> exists=id->before.entries().containsKey(Kind.USER.id(id))||file.containsKey(Kind.USER.id(id));
        for(var required:input.requiredIdentities()) {
            var r=input.identityResolution().get(required.id());String target=required.id();
            if(r==null){if(!exists.test(target))unresolved.add(target);continue;}
            switch(r.action()) {
                case EXISTING->{if(!exists.test(target)){unresolved.add(target);continue;}}
                case MAP->{target=r.targetUserId();if(target==null||!exists.test(target)){unresolved.add(required.id());continue;}if(!target.equals(required.id()))mapped.put(required.id(),target);}
                case PLACEHOLDER->{if(!exists.test(target))placeholders.add(target);}
            }
            if(r.teamIds()!=null)teams.computeIfAbsent(target,k->new TreeSet<>()).addAll(r.teamIds());
        }
        if(!unresolved.isEmpty())throw Failure.detail(409,ControlErrorDetailCode.UNRESOLVED_IDENTITIES,unresolved);
        var entries=new HashMap<Ids.RecordId,Directory.Entry>();
        for(var e:file.values()) {
            if(e.id().kind()==Kind.USER&&mapped.containsKey(e.id().value()))continue;
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)codec.value(e.document());
            if(e.id().kind()==Kind.WORKLOAD_BINDING&&node.has("delegatedUserId")&&(mapped.containsKey(node.get("delegatedUserId").asText())||placeholders.contains(node.get("delegatedUserId").asText()))){node.put("enabled",false);node.put("needsReauthorization",true);}
            if(!mapped.isEmpty()){for(var field:USER_FIELDS)if(node.has(field)&&mapped.containsKey(node.get(field).asText()))node.put(field,mapped.get(node.get(field).asText()));
                if(node.has("userIds")&&e.id().kind()==Kind.TEAM){var ids=new LinkedHashSet<String>();node.get("userIds").forEach(id->ids.add(mapped.getOrDefault(id.asText(),id.asText())));var array=node.putArray("userIds");ids.forEach(array::add);}}
            var entry=codec.entry(e.id().kind(),codec.json(node));entries.put(entry.id(),entry);
        }
        for(var id:placeholders){var user=codec.entry(Kind.USER,codec.json(new ControlUser(id,id,false,1L)));entries.putIfAbsent(user.id(),user);}
        var settings=input.settings();var approval=settings==null?null:settings.deviceApproval();
        if(approval!=null&&approval.ownerUserId()!=null&&mapped.containsKey(approval.ownerUserId()))settings=new io.ololabs.toolgate.contracts.v2.ControlServerSettingsPatch(settings.gateway(),new io.ololabs.toolgate.contracts.v2.DeviceApproval(approval.autoApprove(),approval.durationMs(),mapped.get(approval.ownerUserId())),settings.configuration());
        return new Resolved(entries,teams,settings);
    }
    /** An authorized administrator installs a new token hash: the epoch increments, a delegated binding is rebound to its user's current session
     * epoch, needsReauthorization clears, and the Gateway context for the token is returned. Any previous token stops working. */
    public Store.Reply rotateCredential(Actor actor,String id,String document,String key,String requestId) {
        actor.requireAdmin();Ids.valid(id);Ids.valid(key);Ids.valid(requestId);var request=codec.model(document,ControlWorkloadCredentialRotation.class);var hash=digest("CREDENTIAL\n"+id+"\n"+codec.json(request));
        return store.transaction(actor.tenant(),true,tx->{
            var before=tx.load();var old=before.entries().get(Kind.WORKLOAD_BINDING.id(id));if(old==null)throw missingFailure();require(actor,tx,before,"workload_binding.rotate_credential",old);var replay=tx.replay(actor.id(),key,hash);if(replay!=null)return replay;var b=codec.model(old.document(),ControlWorkloadBinding.class);
            if(request.bindingId()!=null&&!before.entries().containsKey(Kind.BINDING.id(request.bindingId())))throw Failure.validation();if(b.credentialSha256().equals(request.credentialSha256()))throw Failure.conflict();
            boolean delegated=b.mode()==io.ololabs.toolgate.contracts.v2.RequestMode.DELEGATED;
            Long session=!delegated?b.delegatedSessionEpoch():before.entries().values().stream().filter(e->e.id().kind()==Kind.IDENTITY_BINDING&&e.enabled()).map(e->codec.model(e.document(),ControlIdentityBinding.class)).filter(i->i.userId().equals(b.delegatedUserId())).map(ControlIdentityBinding::sessionEpoch).max(Long::compare).orElseThrow(Failure::conflict);
            var next=codec.entry(Kind.WORKLOAD_BINDING,codec.json(new ControlWorkloadBinding(b.id(),b.name(),request.enable(),b.revision()+1,b.agentId(),b.mode(),b.issuer(),b.subject(),b.audience(),request.credentialSha256(),b.credentialEpoch()+1,b.expiresAtUnixMs(),b.delegatedUserId(),b.parentBindingId(),session,b.passClaims(),null)));
            lifecycle(tx,old,next);var entries=new HashMap<>(before.entries());entries.put(next.id(),next);var after=validated(tx,before,before.revision()+1,entries);tx.save(before,after);auditChanges(tx,actor,before,after,requestId,"WORKLOAD_CREDENTIAL_ROTATE",hash);
            var chain=new ArrayList<io.ololabs.toolgate.contracts.v2.ActorHop>();for(var parent=b.parentBindingId();parent!=null;){var p=codec.model(after.entries().get(Kind.WORKLOAD_BINDING.id(parent)).document(),ControlWorkloadBinding.class);chain.addFirst(new io.ololabs.toolgate.contracts.v2.ActorHop(p.agentId(),p.id()));parent=p.parentBindingId();if(chain.size()>8)throw Failure.conflict();}
            var binding=codec.model(next.document(),ControlWorkloadBinding.class);
            var execution=request.bindingId()!=null?request.bindingId():executionBinding(after,b.agentId());
            var context=new io.ololabs.toolgate.contracts.v2.RequestContext(id,actor.tenant().value(),b.mode(),io.ololabs.toolgate.contracts.v2.CredentialKind.STATIC_TOKEN,null,delegated?b.delegatedUserId():null,b.agentId(),id,chain,delegated?session:null,binding.credentialEpoch(),null,null,execution,request.credentialSha256());
            var reply=new Store.Reply(200,codec.json(new ControlWorkloadCredentialResult(binding,context)),binding.revision());tx.remember(actor.id(),key,hash,reply);return reply;
        });
    }
    /** The one enabled execution binding that the agent groups' CAPABILITY or SERVICE grants select, or null when none or several do. */
    private String executionBinding(Directory d,String agent) {
        var groups=GroupGraph.memberships(d,codec,Kind.AGENT,agent,true);var result=new TreeSet<String>();
        for(var g:d.entries().values())if(g.id().kind()==Kind.GRANT&&g.enabled()){var grant=codec.model(g.document(),ControlAccessGrant.class);if(grant.sourceType()!=EnterpriseSourceType.AGENT_GROUP||!groups.contains(grant.sourceId())||grant.purpose()!=EnterpriseGrantPurpose.CAPABILITY&&grant.purpose()!=EnterpriseGrantPurpose.SERVICE)continue;
            for(var e:d.entries().values())if(e.id().kind()==Kind.BINDING&&e.enabled()){var x=codec.model(e.document(),ControlExecutionBinding.class);if(EnterpriseEvaluator.selected(grant.scope().toolGroups(),x.toolGroupId())&&EnterpriseEvaluator.selected(grant.scope().deviceGroups(),x.deviceGroupId()))result.add(x.id());}}
        return result.size()==1?result.first():null;
    }
    /** Super Admin enables or disables a user; either change ends the user's sessions and delegated workload tokens. */
    public Store.Reply userEnabled(Actor actor,String id,boolean enabled,String key,String requestId) {
        actor.requireAdmin();Ids.valid(id);
        var user=store.transaction(actor.tenant(),false,tx->{var d=tx.load();var e=d.entries().get(Kind.USER.id(id));if(e==null)throw missingFailure();require(actor,tx,d,enabled?"enable":"disable",e);return codec.model(e.document(),ControlUser.class);});
        return mutate(actor,Kind.USER,id,"UPDATE",codec.json(new ControlUser(user.id(),user.name(),enabled,user.revision(),user.email(),user.showOnLogin(),user.claims())),user.revision(),key,requestId);
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
    public Store.Reply memberships(Actor actor,Kind kind,String id) {return store.transaction(actor.tenant(),false,tx->{var d=tx.load();var e=d.entries().get(kind.id(id));if(e==null)throw missingFailure();require(actor,tx,d,"read",e);return membership(d,kind,id);});}
    private static Failure missingFailure() {return new Failure(ErrorCode.NOT_FOUND,404,"Record not found");}
    private Store.Reply membership(Directory d,Kind kind,String id) {return new Store.Reply(200,codec.json(new GroupMembership(EnterpriseMemberType.valueOf(kind.name()),id,GroupGraph.memberships(d,codec,kind,id,false),d.revision())),d.revision());}
    public Store.Reply setMemberships(Actor actor,Kind kind,String id,String document,long expected,String key,String requestId) {
        actor.requireAdmin();Ids.valid(key);Ids.valid(requestId);var input=codec.model(document,GroupMembership.class);if(!id.equals(input.entityId())||!kind.name().equals(input.entityType().name())||input.revision()!=expected)throw Failure.validation();var hash=digest("MEMBERSHIPS\n"+codec.json(input));
        return store.transaction(actor.tenant(),true,tx->{var before=tx.load();var entity=before.entries().get(kind.id(id));if(entity==null)throw missingFailure();require(actor,tx,before,kind==Kind.DEVICE?"device.manage_groups":"read",entity);var previous=GroupGraph.memberships(before,codec,kind,id,false);var changedGroups=new HashSet<>(previous);changedGroups.addAll(input.groupIds());for(var group:changedGroups){if(previous.contains(group)==input.groupIds().contains(group))continue;var entry=before.entries().get(GroupGraph.groupKind(kind).id(group));if(entry==null)throw missingFailure();require(actor,tx,before,ManagementPermissionCatalog.resource(GroupGraph.groupKind(kind))+".manage_members",entry);management.requireAllocation(before,before,actor.userId(),entry,System.currentTimeMillis());}var replay=tx.replay(actor.id(),key,hash);if(replay!=null)return replay;if(before.revision()!=expected)throw Failure.conflict();var entries=new HashMap<>(before.entries());GroupGraph.setMemberships(entries,codec,kind,id,input.groupIds());var after=validated(tx,before,before.revision()+1,entries);tx.save(before,after);auditChanges(tx,actor,before,after,requestId,"MEMBERSHIP_UPDATE",hash);var reply=membership(after,kind,id);tx.remember(actor.id(),key,hash,reply);return reply;});
    }
    public Store.Reply simulate(Actor actor,String document) {actor.requireAdmin();var input=codec.model(document,EnterpriseEvaluation.class);return store.transaction(actor.tenant(),false,tx->{management.requireAll(tx.load(),actor.userId(),"simulation.run",System.currentTimeMillis());var decision=new EnterpriseEvaluator(codec).evaluate(actor.tenant(),tx.load(),input);return new Store.Reply(200,codec.json(decision),tx.load().revision());});}
    public Store.Reply shadow(Actor actor,String document){actor.requireAdmin();var request=codec.model(document,EnterpriseShadowRequest.class);return store.transaction(actor.tenant(),false,tx->{management.requireAll(tx.load(),actor.userId(),"simulation.run",lifecycleClock.millis());
        var input=codec.input(codec.json(Map.of("snapshot",request.snapshot(),"mode","REPLACE","dryRun",true)),false,actor.tenant());
        var before=tx.load();var resolved=resolve(before,input);var entries=retainedImportEntries(before,input);
        for(var entry:resolved.entries().values())entries.put(entry.id(),importObservations(before.entries().get(entry.id()),entry));
        restoreImportMemberships(before,input,resolved,entries);
        var proposed=new Directory(input.directory().revision(),entries);proposed.validate(maxRecords,maxBytes);codec.validatePolicies(proposed);
        var decision=new EnterpriseEvaluator(codec).evaluateWithQuotas(actor.tenant(),proposed,request.evaluation(),budget->tx.enterprise().budgetUsage(budget,Math.max(0,request.evaluation().nowUnixMs()-60000),null));
        boolean expansion=request.observedLegacyDecision()==Decision.BLOCK&&decision.decision()!=Decision.BLOCK||request.observedLegacyDecision()==Decision.ASK&&decision.decision()==Decision.ALLOW;
        return new Store.Reply(200,codec.json(new EnterpriseShadowResult(decision,request.observedLegacyDecision(),request.legacyEvidenceDigest(),digest(codec.json(request.snapshot())),expansion)),tx.load().revision());});}
    public static String digest(String value) {return digest(value.getBytes(StandardCharsets.UTF_8));}
    public static String digest(byte[] value) {try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
