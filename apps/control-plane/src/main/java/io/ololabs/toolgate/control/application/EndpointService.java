// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.time.Clock;
import java.util.*;

/** Enrollment and check-in use cases; all authoritative mutable state is transactional. */
public final class EndpointService {
    // Client executables carry the product version; the contract library has its own version.
    private static final String MINIMUM_CLIENT_VERSION=productVersion();
    private static String productVersion() {
        try(var input=EndpointService.class.getResourceAsStream("/META-INF/toolgate-product.version")) {
            if(input==null)throw new IllegalStateException("Product version required");
            String version=new String(input.readNBytes(129),java.nio.charset.StandardCharsets.US_ASCII).trim();
            if(version.length()>128 || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))throw new IllegalStateException("Product version required");
            return version;
        }catch(java.io.IOException failure){throw new IllegalStateException("Product version required");}
    }
    private final Store store;
    private final Codec codec;
    private final DeviceIssuer issuer;
    private final Clock clock;
    private final Ids.TenantId tenant;
    private final String server,organization,control,gateway;
    private final boolean enabled;
    private ClientCheckInPolicy checkInPolicy=ClientCheckInPolicy.QUICKSTART;
    private EnterpriseOperations operations;
    /** Env-owned check-in policy; the fixed 500 ms Quickstart behaviour until configured. */
    public EndpointService checkInPolicy(ClientCheckInPolicy value){checkInPolicy=java.util.Objects.requireNonNull(value);if(operations!=null)operations.checkInPolicy(value);return this;}
    public ClientCheckInPolicy checkInPolicy(){return checkInPolicy;}
    public EndpointService operations(EnterpriseOperations value){operations=java.util.Objects.requireNonNull(value).checkInPolicy(checkInPolicy);return this;}
    public McpService relay(){available();if(operations==null)throw Failure.unavailable();return new McpService(store,codec,this,operations,clock,tenant,server);}
    public EndpointService(Store store,Codec codec,DeviceIssuer issuer,Clock clock,boolean enabled,
                           String tenant,String server,String organization,String control,String gateway) {
        this.store=store;this.codec=codec;this.issuer=issuer;this.clock=clock;this.enabled=enabled;
        this.tenant=new Ids.TenantId(tenant);this.server=Ids.valid(server);this.organization=organization;
        this.control=origin(control);this.gateway=origin(gateway);
    }
    /** Fixed HTTPS origins exclude redirects, userinfo, paths and fragments. */
    public static String origin(String value) {
        try {var uri=java.net.URI.create(value);
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null
                || uri.getRawFragment()!=null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
            return "https://"+uri.getRawAuthority();
        }catch(IllegalArgumentException e){throw new IllegalArgumentException("HTTPS server origin required");}
    }
    private void available(){if(!enabled)throw Failure.unavailable();}
    private static final class Observation {long time=-1;}
    private <T> T transaction(java.util.function.BiFunction<Store.Session,Long,T> action) {
        available();var observation=new Observation();
        try {return store.transaction(tenant,true,tx->{long now=clock.millis();if(now<0||now>9007199254740991L)throw Failure.unavailable();
            observation.time=Math.max(now,tx.approvalClock(now));if(observation.time>now)throw Failure.unavailable();return action.apply(tx,now);});}
        catch(RuntimeException failure){if(observation.time>=0)store.transaction(tenant,true,tx->{tx.approvalClock(observation.time);return null;});throw failure;}
    }
    private Directory.Entry enabledUser(Store.Session tx,String user) {
        var entry=tx.load().entries().get(Ids.Kind.USER.id(user));if(entry==null||!entry.enabled())throw forbidden();return entry;
    }
    private static Failure forbidden(){return new Failure(ErrorCode.FORBIDDEN,403,"Device identity unavailable");}
    /** Retryable access suspension must not make older clients permanently revoke their identity. */
    private static Failure suspended(){return new Failure(ErrorCode.FORBIDDEN,423,"Device access is not approved or enabled");}
    private static Failure enrollmentRequired(){return new Failure(ErrorCode.NOT_FOUND,410,"Device was deleted; enroll again for approval");}
    private boolean deleted(Store.Session tx,EndpointDeviceRecord device){return device.state()!=EndpointState.REVOKED&&(device.state()==EndpointState.UNENROLLED||!tx.load().entries().containsKey(Ids.Kind.DEVICE.id(device.deviceId())));}
    private Long connectionDeadline(Long expires,Boolean unlimited,long now) {
        if(Boolean.TRUE.equals(unlimited)){if(expires!=null)throw Failure.validation();return null;}
        long deadline=expires==null?now+86400000:expires;
        if(deadline>9007199254740991L||deadline/1000*1000<=now)throw Failure.validation();return deadline;
    }
    private static long approvalRevision(EndpointDeviceRecord device){return device.approvalRevision()==null?1:device.approvalRevision();}
    private void human(DirectoryService.Actor actor,String user,Store.Session tx) {
        if(!actor.tenant().equals(tenant)||user==null||!user.equals(actor.userId()))throw forbidden();Ids.valid(user);enabledUser(tx,user);
    }
    public void management(Store.Session tx,DirectoryService.Actor actor,String action,String deviceId){
        actor.requireAdmin();if(!actor.tenant().equals(tenant)||actor.userId()==null)throw forbidden();
        var entry=tx.load().entries().get(Ids.Kind.DEVICE.id(deviceId));var access=new ManagementAccess(codec);
        if(entry==null)access.require(tx.load(),actor.userId(),action,Ids.Kind.DEVICE_GROUP,"default-devices",clock.millis());else access.requireEntry(tx.load(),actor.userId(),action,entry,clock.millis());
    }
    /** Approval through a group rule returns null; an ownDevices rule returns the group approved devices are placed in.
     *  Under ownDevices only the caller's own devices, or a new enrollment approved by its code, may be approved. */
    private String approver(Store.Session tx,DirectoryService.Actor actor,String deviceId,boolean byCode){
        try{management(tx,actor,"approve-device",deviceId);return null;}catch(Failure denied){if(denied.status()!=403)throw denied;}
        var group=new ManagementAccess(codec).ownDeviceGroup(tx.load(),actor.userId(),clock.millis());var entry=tx.load().entries().get(Ids.Kind.DEVICE.id(deviceId));
        if(group==null||(entry==null?!byCode:!codec.model(entry.document(),ControlDevice.class).ownerUserId().equals(actor.userId())))throw ManagementAccess.denied();return group;
    }
    /** User codes and key fingerprints go only to callers who may approve the device through a group rule. */
    private boolean groupApprover(Store.Session tx,DirectoryService.Actor actor,String deviceId){try{management(tx,actor,"approve-device",deviceId);return true;}catch(Failure denied){if(denied.status()!=403)throw denied;return false;}}
    /** Callers whose only device approval is ownDevices see no pending enrollments and only the devices they own. */
    private boolean ownOnly(Store.Session tx,DirectoryService.Actor actor){var access=new ManagementAccess(codec);return access.ownDeviceGroup(tx.load(),actor.userId(),clock.millis())!=null&&!access.superAdministrator(tx.load(),actor.userId())&&access.groupRules(tx.load(),actor.userId()).noneMatch(r->r.actions().contains("approve-device"));}
    private static EndpointEnrollmentReview redacted(EndpointEnrollmentReview r){return new EndpointEnrollmentReview(r.enrollmentId(),null,r.deviceId(),r.platform(),null,r.state(),r.expiresAtUnixMs(),r.connectionExpiresAtUnixMs(),r.unlimitedConnection());}
    private static EndpointDeviceRecord redacted(EndpointDeviceRecord d){return new EndpointDeviceRecord(d.deviceId(),d.tenantId(),d.userId(),null,d.state(),d.revision(),d.lastSeenUnixMs(),d.reportSequence(),d.report(),d.connectionExpiresAtUnixMs(),d.connectionApproved(),d.approvalRevision(),d.systemName(),d.ipAddress());}
    /** An ownDevices approval leaves the device in exactly the rule's group. */
    private void ownMembership(Store.Session tx,String deviceId,String group){var before=tx.load();var entries=new HashMap<>(before.entries());GroupGraph.setMemberships(entries,codec,Ids.Kind.DEVICE,deviceId,List.of(group));var after=new Directory(before.revision()+1,entries);after.validate(512,1048576);tx.save(before,after);}
    private final java.util.concurrent.ConcurrentHashMap<String,ArrayDeque<Long>> failures=new java.util.concurrent.ConcurrentHashMap<>();
    /** Five failed code lookups or approvals per user in ten minutes, then 429 until the oldest failure ages out. */
    private <T> T limited(DirectoryService.Actor actor,java.util.function.Supplier<T> work){
        var times=failures.computeIfAbsent(actor.tenant().value()+"/"+actor.userId(),k->new ArrayDeque<>());long now=clock.millis();
        synchronized(times){while(!times.isEmpty()&&times.peekFirst()<=now-600000)times.pollFirst();if(times.size()>=5)throw Failure.detail(429,ControlErrorDetailCode.RATE_LIMITED,List.of());}
        try{return work.get();}catch(Failure failure){if(failure.status()==403||failure.status()==404||failure.status()==409)synchronized(times){times.addLast(now);}throw failure;}
    }
    private Store.Reply reply(Object body,long revision){return new Store.Reply(200,codec.json(body),revision);}
    private static String random(int bytes){byte[] value=new byte[bytes];new java.security.SecureRandom().nextBytes(value);return HexFormat.of().formatHex(value);}
    private String verification(){return control+"/console/#enroll";}
    public Store.Reply discovery(){available();long now=clock.millis();
        var manifest=new ClientDiscovery(1L,server,organization,tenant.value(),control,gateway,verification(),MINIMUM_CLIENT_VERSION,now,now+300000,issuer.issuerCertificate());
        return reply(issuer.discovery(codec.json(manifest)),0);
    }
    /** A signed CSR proves the announced key even before enrollment or after certificate expiry. */
    public Store.Reply ping(String body){
        available();var presence=codec.model(body,EndpointPresencePing.class);var ping=presence.announcement();var fingerprint=issuer.fingerprint(ping.csrPem());
        return transaction((tx,now)->{
            var row=tx.endpoint(ping.deviceId());var knownKey=tx.endpointKey(fingerprint);
            if((row!=null&&!row.fingerprint().equals(fingerprint))||(knownKey!=null&&!knownKey.id().equals(ping.deviceId())))throw forbidden();
            var device=row==null?null:codec.model(row.document(),EndpointDeviceRecord.class);
            EndpointState state;EndpointConnectionAction action;
            if(device!=null&&device.state()==EndpointState.REVOKED){state=EndpointState.REVOKED;action=EndpointConnectionAction.WAIT;}
            else if(device==null||deleted(tx,device)){
                var latest=tx.enrollments(now).stream().map(e->codec.model(e.document(),EndpointEnrollmentReview.class)).filter(e->e.deviceId().equals(ping.deviceId())&&e.keyFingerprint().equals(fingerprint)).reduce((a,b)->b).orElse(null);
                state=latest!=null&&latest.state()==EnrollmentState.PENDING?EndpointState.PENDING:EndpointState.UNENROLLED;
                action=latest!=null&&latest.state()==EnrollmentState.DENIED?EndpointConnectionAction.WAIT:EndpointConnectionAction.ENROLL;
            }else{
                try{active(tx,device);state=EndpointState.ACTIVE;action=EndpointConnectionAction.CHECK_IN;}
                catch(Failure denied){if(denied.status()!=423)throw denied;state=EndpointState.OFFLINE;action=EndpointConnectionAction.WAIT;}
            }
            long interval=state==EndpointState.REVOKED?Math.max(500L,3600000L-Math.floorMod(now+java.time.ZoneOffset.of(presence.utcOffset()).getTotalSeconds()*1000L,3600000L)):action==EndpointConnectionAction.CHECK_IN?checkInPolicy.nextIntervalMs():5000L;
            return reply(new EndpointConnectionDirective(ping.deviceId(),state,action,device==null?0L:device.reportSequence(),interval),0);
        });
    }
    public Store.Reply start(String body,String requestId) {
        return start(body,requestId,false);
    }
    /** Explicit possession-proved request after revocation; it never grants approval. */
    public Store.Reply requestApproval(String body,String requestId) {
        return start(body,requestId,true);
    }
    private EndpointDeviceRecord resetForApproval(Store.Session tx,Store.EndpointRecord row,EndpointDeviceRecord device){
        var reset=new EndpointDeviceRecord(device.deviceId(),device.tenantId(),device.userId(),device.keyFingerprint(),EndpointState.UNENROLLED,device.revision()+1,0L,device.reportSequence(),null,null,false,approvalRevision(device)+1,null,null);
        tx.deleteEnrollments(device.deviceId());
        tx.saveEndpoint(new Store.EndpointRecord(row.id(),row.fingerprint(),codec.json(reset),row.csr(),row.reportDigest(),row.acknowledgment()));
        return reset;
    }
    private Store.Reply start(String body,String requestId,boolean requestAgain) {
        available();
        var start=codec.model(body,EndpointEnrollmentStart.class);var fingerprint=issuer.fingerprint(start.csrPem());Ids.valid(requestId);
        return transaction((tx,now)->{
            tx.pruneEnrollments(now);if(tx.pendingEnrollments(now)>=32)throw Failure.conflict();
            var registered=tx.endpoint(start.deviceId());var knownKey=tx.endpointKey(fingerprint);
            if((registered!=null&&!registered.fingerprint().equals(fingerprint))||(knownKey!=null&&!knownKey.id().equals(start.deviceId())))throw requestAgain?forbidden():Failure.conflict();
            EndpointDeviceRecord device=registered==null?null:codec.model(registered.document(),EndpointDeviceRecord.class);
            if(requestAgain&&(device==null||(device.state()!=EndpointState.REVOKED&&!deleted(tx,device))))throw Failure.conflict();
            boolean reenrollment=device!=null&&(deleted(tx,device)||(requestAgain&&device.state()==EndpointState.REVOKED));
            if(reenrollment){
                // Also recover devices deleted before the deletion state was persisted.
                if(device.state()!=EndpointState.UNENROLLED){
                    device=resetForApproval(tx,registered,device);
                    tx.audit(fingerprint,"ENROLLMENT_REQUEST_AGAIN","endpoint:"+device.deviceId(),device.revision(),requestId,DirectoryService.digest(body));
                }
                device=null;
            }
            if(device!=null)active(tx,device);
            if(tx.load().entries().values().stream().noneMatch(e->e.id().kind()==Ids.Kind.USER&&e.enabled()))throw forbidden();
            String id=random(16),deviceCode=random(32),userCode=random(8).toUpperCase(Locale.ROOT);long expires=now+600000;
            // Administrator-enabled auto-approval registers a new key for a bounded period under the configured owner.
            var settings=new ServerSettingsService(store,codec,checkInPolicy).current(tx);
            String autoOwner=device==null&&!reenrollment?ServerSettingsService.autoApprovalOwner(tx,settings):null;
            Long autoExpires=autoOwner==null?null:Math.min(9007199254740991L,now+ServerSettingsService.autoApprovalMs(settings));
            var review=new EndpointEnrollmentReview(id,userCode,start.deviceId(),start.platform(),fingerprint,device==null&&autoOwner==null?EnrollmentState.PENDING:EnrollmentState.APPROVED,expires,device==null?autoExpires:device.connectionExpiresAtUnixMs(),device==null?(autoOwner==null?null:Boolean.FALSE):Boolean.valueOf(device.connectionExpiresAtUnixMs()==null));
            // A verified CSR for the exact approved registered key can recover its public certificate.
            // It cannot change approval, owner, activation, key or connection deadline.
            String certificate=device!=null?codec.json(issuer.issue(start.csrPem(),device.deviceId(),tenant.value(),device.userId(),server,now,device.connectionExpiresAtUnixMs()==null?Long.MAX_VALUE:device.connectionExpiresAtUnixMs()))
                :autoOwner!=null?register(tx,review,start.csrPem(),autoOwner,autoExpires,now):null;
            tx.saveEnrollment(new Store.EnrollmentRecord(id,DirectoryService.digest(userCode),DirectoryService.digest(deviceCode),codec.json(review),start.csrPem(),device!=null?device.userId():autoOwner,certificate,expires,0));
            tx.audit(fingerprint,device==null?"ENROLLMENT_CREATE":"DEVICE_IDENTITY_RECOVER","endpoint:"+start.deviceId(),1,requestId,DirectoryService.digest(codec.json(start)));
            if(autoOwner!=null)tx.audit(fingerprint,"ENROLLMENT_AUTO_APPROVE","endpoint:"+start.deviceId(),1,requestId,DirectoryService.digest(codec.json(settings)));
            return reply(new EndpointEnrollmentChallenge(id,deviceCode,userCode,verification(),expires,5L),1);
        });
    }
    public Store.Reply review(DirectoryService.Actor actor,String user,String code) {
        if(code==null||!code.matches("[A-F0-9]{16}"))throw Failure.validation();
        return transaction((tx,now)->{human(actor,user,tx);var row=tx.enrollmentCode(DirectoryService.digest(code));
            if(row==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Enrollment not found");
            var review=codec.model(row.document(),EndpointEnrollmentReview.class);
            management(tx,actor,"read",review.deviceId());
            if(row.expiresAt()<=now)return reply(withState(review,EnrollmentState.EXPIRED),1);
            return reply(review,1);
        });
    }
    public Store.Reply pending(DirectoryService.Actor actor,String user) {
        return transaction((tx,now)->{human(actor,user,tx);if(ownOnly(tx,actor))return reply(new EndpointEnrollmentPage(List.of()),0);
            var items=tx.enrollments(now).stream().map(row->codec.model(row.document(),EndpointEnrollmentReview.class))
                .filter(review->review.state()==EnrollmentState.PENDING).filter(review->{try{management(tx,actor,"read",review.deviceId());return true;}catch(Failure denied){if(denied.status()==403)return false;throw denied;}}).map(review->groupApprover(tx,actor,review.deviceId())?review:redacted(review)).toList();
            return reply(new EndpointEnrollmentPage(items),0);
        });
    }
    private EndpointEnrollmentReview withState(EndpointEnrollmentReview r,EnrollmentState state){return new EndpointEnrollmentReview(r.enrollmentId(),r.userCode(),r.deviceId(),r.platform(),r.keyFingerprint(),state,r.expiresAtUnixMs(),r.connectionExpiresAtUnixMs(),r.unlimitedConnection());}
    public Store.Reply decide(DirectoryService.Actor actor,String user,String body,String key,String requestId) {
        var decision=codec.model(body,EndpointEnrollmentDecision.class);Ids.valid(key);Ids.valid(requestId);var digest=DirectoryService.digest(body);
        return limited(actor,()->transaction((tx,now)->{
            human(actor,user,tx);var row=tx.enrollmentCode(DirectoryService.digest(decision.userCode()));if(row==null)throw Failure.conflict();
            var review=codec.model(row.document(),EndpointEnrollmentReview.class);
            var own=approver(tx,actor,review.deviceId(),true);var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return replay;
            if(row.expiresAt()<=now)throw Failure.conflict();
            if(review.state()!=EnrollmentState.PENDING||!review.keyFingerprint().equals(decision.keyFingerprint()))throw Failure.conflict();
            boolean approve=decision.choice()==EnrollmentChoice.APPROVE;
            Long connectionExpires=approve?connectionDeadline(decision.connectionExpiresAtUnixMs(),decision.unlimitedConnection(),now):null;
            var state=approve?EnrollmentState.APPROVED:EnrollmentState.DENIED;String certificate=null;
            if(approve&&own!=null){var group=tx.load().entries().get(Ids.Kind.DEVICE_GROUP.id(own));
                if(group!=null&&GroupGraph.members(group,codec).stream().map(id->tx.load().entries().get(Ids.Kind.DEVICE.id(id))).anyMatch(d->d!=null&&codec.model(d.document(),ControlDevice.class).ownerUserId().equals(user)))throw Failure.detail(409,ControlErrorDetailCode.ONE_DEVICE_PER_ACCOUNT,List.of(user));}
            if(approve)certificate=register(tx,review,row.csr(),user,connectionExpires,now);
            if(approve&&own!=null)ownMembership(tx,review.deviceId(),own);
            var updated=new EndpointEnrollmentReview(review.enrollmentId(),review.userCode(),review.deviceId(),review.platform(),review.keyFingerprint(),state,review.expiresAtUnixMs(),connectionExpires,approve?connectionExpires==null:null);tx.saveEnrollment(new Store.EnrollmentRecord(row.id(),row.codeDigest(),row.deviceDigest(),codec.json(updated),row.csr(),approve?user:null,certificate,row.expiresAt(),row.lastPoll()));
            tx.audit(actor.id(),approve?"ENROLLMENT_APPROVE":"ENROLLMENT_DENY","endpoint:"+review.deviceId(),1,requestId,digest);
            var result=reply(own==null?updated:redacted(updated),1);tx.remember(actor.id(),key,digest,result);return result;
        }));
    }
    /** The pending enrollment a valid code names, for callers who may approve it; anything else is 404 without detail. */
    public Store.Reply lookup(DirectoryService.Actor actor,String user,String body){
        var request=codec.model(body,EndpointEnrollmentLookup.class);
        return limited(actor,()->transaction((tx,now)->{human(actor,user,tx);var row=tx.enrollmentCode(DirectoryService.digest(request.userCode()));var missing=new Failure(ErrorCode.NOT_FOUND,404,"Enrollment not found");
            if(row==null||row.expiresAt()<=now)throw missing;var review=codec.model(row.document(),EndpointEnrollmentReview.class);if(review.state()!=EnrollmentState.PENDING)throw missing;
            try{approver(tx,actor,review.deviceId(),true);}catch(Failure denied){if(denied.status()==403)throw missing;throw denied;}
            return reply(new EndpointEnrollmentLookupResult(review.enrollmentId(),review.deviceId(),review.platform(),review.keyFingerprint(),review.expiresAtUnixMs()),1);}));
    }
    /** Issues the first identity for an approved key and creates its bounded endpoint and directory records atomically. */
    private String register(Store.Session tx,EndpointEnrollmentReview review,String csr,String user,Long connectionExpires,long now){
        var existing=tx.endpoint(review.deviceId());var knownKey=tx.endpointKey(review.keyFingerprint());
        var previousDevice=existing==null?null:codec.model(existing.document(),EndpointDeviceRecord.class);
        boolean reenrollment=previousDevice!=null&&previousDevice.state()==EndpointState.UNENROLLED&&existing.fingerprint().equals(review.keyFingerprint());
        if((existing!=null&&!reenrollment)||(knownKey!=null&&(!reenrollment||!knownKey.id().equals(review.deviceId()))))throw Failure.conflict();
        var identity=issuer.issue(csr,review.deviceId(),tenant.value(),user,server,now,connectionExpires==null?Long.MAX_VALUE:connectionExpires);
        // Preserve the sequence across reenrollment so old reports cannot be replayed.
        var endpoint=new EndpointDeviceRecord(review.deviceId(),tenant.value(),user,review.keyFingerprint(),EndpointState.ACTIVE,reenrollment?previousDevice.revision()+1:1L,0L,reenrollment?previousDevice.reportSequence():0L,null,connectionExpires,true,reenrollment?approvalRevision(previousDevice)+1:1L,null,null);
        tx.saveEndpoint(new Store.EndpointRecord(review.deviceId(),review.keyFingerprint(),codec.json(endpoint),csr,"0".repeat(64),"{}"));
        // Directory metadata cannot grant device credentials; enrollment creates the matching bounded record atomically.
        var before=tx.load();var id=Ids.Kind.DEVICE.id(review.deviceId());var previous=before.entries().get(id);
        if(previous==null){
            if(tx.used(id)&&!reenrollment)throw Failure.conflict();
            var entry=codec.entry(Ids.Kind.DEVICE,codec.json(new ControlDevice(review.deviceId(),"Enrolled "+review.platform(),true,1L,user)));
            var entries=new HashMap<>(before.entries());entries.put(id,entry);DeviceGroups.addDefault(entries,codec,review.deviceId());var after=new Directory(before.revision()+1,entries);after.validate(512,1048576);tx.save(before,after);
        }
        return codec.json(identity);
    }
    public Store.Reply poll(String body,String requestId) {
        var poll=codec.model(body,EndpointEnrollmentPoll.class);Ids.valid(requestId);
        return transaction((tx,now)->{var row=tx.enrollment(poll.enrollmentId());
            if(row==null||!java.security.MessageDigest.isEqual(row.deviceDigest().getBytes(java.nio.charset.StandardCharsets.US_ASCII),DirectoryService.digest(poll.deviceCode()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw forbidden();
            if(row.expiresAt()<=now)return reply(new EndpointEnrollmentResult(EnrollmentState.EXPIRED,null,serverName(tx)),1);
            if(row.lastPoll()!=0 && now-row.lastPoll()<5000)throw Failure.conflict();
            var review=codec.model(row.document(),EndpointEnrollmentReview.class);var state=review.state();DeviceIdentity identity=null;
            if(state==EnrollmentState.APPROVED || state==EnrollmentState.CONSUMED){
                var device=tx.endpoint(review.deviceId());if(device==null||codec.model(device.document(),EndpointDeviceRecord.class).state()!=EndpointState.ACTIVE)throw forbidden();
                active(tx,codec.model(device.document(),EndpointDeviceRecord.class));identity=codec.model(row.certificate(),DeviceIdentity.class);
                if(identity.expiresAtUnixMs()<=now)throw forbidden();
                // Exact code may recover a lost response until challenge expiry; it cannot issue another identity.
                if(state==EnrollmentState.APPROVED)tx.audit(review.keyFingerprint(),"ENROLLMENT_CONSUME","endpoint:"+review.deviceId(),1,requestId,DirectoryService.digest(row.id()));
                state=EnrollmentState.CONSUMED;
            }
            tx.saveEnrollment(new Store.EnrollmentRecord(row.id(),row.codeDigest(),row.deviceDigest(),codec.json(withState(review,state)),row.csr(),row.userId(),row.certificate(),row.expiresAt(),now));
            return reply(new EndpointEnrollmentResult(state,identity,serverName(tx)),1);
        });
    }
    public Store.Reply checkIn(java.security.cert.X509Certificate peer,String body,String requestId) {
        return checkIn(peer,body,requestId,false);
    }
    public String issuerCertificate() { available();return issuer.issuerCertificate(); }
    public void verifySocketPeer(java.security.cert.X509Certificate peer) {
        transaction((tx,now)->{authenticate(tx,peer,now);return null;});
    }
    /** Configured gateway name sent to devices in enrollment and check-in replies. */
    private String serverName(Store.Session tx){return new ServerSettingsService(store,codec,checkInPolicy).current(tx).gateway().gatewayName();}
    private Store.Reply interval(Store.Reply reply,boolean milliseconds,String serverName) {
        var ack=codec.model(reply.body(),EndpointCheckInAck.class);
        long now=clock.millis();var identity=ack.identity()!=null&&ack.identity().expiresAtUnixMs()<=now?null:ack.identity();
        // Preserve exact recent replies, but a lost response recovered after a long outage must
        // not reinstall an expired certificate or fail the client's current clock-skew check.
        long serverTime=now-ack.serverTimeUnixMs()>300000?now:ack.serverTimeUnixMs();
        long next=checkInPolicy.nextIntervalMs();
        var negotiated=new EndpointCheckInAck(ack.deviceId(),ack.sequence(),serverTime,checkInPolicy.nextIntervalSeconds(next),milliseconds?next:null,identity,ack.adoption(),ack.task(),serverName);
        return new Store.Reply(reply.status(),codec.json(negotiated),reply.revision());
    }
    public Store.Reply checkIn(java.security.cert.X509Certificate peer,String body,String requestId,boolean milliseconds) {
        return checkIn(peer,body,requestId,milliseconds,null,null);
    }
    public Store.Reply checkIn(java.security.cert.X509Certificate peer,String body,String requestId,boolean milliseconds,String systemName,String ipAddress) {
        available();
        if(systemName!=null&&(systemName.length()>255||!systemName.matches("[a-zA-Z0-9._-]+")))throw Failure.validation();
        if(ipAddress!=null&&(ipAddress.length()>45||!ipAddress.matches("[0-9a-fA-F:.]{2,45}")))throw Failure.validation();
        var check=codec.model(body,EndpointCheckIn.class);Ids.valid(requestId);var fingerprint=issuer.peerFingerprint(peer,clock.millis());
        var digest=DirectoryService.digest(codec.json(check));
        return transaction((tx,now)->{var row=tx.endpointKey(fingerprint);if(row==null)throw forbidden();
            var device=codec.model(row.document(),EndpointDeviceRecord.class);active(tx,device);
            if(!device.deviceId().equals(check.report().deviceId()))throw forbidden();
            if(check.report().appliedRevision()!=0||!check.report().packages().isEmpty())FleetService.validateReport(tx,codec,check.report());
            if(check.sequence().equals(device.reportSequence())){if(!digest.equals(row.reportDigest()))throw Failure.conflict();return interval(new Store.Reply(200,row.acknowledgment(),device.revision()),milliseconds,serverName(tx));}
            if(check.sequence()!=device.reportSequence()+1)throw Failure.conflict();
            // Only the device's owner switches its power; the report that says so is sent at once.
            boolean off=DevicePlacement.poweredOff(check.report()),switched=off!=DevicePlacement.poweredOff(device.report());
            // Allow the configured cycle with its jitter and transport jitter; reject request bursts.
            if(!switched&&device.reportSequence()>0 && now-device.lastSeenUnixMs()<checkInPolicy.burstGuardMs())throw Failure.conflict();
            // A certificate already capped at the approved deadline needs no repeated renewal.
            long connectionExpires=device.connectionExpiresAtUnixMs()==null?Long.MAX_VALUE:device.connectionExpiresAtUnixMs();
            DeviceIdentity renewed=null;if(peer.getNotAfter().getTime()-now<43200000&&peer.getNotAfter().getTime()<connectionExpires/1000*1000)renewed=issuer.issue(row.csr(),device.deviceId(),tenant.value(),device.userId(),server,now,connectionExpires);
            var configuration=new EndpointAdoptions(codec).poll(tx,device,server,check.adoptionDigest(),check.localTools());
            var task=off?null:relay().poll(tx,device,now,requestId);
            var ack=new EndpointCheckInAck(device.deviceId(),check.sequence(),now,2L,null,renewed,configuration,task,null);
            var updated=new EndpointDeviceRecord(device.deviceId(),tenant.value(),device.userId(),fingerprint,EndpointState.ACTIVE,device.revision()+1,now,check.sequence(),check.report(),device.connectionExpiresAtUnixMs(),device.connectionApproved(),device.approvalRevision(),systemName==null?device.systemName():systemName,ipAddress==null?device.ipAddress():ipAddress);
            var response=reply(ack,updated.revision());tx.saveEndpoint(new Store.EndpointRecord(row.id(),row.fingerprint(),codec.json(updated),row.csr(),digest,response.body()));
            tx.audit(fingerprint,renewed==null?"DEVICE_CHECK_IN":"DEVICE_RENEW","endpoint:"+row.id(),updated.revision(),requestId,digest);
            if(switched)tx.audit(fingerprint,off?"DEVICE_POWER_OFF":"DEVICE_POWER_ON","endpoint:"+row.id(),updated.revision(),requestId,digest);return interval(response,milliseconds,serverName(tx));
        });
    }
    public EndpointDeviceRecord authenticate(Store.Session tx,java.security.cert.X509Certificate peer,long now){
        available();var fingerprint=issuer.peerFingerprint(peer,now);var row=tx.endpointKey(fingerprint);
        if(row==null)throw forbidden();var device=codec.model(row.document(),EndpointDeviceRecord.class);active(tx,device);return device;
    }
    public void active(Store.Session tx,EndpointDeviceRecord device){
        if(deleted(tx,device))throw enrollmentRequired();
        if(device.state()!=EndpointState.ACTIVE)throw forbidden();
        if(Boolean.FALSE.equals(device.connectionApproved())||(device.connectionExpiresAtUnixMs()!=null&&device.connectionExpiresAtUnixMs()<=clock.millis()))throw suspended();
        var entry=tx.load().entries().get(Ids.Kind.DEVICE.id(device.deviceId()));
        if(entry==null)throw forbidden();
        if(!entry.enabled())throw suspended();
    }
    public Store.Reply device(DirectoryService.Actor actor,String id) {
        actor.requireAdmin();if(!actor.tenant().equals(tenant))throw forbidden();return transaction((tx,now)->{management(tx,actor,"read",id);var row=tx.endpoint(Ids.valid(id));if(row==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Device not found");var device=codec.model(row.document(),EndpointDeviceRecord.class);
            if(ownOnly(tx,actor)&&!device.userId().equals(actor.userId()))throw ManagementAccess.denied();return reply(groupApprover(tx,actor,id)?device:redacted(device),0);});
    }
    public Store.Reply devices(DirectoryService.Actor actor) {
        actor.requireAdmin();if(!actor.tenant().equals(tenant))throw forbidden();
        return transaction((tx,now)->{
            var items=new TreeMap<String,EndpointManagedDevice>();var entries=tx.load().entries();boolean ownOnly=ownOnly(tx,actor);
            for(var entry:entries.values())if(entry.id().kind()==Ids.Kind.DEVICE){
                try{management(tx,actor,"read",entry.id().value());}catch(Failure denied){if(denied.status()==403)continue;throw denied;}
                var directory=codec.model(entry.document(),ControlDevice.class);var row=tx.endpoint(directory.id());if(ownOnly&&!directory.ownerUserId().equals(actor.userId()))continue;var secrets=groupApprover(tx,actor,directory.id());
                var kind=tenant.value().equals("quickstart")&&server.equals("quickstart-server")&&directory.ownerUserId().equals("local-tools")?switch(directory.id()){
                    case "local-builtins"->SystemExecutorKind.BUILTINS;case "local-hotfolder"->SystemExecutorKind.HOTFOLDER;case "local-rest-forwarding"->SystemExecutorKind.REST_FORWARDING;default->null;
                }:null;
                var owner=entries.get(Ids.Kind.USER.id(directory.ownerUserId()));
                items.put(directory.id(),new EndpointManagedDevice(directory.id(),kind!=null,directory,row==null?null:secrets?codec.model(row.document(),EndpointDeviceRecord.class):redacted(codec.model(row.document(),EndpointDeviceRecord.class)),null,owner==null?null:codec.model(owner.document(),ControlUser.class),kind,null,row==null?null:codec.model(row.document(),EndpointDeviceRecord.class).systemName(),row==null?null:codec.model(row.document(),EndpointDeviceRecord.class).ipAddress()));
            }
            for(var row:tx.enrollments(now)){
                var review=codec.model(row.document(),EndpointEnrollmentReview.class);var endpoint=tx.endpoint(review.deviceId());
                if(ownOnly||review.state()!=EnrollmentState.PENDING||(endpoint!=null&&codec.model(endpoint.document(),EndpointDeviceRecord.class).state()!=EndpointState.UNENROLLED))continue;
                try{management(tx,actor,"read",review.deviceId());}catch(Failure denied){if(denied.status()==403)continue;throw denied;}
                var previous=items.get(review.deviceId());
                items.put(review.deviceId(),new EndpointManagedDevice(review.deviceId(),false,previous==null?null:previous.directoryDevice(),null,groupApprover(tx,actor,review.deviceId())?review:redacted(review),previous==null?null:previous.registeredUser(),null,null,null,null));
            }
            return reply(new EndpointManagedDevicePage(List.copyOf(items.values())),0);
        });
    }
    public Store.Reply approval(DirectoryService.Actor actor,String user,String id,String body,String key,String requestId) {
        actor.requireAdmin();Ids.valid(id);Ids.valid(key);Ids.valid(requestId);
        var request=codec.model(body,EndpointApprovalRequest.class);var digest=DirectoryService.digest("approval\n"+id+"\n"+body);
        return limited(actor,()->transaction((tx,now)->{human(actor,user,tx);var own=approver(tx,actor,id,false);var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return replay;
            var row=tx.endpoint(id);if(row==null)throw Failure.conflict();var device=codec.model(row.document(),EndpointDeviceRecord.class);
            if(request.expectedApprovalRevision()!=approvalRevision(device)||device.state()!=EndpointState.ACTIVE)throw Failure.conflict();
            // Administrative reapproval never reassigns the identity or reenables directory access.
            var entry=tx.load().entries().get(Ids.Kind.DEVICE.id(id));if(entry==null)throw Failure.conflict();
            Long expires=request.approved()?connectionDeadline(request.connectionExpiresAtUnixMs(),request.unlimitedConnection(),now):device.connectionExpiresAtUnixMs();
            var updated=new EndpointDeviceRecord(id,tenant.value(),device.userId(),device.keyFingerprint(),device.state(),device.revision()+1,device.lastSeenUnixMs(),device.reportSequence(),device.report(),expires,request.approved(),approvalRevision(device)+1,device.systemName(),device.ipAddress());
            tx.saveEndpoint(new Store.EndpointRecord(id,row.fingerprint(),codec.json(updated),row.csr(),row.reportDigest(),row.acknowledgment()));
            if(own!=null&&request.approved())ownMembership(tx,id,own);
            tx.audit(actor.id(),request.approved()?"DEVICE_APPROVE":"DEVICE_DEAPPROVE","endpoint:"+id,updated.revision(),requestId,digest);
            var result=reply(own==null?updated:redacted(updated),updated.revision());tx.remember(actor.id(),key,digest,result);return result;
        }));
    }
    public Store.Reply enabled(DirectoryService.Actor actor,String user,String id,String body,String key,String requestId) {
        actor.requireAdmin();Ids.valid(id);Ids.valid(key);Ids.valid(requestId);
        var request=codec.model(body,EndpointEnabledRequest.class);var digest=DirectoryService.digest("enabled\n"+id+"\n"+body);
        return transaction((tx,now)->{human(actor,user,tx);management(tx,actor,request.enabled()?"enable":"disable",id);var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return replay;
            var before=tx.load();var recordId=Ids.Kind.DEVICE.id(id);var entry=before.entries().get(recordId);
            ControlDevice directory;
            if(entry==null){
                var endpoint=tx.endpoint(id);
                boolean reenrollment=endpoint!=null&&codec.model(endpoint.document(),EndpointDeviceRecord.class).state()==EndpointState.UNENROLLED;
                if(request.expectedRevision()!=0||(tx.used(recordId)&&!reenrollment))throw Failure.conflict();
                var pending=tx.enrollments(now).stream().map(row->codec.model(row.document(),EndpointEnrollmentReview.class)).filter(review->review.deviceId().equals(id)&&review.state()==EnrollmentState.PENDING).findFirst().orElseThrow(Failure::conflict);
                directory=new ControlDevice(id,pending.platform()+" client",request.enabled(),1L,user);
            }else{
                var current=codec.model(entry.document(),ControlDevice.class);if(!current.revision().equals(request.expectedRevision()))throw Failure.conflict();
                directory=new ControlDevice(id,current.name(),request.enabled(),current.revision()+1,current.ownerUserId());
            }
            var entries=new HashMap<>(before.entries());entries.put(recordId,codec.entry(Ids.Kind.DEVICE,codec.json(directory)));
            if(entry==null)DeviceGroups.addDefault(entries,codec,id);
            var after=new Directory(before.revision()+1,entries);after.validate(512,1048576);tx.save(before,after);
            tx.audit(actor.id(),request.enabled()?"DEVICE_ENABLE":"DEVICE_DISABLE","endpoint:"+id,directory.revision(),requestId,digest);
            var result=reply(directory,directory.revision());tx.remember(actor.id(),key,digest,result);return result;
        });
    }
    /** Reopens the review flow; a live client must submit a fresh CSR request and receive approval. */
    public Store.Reply requestApproval(DirectoryService.Actor actor,String user,String id,String body,String key,String requestId){
        actor.requireAdmin();Ids.valid(id);Ids.valid(key);Ids.valid(requestId);
        var request=codec.model(body,EndpointRevokeRequest.class);var digest=DirectoryService.digest("request-approval\n"+id+"\n"+body);
        return transaction((tx,now)->{human(actor,user,tx);approver(tx,actor,id,false);var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return replay;
            var row=tx.endpoint(id);if(row==null)throw Failure.conflict();var device=codec.model(row.document(),EndpointDeviceRecord.class);
            if(!request.expectedRevision().equals(device.revision())||device.state()!=EndpointState.REVOKED)throw Failure.conflict();
            var reset=resetForApproval(tx,row,device);
            tx.audit(actor.id(),"DEVICE_REQUEST_APPROVAL","endpoint:"+id,reset.revision(),requestId,digest);
            var result=reply(reset,reset.revision());tx.remember(actor.id(),key,digest,result);return result;
        });
    }
    public Store.Reply revoke(DirectoryService.Actor actor,String id,String body,String key,String requestId) {
        actor.requireAdmin();if(!actor.tenant().equals(tenant))throw forbidden();Ids.valid(id);Ids.valid(key);Ids.valid(requestId);
        var request=codec.model(body,EndpointRevokeRequest.class);var digest=DirectoryService.digest(id+"\n"+body);
        return transaction((tx,now)->{management(tx,actor,"revoke-device",id);var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return replay;
            var row=tx.endpoint(id);if(row==null)throw Failure.conflict();var device=codec.model(row.document(),EndpointDeviceRecord.class);
            if(!request.expectedRevision().equals(device.revision())||device.state()==EndpointState.REVOKED)throw Failure.conflict();
            var updated=new EndpointDeviceRecord(id,tenant.value(),device.userId(),row.fingerprint(),EndpointState.REVOKED,device.revision()+1,device.lastSeenUnixMs(),device.reportSequence(),device.report(),device.connectionExpiresAtUnixMs(),false,approvalRevision(device)+1,device.systemName(),device.ipAddress());
            tx.deleteEnrollments(id);
            tx.saveEndpoint(new Store.EndpointRecord(id,row.fingerprint(),codec.json(updated),row.csr(),row.reportDigest(),row.acknowledgment()));
            tx.audit(actor.id(),"DEVICE_REVOKE","endpoint:"+id,updated.revision(),requestId,digest);var result=reply(updated,updated.revision());tx.remember(actor.id(),key,digest,result);return result;
        });
    }
}
