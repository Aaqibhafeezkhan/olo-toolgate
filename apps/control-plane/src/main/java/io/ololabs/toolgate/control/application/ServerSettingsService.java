// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.v2.ControlServerSettings;
import io.ololabs.toolgate.contracts.v2.ControlServerSettingsPatch;
import io.ololabs.toolgate.contracts.v2.DeviceApproval;
import io.ololabs.toolgate.contracts.v2.GatewaySettings;

/** Tenant control-plane settings (formatVersion 2, plan.md §18). Auto-approval is off until an administrator or a
 *  startup import enables it. Client check-in is env-owned: always the running policy, never stored or imported. */
public final class ServerSettingsService {
    public static final long DAY_MS=86400000L,DEFAULT_AUTO_APPROVE_MS=30*DAY_MS,MAX_AUTO_APPROVE_MS=3650*DAY_MS;
    private static final java.util.regex.Pattern GATEWAY_NAME=java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._()-]{0,63}");
    /** Gateway name devices show until an administrator renames it; from TOOLGATE_GATEWAY_NAME. */
    public static final String DEFAULT_GATEWAY_NAME=gatewayName(System.getenv("TOOLGATE_GATEWAY_NAME"),"ToolGate");
    static String gatewayName(String value,String fallback){return value!=null&&GATEWAY_NAME.matcher(value.trim()).matches()?value.trim():fallback;}
    private static final String STARTUP_ACTOR=DirectoryService.digest("startup-configuration-import");
    private final Store store;
    private final Codec codec;
    private final ClientCheckInPolicy checkIn;
    public ServerSettingsService(Store store,Codec codec,ClientCheckInPolicy checkIn){this.store=store;this.codec=codec;this.checkIn=java.util.Objects.requireNonNull(checkIn);}
    public static ControlServerSettings defaults(ClientCheckInPolicy checkIn){
        return new ControlServerSettings(2L,1L,new GatewaySettings(DEFAULT_GATEWAY_NAME),new DeviceApproval(false,DEFAULT_AUTO_APPROVE_MS,null),checkIn.effective());
    }
    /** The saved record with the running check-in values. Revision 1 is the unsaved default. */
    public ControlServerSettings current(Store.Session tx){var document=tx.serverSettings();if(document==null)return defaults(checkIn);
        // Nothing is in production, so a record from before formatVersion 2 is reset to the defaults, keeping its revision.
        var node=(com.fasterxml.jackson.databind.JsonNode)codec.value(document);
        if(node.path("formatVersion").asLong()!=2){var reset=defaults(checkIn);return new ControlServerSettings(2L,Math.max(1,node.path("revision").asLong()),reset.gateway(),reset.deviceApproval(),reset.clientCheckIn());}
        var saved=codec.model(document,ControlServerSettings.class);
        return new ControlServerSettings(2L,saved.revision(),saved.gateway(),saved.deviceApproval(),checkIn.effective());}
    /** The enabled user that owns devices approved without a human decision, or null when auto-approval is unavailable. */
    public static String autoApprovalOwner(Store.Session tx,ControlServerSettings settings){
        var approval=settings.deviceApproval();
        if(!approval.autoApprove()||approval.ownerUserId()==null)return null;
        var owner=tx.load().entries().get(Ids.Kind.USER.id(approval.ownerUserId()));
        return owner!=null&&owner.enabled()?approval.ownerUserId():null;
    }
    public static long autoApprovalMs(ControlServerSettings settings){var duration=settings.deviceApproval().durationMs();return duration==null?DEFAULT_AUTO_APPROVE_MS:duration;}
    private Store.Reply reply(ControlServerSettings settings){return new Store.Reply(200,codec.json(settings),settings.revision());}
    private static void admin(DirectoryService.Actor actor){actor.requireAdmin();}
    public Store.Reply get(DirectoryService.Actor actor){admin(actor);return store.transaction(actor.tenant(),false,tx->reply(current(tx)));}

    /** Full-record replace, used to restore an exported document. clientCheckIn in the body is ignored. */
    public Store.Reply update(DirectoryService.Actor actor,String body,long expected,String key,String requestId){
        admin(actor);Ids.valid(key);Ids.valid(requestId);var requested=codec.model(body,ControlServerSettings.class);
        return write(actor,"settings\n"+expected+"\n"+body,expected,key,requestId,current->requested);
    }
    /** Merge patch (RFC 7396) applied per group: each group in the body replaces that group, so a page can clear an
     *  optional field by leaving it out. Groups left out are unchanged; env-owned clientCheckIn cannot be patched. */
    public Store.Reply patch(DirectoryService.Actor actor,String body,long expected,String key,String requestId){
        admin(actor);Ids.valid(key);Ids.valid(requestId);var groups=codec.model(body,ControlServerSettingsPatch.class);
        return write(actor,"settings-patch\n"+expected+"\n"+body,expected,key,requestId,current->new ControlServerSettings(2L,current.revision(),
            groups.gateway()!=null?groups.gateway():current.gateway(),groups.deviceApproval()!=null?groups.deviceApproval():current.deviceApproval(),current.clientCheckIn()));
    }
    private Store.Reply write(DirectoryService.Actor actor,String request,long expected,String key,String requestId,java.util.function.UnaryOperator<ControlServerSettings> change){
        var digest=DirectoryService.digest(request);
        return store.transaction(actor.tenant(),true,tx->{var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return replay;
            var current=current(tx);if(current.revision()!=expected)throw Failure.conflict();
            var saved=save(tx,change.apply(current),current,true);tx.audit(actor.id(),"SERVER_SETTINGS_UPDATE","settings",saved.revision(),requestId,digest);
            var result=reply(saved);tx.remember(actor.id(),key,digest,result);return result;});
    }
    /** Startup import seeds settings once; with overwrite it replaces whatever an administrator saved before the restart.
     * Directory users may be imported after the control plane starts, so the owner is checked again at each enrollment. */
    public boolean importAtStartup(Ids.TenantId tenant,ControlServerSettings requested,boolean overwrite,String requestId){
        return store.transaction(tenant,true,tx->{if(tx.serverSettings()!=null&&!overwrite)return false;
            var saved=save(tx,requested,current(tx),false);tx.audit(STARTUP_ACTOR,"SERVER_SETTINGS_IMPORT","settings",saved.revision(),requestId,DirectoryService.digest(codec.json(saved)));return true;});
    }
    private ControlServerSettings save(Store.Session tx,ControlServerSettings requested,ControlServerSettings current,boolean requireOwner){
        var approval=requested.deviceApproval();long duration=approval.durationMs()==null?DEFAULT_AUTO_APPROVE_MS:approval.durationMs();
        if(duration<DAY_MS||duration>MAX_AUTO_APPROVE_MS)throw Failure.validation();
        var owner=approval.ownerUserId();
        if(owner!=null){Ids.valid(owner);var entry=requireOwner?tx.load().entries().get(Ids.Kind.USER.id(owner)):null;if(requireOwner&&(entry==null||!entry.enabled()))throw Failure.validation();}
        // Auto-approved identities need an accountable owner, exactly like a human approval.
        if(approval.autoApprove()&&owner==null)throw Failure.validation();
        var name=requested.gateway().gatewayName();
        if(!name.equals(gatewayName(name,null)))throw Failure.validation();
        var saved=new ControlServerSettings(2L,current.revision()+1,new GatewaySettings(name),new DeviceApproval(approval.autoApprove(),duration,owner),checkIn.effective());
        tx.saveServerSettings(saved.revision(),codec.json(saved));return saved;
    }
}
