// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.time.Clock;
import java.security.cert.X509Certificate;
import java.util.*;
import static io.ololabs.toolgate.control.application.FleetStore.Kind.*;

/** Signed desired state: authority, audit and generations commit together; observations never grant access. */
public final class FleetService {
    private static final long MAX=9007199254740991L;
    private final Store store; private final Codec codec; private final FleetCrypto crypto;
    private final EndpointService endpoints; private final Clock clock;
    private final Ids.TenantId tenant; private final String server; private final boolean enabled;
    public FleetService(Store store,Codec codec,FleetCrypto crypto,EndpointService endpoints,Clock clock,
                        boolean enabled,String tenant,String server){
        this.store=store;this.codec=codec;this.crypto=crypto;this.endpoints=endpoints;this.clock=clock;
        this.enabled=enabled;this.tenant=new Ids.TenantId(tenant);this.server=Ids.valid(server);
    }
    private void available(){if(!enabled)throw Failure.unavailable();}
    private void admin(DirectoryService.Actor actor){available();actor.requireAdmin();if(!actor.tenant().equals(tenant))throw forbidden();}
    private static Failure forbidden(){return new Failure(ErrorCode.FORBIDDEN,403,"Fleet assignment rejected");}
    private long now(Store.Session tx){long now=clock.millis();if(now<0||now>MAX-86400000||tx.approvalClock(now)>now)throw Failure.unavailable();return now;}
    private Store.Reply reply(Object document,long revision){return new Store.Reply(200,codec.json(document),revision);}
    private static long next(long revision){if(revision>=MAX)throw Failure.conflict();return revision+1;}
    private static String releaseId(String packageId,String version){return DirectoryService.digest(packageId+"\n"+version);}
    private void permission(Store.Session tx,DirectoryService.Actor actor,String action,FleetPackageDocument document){
        var access=new ManagementAccess(codec);var graph=tx.load();
        for(var tool:document.tools())access.requireEntry(graph,actor.userId(),"package."+action,codec.entry(Ids.Kind.TOOL,codec.json(tool.authorizationProfile().tool())),clock.millis());
    }
    private void rolloutPermission(Store.Session tx,DirectoryService.Actor actor,String action,FleetRolloutRecord rollout){
        permission(tx,actor,action,crypto.release(release(tx,rollout.packageId(),rollout.version()).release()).model());
        for(var member:rollout.members())endpoints.management(tx,actor,"package."+action,member.deviceId());
    }
    private FleetPackageRelease release(Store.Session tx,String packageId,String version){var row=tx.fleet().get(RELEASE,releaseId(packageId,version));if(row==null)throw Failure.conflict();return codec.model(row.document(),FleetPackageRelease.class);}
    public Store.Reply publish(DirectoryService.Actor actor,String body,String key,String requestId){
        admin(actor);Ids.valid(key);Ids.valid(requestId);var release=codec.model(body,FleetPackageRelease.class);
        var verified=crypto.release(release.release());var document=verified.model();
        if(!document.packageId().equals(release.packageId())||!document.version().equals(release.version())
            ||verified.bytes().length!=release.sizeBytes()||!digest(verified.bytes()).equals(release.manifestDigest()))throw Failure.validation();
        validatePackage(document);
        var digest=DirectoryService.digest("fleet.publish\n"+body);
        return store.transaction(tenant,true,tx->{now(tx);permission(tx,actor,"publish",document);var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return replay;
            if(tx.fleet().count(RELEASE)>=128)throw Failure.conflict();
            tx.fleet().save(RELEASE,new FleetStore.Row(releaseId(release.packageId(),release.version()),1,codec.json(release)),0);
            tx.audit(actor.id(),"PACKAGE_RELEASE",release.packageId(),1,requestId,digest);
            var result=reply(release,1);tx.remember(actor.id(),key,digest,result);return result;
        });
    }
    /** No install hooks. All tools, runtimes and confined self-tests must form a closed graph. */
    public static void validatePackage(FleetPackageDocument document){
        var runtimes=new HashSet<String>();for(var runtime:document.runtimes())if(!runtimes.add(runtime.id()))throw Failure.validation();
        var tools=new HashSet<String>();for(var tool:document.tools())if(!tools.add(tool.toolId())||!runtimes.contains(tool.runtimeId()))throw Failure.validation();
        for(var tool:document.tools()){var runtime=document.runtimes().stream().filter(r->r.id().equals(tool.runtimeId())).findFirst().orElseThrow(Failure::validation);BuilderValidation.source(runtime,tool);InstalledProfiles.validate(tool,runtime);}
        var tested=new HashSet<String>();for(var test:document.selfTests())if(!tools.contains(test.toolId())||!tested.add(test.toolId()))throw Failure.validation();
        if(!tested.equals(tools)||new HashSet<>(document.platforms()).size()!=document.platforms().size()
            ||new HashSet<>(document.architectures()).size()!=document.architectures().size())throw Failure.validation();
    }
    public static String digest(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
    /** Immutable release identity must equal the sealed builder package before a deploy action. */
    public void requireRelease(DirectoryService.Actor actor,FleetPackageDocument document){admin(actor);store.transaction(tenant,false,tx->{permission(tx,actor,"read",document);if(!crypto.release(release(tx,document.packageId(),document.version()).release()).model().equals(document))throw forbidden();return null;});}
    public Store.Reply releases(DirectoryService.Actor actor,String after){admin(actor);if(after!=null&&!after.matches("[a-f0-9]{64}"))throw Failure.validation();
        return store.transaction(tenant,false,tx->{new ManagementAccess(codec).access(tx.load(),actor.userId());var rows=tx.fleet().page(RELEASE,after==null?"":after,33);var items=rows.stream().limit(32).map(r->codec.model(r.document(),FleetPackageRelease.class)).filter(r->{try{permission(tx,actor,"read",crypto.release(r.release()).model());return true;}catch(Failure denied){if(denied.status()!=403)throw denied;return false;}}).toList();return reply(new FleetReleasePage(items,rows.size()>32?rows.get(31).id():null),0);});}
    private FleetDesiredSnapshot snapshot(Store.Session tx,String device){var row=tx.fleet().get(DESIRED,device);return row==null?new FleetDesiredSnapshot(device,1L,List.of()):codec.model(row.document(),FleetDesiredSnapshot.class);}
    private EndpointDeviceRecord active(Store.Session tx,String id){var row=tx.endpoint(id);if(row==null)throw forbidden();var device=codec.model(row.document(),EndpointDeviceRecord.class);endpoints.active(tx,device);return device;}
    /** Stable hash order makes increasing percentages retain the initial canary cohort. */
    public static int selected(int total,long percentage){return (int)((total*percentage+99)/100);}
    private FleetRolloutRecord assign(Store.Session tx,FleetRolloutRecord rollout,long percentage,String creator){
        var release=release(tx,rollout.packageId(),rollout.version());
        var selected=selected(rollout.members().size(),percentage);var members=new ArrayList<FleetRolloutMember>();int index=0;
        for(var member:rollout.members()){
            if(index++>=selected||member.generation()>0){members.add(member);continue;}
            active(tx,member.deviceId());var before=tx.fleet().get(DESIRED,member.deviceId());var current=snapshot(tx,member.deviceId());
            var assignments=new ArrayList<>(current.assignments());assignments.removeIf(a->a.release().packageId().equals(rollout.packageId()));
            if(assignments.size()>=16)throw Failure.conflict();assignments.add(new FleetAssignment(release,rollout.desiredPresence()));
            assignments.sort(Comparator.comparing(a->a.release().packageId()));
            // Prevent tool/runtime identifiers from crossing package ownership boundaries.
            var tools=new HashSet<String>();var runtimes=new HashSet<String>();
            for(var assignment:assignments)if(assignment.desiredPresence()){
                var doc=crypto.release(assignment.release().release()).model();
                for(var tool:doc.tools())if(!tools.add(tool.toolId()))throw Failure.conflict();
                for(var runtime:doc.runtimes())if(!runtimes.add(runtime.id()))throw Failure.conflict();
            }
            long generation=before==null?1:next(before.revision());var desired=new FleetDesiredSnapshot(member.deviceId(),generation,assignments);
            codec.model(codec.json(desired),FleetDesiredSnapshot.class);
            if(codec.json(desired).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000)throw Failure.validation();
            tx.fleet().save(DESIRED,new FleetStore.Row(member.deviceId(),generation,codec.json(desired)),before==null?0:before.revision());
            tx.enterprise().rememberCreator("FLEET",DirectoryService.digest(member.deviceId()+"\n"+release.manifestDigest()),creator);
            members.add(new FleetRolloutMember(member.deviceId(),generation));
        }
        return new FleetRolloutRecord(rollout.id(),rollout.packageId(),rollout.version(),rollout.desiredPresence(),percentage,rollout.revision(),rollout.createdAtUnixMs(),members);
    }
    public Store.Reply rollout(DirectoryService.Actor actor,String body,String key,String requestId){
        admin(actor);Ids.valid(key);Ids.valid(requestId);var request=codec.model(body,FleetRolloutRequest.class);
        if(new HashSet<>(request.deviceIds()).size()!=request.deviceIds().size())throw Failure.validation();
        var digest=DirectoryService.digest("fleet.rollout\n"+body);
        return store.transaction(tenant,true,tx->{long now=now(tx);permission(tx,actor,"deploy",crypto.release(release(tx,request.packageId(),request.version()).release()).model());for(var device:request.deviceIds())endpoints.management(tx,actor,"package.deploy",device);var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return replay;
            if(tx.fleet().count(ROLLOUT)>=256)throw Failure.conflict();
            for(var id:request.deviceIds())active(tx,id);
            var members=request.deviceIds().stream().sorted(Comparator.comparing(id->DirectoryService.digest(request.id()+"\n"+id))).map(id->new FleetRolloutMember(id,0L)).toList();
            var rollout=assign(tx,new FleetRolloutRecord(request.id(),request.packageId(),request.version(),request.desiredPresence(),request.percentage(),1L,now,members),request.percentage(),actor.userId());
            tx.fleet().save(ROLLOUT,new FleetStore.Row(rollout.id(),1,codec.json(rollout)),0);
            tx.audit(actor.id(),"PACKAGE_ASSIGN",rollout.id(),1,requestId,digest);var result=reply(rollout,1);tx.remember(actor.id(),key,digest,result);return result;
        });
    }
    public Store.Reply advance(DirectoryService.Actor actor,String id,String body,String key,String requestId){
        admin(actor);Ids.valid(id);Ids.valid(key);Ids.valid(requestId);var request=codec.model(body,FleetRolloutAdvance.class);var digest=DirectoryService.digest("fleet.advance\n"+id+"\n"+body);
        return store.transaction(tenant,true,tx->{now(tx);var row=tx.fleet().get(ROLLOUT,id);if(row==null)throw Failure.conflict();rolloutPermission(tx,actor,"deploy",codec.model(row.document(),FleetRolloutRecord.class));var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return replay;
            if(!java.util.Objects.equals(row.revision(),request.expectedRevision()))throw Failure.conflict();
            var before=codec.model(row.document(),FleetRolloutRecord.class);if(request.percentage()<=before.percentage())throw Failure.conflict();
            // A superseded canary cannot silently assign its old release to remaining devices.
            for(var member:before.members())if(member.generation()>0&&!matches(snapshot(tx,member.deviceId()),before))throw Failure.conflict();
            var updated=assign(tx,new FleetRolloutRecord(id,before.packageId(),before.version(),before.desiredPresence(),request.percentage(),next(row.revision()),before.createdAtUnixMs(),before.members()),request.percentage(),actor.userId());
            tx.fleet().save(ROLLOUT,new FleetStore.Row(id,updated.revision(),codec.json(updated)),row.revision());
            tx.audit(actor.id(),"ROLLOUT_ADVANCE",id,updated.revision(),requestId,digest);var result=reply(updated,updated.revision());tx.remember(actor.id(),key,digest,result);return result;
        });
    }
    private static boolean matches(FleetDesiredSnapshot desired,FleetRolloutRecord rollout){return desired.assignments().stream().anyMatch(a->a.release().packageId().equals(rollout.packageId())&&a.release().version().equals(rollout.version())&&a.desiredPresence().equals(rollout.desiredPresence()));}
    private FleetRolloutStatus status(Store.Session tx,FleetRolloutRecord rollout,long now){long ready=0,failed=0,offline=0,waiting=0,pending=0,superseded=0;
        for(var member:rollout.members()){
            if(member.generation()==0){pending++;continue;}
            var desired=snapshot(tx,member.deviceId());if(!matches(desired,rollout)){superseded++;continue;}
            var row=tx.endpoint(member.deviceId());var device=row==null?null:codec.model(row.document(),EndpointDeviceRecord.class);
            if(device==null||device.state()!=EndpointState.ACTIVE){failed++;continue;}
            if(device.lastSeenUnixMs()==0||now-device.lastSeenUnixMs()>180000){offline++;continue;}
            var report=device.report();if(report==null||!report.appliedRevision().equals(desired.generation())){waiting++;continue;}
            var pkg=report.packages().stream().filter(p->p.packageId().equals(rollout.packageId())&&p.version().equals(rollout.version())).findFirst().orElse(null);
            if(pkg==null){waiting++;continue;}
            if(pkg.state()==PackageState.FAILED||pkg.state()==PackageState.REVOKED)failed++;
            else if(pkg.state()==(rollout.desiredPresence()?PackageState.READY:PackageState.ABSENT))ready++;
            else waiting++;
        }
        return new FleetRolloutStatus(rollout,ready,failed,offline,waiting,pending,superseded);
    }
    public Store.Reply rollouts(DirectoryService.Actor actor,String after){admin(actor);if(after!=null)Ids.valid(after);
        return store.transaction(tenant,false,tx->{new ManagementAccess(codec).access(tx.load(),actor.userId());long now=clock.millis();var rows=tx.fleet().page(ROLLOUT,after==null?"":after,33);return reply(new FleetRolloutPage(rows.stream().limit(32).map(r->codec.model(r.document(),FleetRolloutRecord.class)).filter(r->{try{rolloutPermission(tx,actor,"read",r);return true;}catch(Failure denied){if(denied.status()!=403)throw denied;return false;}}).map(r->status(tx,r,now)).toList(),rows.size()>32?rows.get(31).id():null),0);});}
    private void currentCreator(Store.Session tx,String device,FleetAssignment assignment){
        String user=tx.enterprise().creator("FLEET",DirectoryService.digest(device+"\n"+assignment.release().manifestDigest()));
        if(user==null)throw forbidden();
        var actor=new DirectoryService.Actor(tenant,DirectoryService.digest("queued\n"+user),true,false,user);
        permission(tx,actor,"deploy",crypto.release(assignment.release().release()).model());
        endpoints.management(tx,actor,"package.deploy",device);
    }
    public Store.Reply desired(X509Certificate peer){available();return store.transaction(tenant,true,tx->{long now=now(tx);var device=endpoints.authenticate(tx,peer,now);var row=tx.fleet().get(DESIRED,device.deviceId());
        if(row==null){var initial=snapshot(tx,device.deviceId());tx.fleet().save(DESIRED,new FleetStore.Row(device.deviceId(),1,codec.json(initial)),0);}
        var desired=snapshot(tx,device.deviceId());for(var assignment:desired.assignments())currentCreator(tx,device.deviceId(),assignment);return reply(crypto.desired(new FleetDesiredDocument(1L,tenant.value(),server,device.deviceId(),desired.generation(),now,now+300000,desired.assignments())),desired.generation());});}
    private FleetPackageRelease assigned(Store.Session tx,String device,long generation,String digest){var desired=snapshot(tx,device);for(var assignment:desired.assignments())currentCreator(tx,device,assignment);if(desired.generation()!=generation)throw Failure.conflict();
        return desired.assignments().stream().filter(a->a.desiredPresence()&&a.release().manifestDigest().equals(digest)).map(FleetAssignment::release).findFirst().orElseThrow(FleetService::forbidden);}
    public Store.Reply grant(X509Certificate peer,String body,String requestId){available();Ids.valid(requestId);var request=codec.model(body,FleetArtifactGrantRequest.class);
        return store.transaction(tenant,true,tx->{long now=now(tx);var device=endpoints.authenticate(tx,peer,now);var release=assigned(tx,device.deviceId(),request.generation(),request.manifestDigest());
            var claims=new FleetArtifactGrantClaims(tenant.value(),server,device.deviceId(),request.generation(),release.manifestDigest(),release.sizeBytes(),UUID.randomUUID().toString(),now,now+60000);
            tx.audit(device.keyFingerprint(),"ARTIFACT_GRANT",release.packageId(),request.generation(),requestId,DirectoryService.digest(body));return reply(crypto.grant(claims),request.generation());});}
    /** Repeat under the download's transaction; grants alone cannot outlive assignment or revocation. */
    public FleetPackageRelease authorizeDownload(Store.Session tx,X509Certificate peer,FleetSignedDocument signed){available();long now=now(tx);var device=endpoints.authenticate(tx,peer,now);var claims=crypto.grant(signed);
        if(!claims.tenantId().equals(tenant.value())||!claims.serverId().equals(server)||!claims.deviceId().equals(device.deviceId())||claims.issuedAtUnixMs()>now||claims.expiresAtUnixMs()<=now||claims.expiresAtUnixMs()-claims.issuedAtUnixMs()>60000)throw forbidden();
        var release=assigned(tx,device.deviceId(),claims.generation(),claims.manifestDigest());if(!release.sizeBytes().equals(claims.sizeBytes()))throw forbidden();return release;}
    public byte[] download(X509Certificate peer,String body,ArtifactStore artifacts){available();var signed=codec.model(body,FleetSignedDocument.class);
        return store.transaction(tenant,true,tx->{var release=authorizeDownload(tx,peer,signed);var bytes=artifacts.download(release.manifestDigest(),release.sizeBytes());
            if(bytes.length!=release.sizeBytes()||!digest(bytes).equals(release.manifestDigest())||!Arrays.equals(bytes,crypto.release(release.release()).bytes()))throw Failure.unavailable();return bytes;});}
    /** Old reports remain observations; only exact current assignments may report READY/ABSENT. */
    public static void validateReport(Store.Session tx,Codec codec,ClientReport report){var row=tx.fleet().get(DESIRED,report.deviceId());
        if(row==null){if(report.appliedRevision()!=0||!report.packages().isEmpty())throw forbidden();return;}
        var desired=codec.model(row.document(),FleetDesiredSnapshot.class);if(report.appliedRevision()>desired.generation())throw forbidden();
        var ids=new HashSet<String>();for(var pkg:report.packages()){
            if(!ids.add(pkg.packageId()))throw Failure.validation();
            if(report.appliedRevision().equals(desired.generation())){
                var assignment=desired.assignments().stream().filter(a->a.release().packageId().equals(pkg.packageId())&&a.release().version().equals(pkg.version())).findFirst().orElseThrow(FleetService::forbidden);
                if(pkg.state()==PackageState.READY&&!assignment.desiredPresence()||pkg.state()==PackageState.ABSENT&&assignment.desiredPresence())throw forbidden();
            }
        }
    }
}
