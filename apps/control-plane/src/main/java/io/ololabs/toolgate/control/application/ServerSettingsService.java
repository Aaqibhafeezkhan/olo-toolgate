// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.ControlEntityKind;
import io.ololabs.toolgate.contracts.EnterpriseConfigurationCommand;
import io.ololabs.toolgate.contracts.EnterpriseConfigurationOperation;
import io.ololabs.toolgate.contracts.v2.ConfigurationSettings;
import io.ololabs.toolgate.contracts.v2.ControlServerSettings;
import io.ololabs.toolgate.contracts.v2.ControlServerSettingsPatch;
import io.ololabs.toolgate.contracts.v2.DeviceApproval;
import io.ololabs.toolgate.contracts.v2.GatewaySettings;

/** Tenant control-plane settings (formatVersion 2, plan.md §18), Super Admin only. Auto-approval is off until an administrator,
 *  an imported snapshot or the startup environment enables it. Client check-in is env-owned: always the running policy, never stored or imported.
 *  A change of configuration.requiredReviews needs the reviews of the threshold in force, with no Super Admin bypass. */
public final class ServerSettingsService {
    public static final long DAY_MS=86400000L,DEFAULT_AUTO_APPROVE_MS=30*DAY_MS,MAX_AUTO_APPROVE_MS=3650*DAY_MS;
    private static final java.util.regex.Pattern GATEWAY_NAME=java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._()-]{0,63}");
    /** Gateway name devices show until an administrator renames it; from TOOLGATE_GATEWAY_NAME. */
    public static final String DEFAULT_GATEWAY_NAME=gatewayName(System.getenv("TOOLGATE_GATEWAY_NAME"),"ToolGate");
    /** Configuration reviews until settings are saved; from TOOLGATE_CONFIG_REQUIRED_REVIEWS (0, 1 or 2, default 2). */
    public static final int DEFAULT_REQUIRED_REVIEWS=requiredReviews(System.getenv("TOOLGATE_CONFIG_REQUIRED_REVIEWS"),2);
    public static final String ENTITY="settings";
    static String gatewayName(String value,String fallback){return value!=null&&GATEWAY_NAME.matcher(value.trim()).matches()?value.trim():fallback;}
    static int requiredReviews(String value,int fallback){return value!=null&&value.trim().matches("[0-2]")?Integer.parseInt(value.trim()):fallback;}
    private static final String STARTUP_ACTOR=DirectoryService.digest("startup-configuration-import");
    private final Store store;
    private final Codec codec;
    private final ClientCheckInPolicy checkIn;
    private ConfigurationChanges reviews;
    public ServerSettingsService(Store store,Codec codec,ClientCheckInPolicy checkIn){this.store=store;this.codec=codec;this.checkIn=java.util.Objects.requireNonNull(checkIn);}
    /** Reviewed workflow used when a write changes the review threshold itself. */
    public ServerSettingsService reviews(ConfigurationChanges value){reviews=java.util.Objects.requireNonNull(value);return this;}
    public static ControlServerSettings defaults(ClientCheckInPolicy checkIn){
        return new ControlServerSettings(2L,1L,new GatewaySettings(DEFAULT_GATEWAY_NAME),new DeviceApproval(false,DEFAULT_AUTO_APPROVE_MS,null),checkIn.effective(),new ConfigurationSettings((long)DEFAULT_REQUIRED_REVIEWS));
    }
    /** The saved record with the running check-in values. Revision 1 is the unsaved default. */
    public ControlServerSettings current(Store.Session tx){var document=tx.serverSettings();if(document==null)return defaults(checkIn);
        // Nothing is in production, so a record from before formatVersion 2 is reset to the defaults, keeping its revision.
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)codec.value(document);
        if(node.path("formatVersion").asLong()!=2){var reset=defaults(checkIn);return new ControlServerSettings(2L,Math.max(1,node.path("revision").asLong()),reset.gateway(),reset.deviceApproval(),reset.clientCheckIn(),reset.configuration());}
        if(!node.has("configuration"))node.set("configuration",(com.fasterxml.jackson.databind.JsonNode)codec.value(codec.json(new ConfigurationSettings((long)DEFAULT_REQUIRED_REVIEWS))));
        var saved=codec.model(codec.json(node),ControlServerSettings.class);
        return new ControlServerSettings(2L,saved.revision(),saved.gateway(),saved.deviceApproval(),checkIn.effective(),saved.configuration());}
    /** Review threshold in force for new configuration changes. */
    public static int requiredReviews(Store.Session tx,Codec codec){return new ServerSettingsService(null,codec,ClientCheckInPolicy.QUICKSTART).current(tx).configuration().requiredReviews().intValue();}
    /** The enabled user that owns devices approved without a human decision, or null when auto-approval is unavailable. */
    public static String autoApprovalOwner(Store.Session tx,ControlServerSettings settings){
        var approval=settings.deviceApproval();
        if(!approval.autoApprove()||approval.ownerUserId()==null)return null;
        var owner=tx.load().entries().get(Ids.Kind.USER.id(approval.ownerUserId()));
        return owner!=null&&owner.enabled()?approval.ownerUserId():null;
    }
    public static long autoApprovalMs(ControlServerSettings settings){var duration=settings.deviceApproval().durationMs();return duration==null?DEFAULT_AUTO_APPROVE_MS:duration;}
    private Store.Reply reply(ControlServerSettings settings){return new Store.Reply(200,codec.json(settings),settings.revision());}
    /** Administration is Super Admin only, resolved from live directory membership rather than the actor's cached flag. */
    private void superAdmin(Store.Session tx,DirectoryService.Actor actor){actor.requireAdmin();if(!new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId()))throw ManagementAccess.denied();}
    public Store.Reply get(DirectoryService.Actor actor){actor.requireAdmin();return store.transaction(actor.tenant(),false,tx->{superAdmin(tx,actor);return reply(current(tx));});}
    /** Review threshold for every signed-in console user; reveals no other setting. */
    public Store.Reply reviewPolicy(DirectoryService.Actor actor){if(actor.userId()==null)throw ManagementAccess.denied();return store.transaction(actor.tenant(),false,tx->new Store.Reply(200,codec.json(new io.ololabs.toolgate.contracts.ControlReviewPolicy((long)requiredReviews(tx,codec))),0));}

    /** Full-record replace, used to restore an exported document. clientCheckIn in the body is ignored. */
    public Store.Reply update(DirectoryService.Actor actor,String body,long expected,String key,String requestId){
        actor.requireAdmin();Ids.valid(key);Ids.valid(requestId);var requested=codec.model(body,ControlServerSettings.class);
        return write(actor,"settings\n"+expected+"\n"+body,expected,key,requestId,current->requested);
    }
    /** Merge patch (RFC 7396) applied per group: each group in the body replaces that group, so a page can clear an
     *  optional field by leaving it out. Groups left out are unchanged; env-owned clientCheckIn cannot be patched. */
    public Store.Reply patch(DirectoryService.Actor actor,String body,long expected,String key,String requestId){
        actor.requireAdmin();Ids.valid(key);Ids.valid(requestId);var groups=codec.model(body,ControlServerSettingsPatch.class);
        return write(actor,"settings-patch\n"+expected+"\n"+body,expected,key,requestId,current->merge(current,groups));
    }
    static ControlServerSettings merge(ControlServerSettings current,ControlServerSettingsPatch groups){
        return new ControlServerSettings(2L,current.revision(),groups.gateway()!=null?groups.gateway():current.gateway(),groups.deviceApproval()!=null?groups.deviceApproval():current.deviceApproval(),current.clientCheckIn(),groups.configuration()!=null?groups.configuration():current.configuration());
    }
    private record Write(Store.Reply reply,ControlServerSettings next){}
    private Store.Reply write(DirectoryService.Actor actor,String request,long expected,String key,String requestId,java.util.function.UnaryOperator<ControlServerSettings> change){
        var digest=DirectoryService.digest(request);
        var write=store.transaction(actor.tenant(),true,tx->{superAdmin(tx,actor);var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return new Write(replay,null);
            var current=current(tx);if(current.revision()!=expected)throw Failure.conflict();var next=change.apply(current);
            // The threshold itself changes only under the current threshold's reviews, never through the Super Admin path.
            if(current.configuration().requiredReviews()>0&&!next.configuration().requiredReviews().equals(current.configuration().requiredReviews()))return new Write(null,next);
            var saved=save(tx,next,current,tx.load());tx.audit(actor.id(),"SERVER_SETTINGS_UPDATE",ENTITY,saved.revision(),requestId,digest);
            var result=reply(saved);tx.remember(actor.id(),key,digest,result);return new Write(result,null);});
        if(write.reply()!=null)return write.reply();
        if(reviews==null)throw Failure.unavailable();var next=write.next();
        return reviews.create(actor,new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.UPDATE,ControlEntityKind.SETTINGS,ENTITY,codec.json(new ControlServerSettingsPatch(next.gateway(),next.deviceApproval(),next.configuration())),expected),key,requestId);
    }
    /** Applies settings groups inside a reviewed change or an import; owners are checked against the directory being saved. */
    public static ControlServerSettings apply(Store.Session tx,Codec codec,ControlServerSettingsPatch groups,Long expected,Directory directory){
        var service=new ServerSettingsService(null,codec,ClientCheckInPolicy.QUICKSTART);var current=service.current(tx);
        if(expected!=null&&current.revision()!=expected.longValue())throw Failure.conflict();
        // An import that repeats the saved values leaves the settings revision unchanged.
        return same(current,groups)?current:service.save(tx,merge(current,groups),current,directory);
    }
    /** Whether applying the groups would change the saved settings. */
    public static boolean changes(Store.Session tx,Codec codec,ControlServerSettingsPatch groups){return !same(new ServerSettingsService(null,codec,ClientCheckInPolicy.QUICKSTART).current(tx),groups);}
    private static boolean same(ControlServerSettings current,ControlServerSettingsPatch groups){
        var next=merge(current,groups);var approval=next.deviceApproval();
        return next.gateway().equals(current.gateway())&&next.configuration().equals(current.configuration())&&new DeviceApproval(approval.autoApprove(),approval.durationMs()==null?DEFAULT_AUTO_APPROVE_MS:approval.durationMs(),approval.ownerUserId()).equals(current.deviceApproval());
    }
    /** Startup environment seeds settings once; with overwrite it replaces whatever an administrator saved before the restart.
     * Directory users may be imported after the control plane starts, so the owner is checked again at each enrollment. */
    public boolean importAtStartup(Ids.TenantId tenant,ControlServerSettings requested,boolean overwrite,String requestId){
        return store.transaction(tenant,true,tx->{if(tx.serverSettings()!=null&&!overwrite)return false;
            var saved=save(tx,requested,current(tx),null);tx.audit(STARTUP_ACTOR,"SERVER_SETTINGS_IMPORT",ENTITY,saved.revision(),requestId,DirectoryService.digest(codec.json(saved)));return true;});
    }
    private ControlServerSettings save(Store.Session tx,ControlServerSettings requested,ControlServerSettings current,Directory owners){
        var approval=requested.deviceApproval();long duration=approval.durationMs()==null?DEFAULT_AUTO_APPROVE_MS:approval.durationMs();
        if(duration<DAY_MS||duration>MAX_AUTO_APPROVE_MS)throw Failure.validation();
        var owner=approval.ownerUserId();
        if(owner!=null){Ids.valid(owner);var entry=owners!=null?owners.entries().get(Ids.Kind.USER.id(owner)):null;if(owners!=null&&(entry==null||!entry.enabled()))throw Failure.validation();}
        // Auto-approved identities need an accountable owner, exactly like a human approval.
        if(approval.autoApprove()&&owner==null)throw Failure.validation();
        var name=requested.gateway().gatewayName();
        if(!name.equals(gatewayName(name,null)))throw Failure.validation();
        var reviews=requested.configuration()==null?current.configuration():requested.configuration();
        var saved=new ControlServerSettings(2L,current.revision()+1,new GatewaySettings(name),new DeviceApproval(approval.autoApprove(),duration,owner),checkIn.effective(),reviews);
        tx.saveServerSettings(saved.revision(),codec.json(saved));return saved;
    }
}
