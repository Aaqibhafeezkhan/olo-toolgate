// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.util.*;
/** Group-scoped secret custody. Current authorization, ciphertext write and audit share one transaction. */
public final class VaultService {
    private final Store store;private final Codec codec;private final TenantVaultKeys keys;
    public VaultService(Store store,Codec codec,TenantVaultKeys keys){this.store=store;this.codec=codec;this.keys=Objects.requireNonNull(keys);}
    private void access(Store.Session tx,DirectoryService.Actor actor,String action,String name,String tg,String dg){
        var graph=tx.load();for(var id:List.of(Ids.Kind.TOOL_GROUP.id(tg),Ids.Kind.DEVICE_GROUP.id(dg))){var group=graph.entries().get(id);if(group==null||!group.enabled())throw ManagementAccess.denied();}
        var management=new ManagementAccess(codec);long now=System.currentTimeMillis();management.require(graph,actor.userId(),action,Ids.Kind.TOOL_GROUP,tg,now);management.require(graph,actor.userId(),action,Ids.Kind.DEVICE_GROUP,dg,now);
        if(action.equals("secret.read")||management.superAdministrator(graph,actor.userId()))return;
        var conditions=new EnterpriseConditions(0L,0L,List.of(),List.of(),List.of(),List.of(),true,false,null,null);
        var scope=new EnterpriseScope(new GroupSelection(List.of(tg),false),new GroupSelection(List.of(dg),false),List.of(action),false,List.of(new EnterpriseResourceRule(ResourceKind.CUSTOM,"secret://"+name,EnterpriseResourceMatch.EXACT)),conditions);
        if(management.access(graph,actor.userId()).grants().stream().noneMatch(rule->rule.actions().contains(action)&&ManagementAccess.current(rule.conditions(),now)&&rule.grantableScopes().stream().anyMatch(ceiling->ManagementAccess.contains(ceiling,scope))))throw ManagementAccess.denied();
    }
    public Store.Reply list(DirectoryService.Actor actor){return store.transaction(actor.tenant(),false,tx->{var visible=new ArrayList<EnterpriseVaultReference>();for(var s:tx.enterprise().secrets())try{access(tx,actor,"secret.read",s.name(),s.toolGroupId(),s.deviceGroupId());visible.add(new EnterpriseVaultReference(s.name(),s.toolGroupId(),s.deviceGroupId()));}catch(Failure denied){if(denied.status()!=403)throw denied;}return new Store.Reply(200,codec.json(new EnterpriseVaultPage(visible)),tx.load().revision());});}
    public Store.Reply deliver(Ids.TenantId tenant,java.util.function.Function<Store.Session,String> certificate,String document,String fencingToken,EnterpriseOperations operations){var request=codec.model(document,EnterpriseSecretDeliveryRequest.class);return store.transaction(tenant,true,tx->{var invocation=operations.secretAuthority(tenant,tx,certificate.apply(tx),request.invocationId(),request.name(),fencingToken);var secret=tx.enterprise().secret(request.name());if(secret==null)throw ManagementAccess.denied();var binding=codec.model(tx.load().entries().get(Ids.Kind.BINDING.id(invocation.evaluation().context().bindingId())).document(),ControlExecutionBinding.class);if(!secret.toolGroupId().equals(binding.toolGroupId())||!secret.deviceGroupId().equals(binding.deviceGroupId()))throw ManagementAccess.denied();
        byte[] clear=decrypt(tenant,tx,secret);try{String value=new String(clear,java.nio.charset.StandardCharsets.UTF_8);tx.audit("device-secret","SECRET_DELIVER",DirectoryService.digest(secret.name()),invocation.revision(),invocation.id(),invocation.requestDigest());return new Store.Reply(200,codec.json(new EnterpriseSecretDelivery(value)),invocation.revision());}finally{Arrays.fill(clear,(byte)0);}});}
    /** A scan's upstream credential (docs/planning/mcp-proxy/plan.md §6): only for the admitted lease holder of a current
     *  scan, before its deadline, and only a secret of exactly the registration's binding groups. */
    public Store.Reply deliverScan(Ids.TenantId tenant,java.util.function.Function<Store.Session,String> device,String scanId,String fencingToken,McpServers scans){
        return store.transaction(tenant,true,tx->{var authority=scans.secretAuthority(tx,device.apply(tx),scanId,fencingToken);var secret=tx.enterprise().secret(authority.secretName());
            if(secret==null||!secret.toolGroupId().equals(authority.toolGroupId())||!secret.deviceGroupId().equals(authority.deviceGroupId()))throw ManagementAccess.denied();
            byte[] clear=decrypt(tenant,tx,secret);try{String value=new String(clear,java.nio.charset.StandardCharsets.UTF_8);tx.audit("device-secret","SECRET_DELIVER_SCAN",DirectoryService.digest(secret.name()),authority.revision(),scanId,DirectoryService.digest(scanId));return new Store.Reply(200,codec.json(new EnterpriseSecretDelivery(value)),authority.revision());}finally{Arrays.fill(clear,(byte)0);}});
    }
    public Store.Reply write(DirectoryService.Actor actor,String document,String requestId){var value=codec.model(document,EnterpriseVaultWrite.class);Ids.valid(requestId);if(value.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw Failure.validation();
        return store.transaction(actor.tenant(),true,tx->{access(tx,actor,"secret.write",value.name(),value.toolGroupId(),value.deviceGroupId());var previous=tx.enterprise().secret(value.name());if(previous!=null)access(tx,actor,"secret.write",previous.name(),previous.toolGroupId(),previous.deviceGroupId());String digest=digest(actor.tenant(),tx,value);var replay=tx.replay(actor.id(),requestId,digest);if(replay!=null)return replay;if(previous==null&&tx.enterprise().secrets().size()>=256)throw Failure.conflict();
            byte[] clear=value.value().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try{tx.enterprise().saveSecret(encrypt(actor.tenant(),keys.current(tx),value.name(),value.toolGroupId(),value.deviceGroupId(),previous==null?1:next(previous.version()),clear));}finally{Arrays.fill(clear,(byte)0);}
            tx.audit(actor.id(),"SECRET_CUSTODY_PUT","secret:"+value.name(),tx.load().revision(),requestId,DirectoryService.digest(codec.json(new EnterpriseVaultReference(value.name(),value.toolGroupId(),value.deviceGroupId()))));var reply=new Store.Reply(201,codec.json(new EnterpriseVaultStored(true)),tx.load().revision());tx.remember(actor.id(),requestId,digest,reply);return reply;});
    }
    private long next(long version){if(version<1||version>=9007199254740991L)throw Failure.conflict();return version+1;}
    private byte[] context(Ids.TenantId tenant,String name,String tg,String dg,long version,long keyVersion){return codec.json(List.of("ToolGate tenant secret",1L,tenant.value(),name,version,tg,dg,keyVersion)).getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    private String digest(Ids.TenantId tenant,Store.Session tx,EnterpriseVaultWrite value) {
        byte[] retry=keys.retry(tenant,tx);
        try{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(retry,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(codec.json(List.of("ToolGate vault retry",1L,tenant.value(),value)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException invalid){throw Failure.unavailable();}finally{Arrays.fill(retry,(byte)0);}
    }
    private EnterpriseStore.Secret encrypt(Ids.TenantId tenant,EnterpriseStore.VaultKey key,String name,String tg,String dg,long version,byte[] clear) {
        byte[] encryption=keys.encryption(tenant,key);
        try{return new EnterpriseStore.Secret(name,tg,dg,version,key.version(),VaultEncryption.encrypt(encryption,clear,context(tenant,name,tg,dg,version,key.version())));}
        finally{Arrays.fill(encryption,(byte)0);}
    }
    private byte[] decrypt(Ids.TenantId tenant,Store.Session tx,EnterpriseStore.Secret secret) {
        byte[] encryption=keys.encryption(tenant,tx.enterprise().vaultKey(secret.keyVersion()));
        try{return VaultEncryption.decrypt(encryption,secret.cipher(),context(tenant,secret.name(),secret.toolGroupId(),secret.deviceGroupId(),secret.version(),secret.keyVersion()));}
        finally{Arrays.fill(encryption,(byte)0);}
    }
    /** Called only by the serialized platform rotation transaction; any failure rolls back all new envelopes. */
    public void rotate(Ids.TenantId tenant,Store.Session tx) {
        var key=keys.rotate(tenant,tx);
        for(var secret:tx.enterprise().secrets()) {
            byte[] clear=decrypt(tenant,tx,secret);
            try{tx.enterprise().saveSecret(encrypt(tenant,key,secret.name(),secret.toolGroupId(),secret.deviceGroupId(),next(secret.version()),clear));}
            finally{Arrays.fill(clear,(byte)0);}
        }
    }
}
