// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;

/** Caller-owned, expiring HTTPS subscriptions with encrypted signing custody and leased delivery. */
public final class ApplicationWebhooks {
    private final Store store;private final Codec codec;private final ApplicationEvents events;
    private final ApplicationWebhookTransport transport;private final TenantVaultKeys keys;private final Clock clock;
    public ApplicationWebhooks(Store store,Codec codec,ApplicationEvents events,ApplicationWebhookTransport transport,TenantVaultKeys keys,Clock clock) {
        this.store=store;this.codec=codec;this.events=events;this.transport=transport;this.keys=keys;this.clock=clock;
    }
    public Store.Reply create(DirectoryService.Actor actor,String body,String key) {
        Ids.valid(key);var request=codec.model(body,ApplicationWebhookCreate.class);
        if(actor.userId()==null||actor.sessionExpiresAt()==null||request.expiresAtUnixMs()<=clock.millis()
            ||request.expiresAtUnixMs()>actor.sessionExpiresAt())throw Failure.validation();
        transport.validate(request.callbackUrl());
        var kinds=Set.copyOf(request.eventKinds());
        String id="sub-"+DirectoryService.digest("application-webhook/v1\n"+actor.tenant().value()+"\n"+actor.id()+"\n"+key).substring(0,40);
        return store.transaction(actor.tenant(),true,tx->{
            var source=events.authorize(tx,actor,request.sourceType(),request.sourceId(),kinds,true);
            tx.applicationEvents().prune(Math.max(0,clock.millis()-86400000));
            var existing=tx.applicationEvents().get(id);
            String requestDigest=DirectoryService.digest(codec.json(request));
            String kindNames=String.join(",",kinds.stream().map(Enum::name).sorted().toList());
            if(existing!=null) {
                owner(actor,existing);
                if(!existing.endpoint().equals(request.callbackUrl())||!existing.sourceType().equals(request.sourceType().name())
                    ||!existing.sourceId().equals(request.sourceId())||!existing.sourceKey().equals(source.key())
                    ||!existing.eventKinds().equals(kindNames)||existing.expiresAt()!=request.expiresAtUnixMs()||!existing.requestDigest().equals(requestDigest)
                    ||!existing.identityDigest().equals(source.identityDigest()))throw Failure.conflict();
                // Recover the same registration after a lost response; never store a plaintext secret in replay rows.
                byte[] secret=open(tx,actor.tenant(),existing);
                try{return receipt(existing,secret);}finally{Arrays.fill(secret,(byte)0);}
            }
            byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);
            var custody=keys.current(tx);byte[] encryption=keys.encryption(actor.tenant(),custody);
            try {
                String sealed=VaultEncryption.encrypt(encryption,secret,context(actor.tenant(),id,custody.version()));
                var sub=new ApplicationEventStore.Subscription(id,actor.userId(),actor.id(),source.identityDigest(),request.sourceType().name(),
                    request.sourceId(),source.key(),kindNames,request.callbackUrl(),request.expiresAtUnixMs(),custody.version(),sealed,
                    "PENDING",request.after(),0,0,null,0,requestDigest);
                tx.applicationEvents().insert(sub);
                tx.audit(actor.id(),"APPLICATION_WEBHOOK_CREATE","application-webhook:"+id,1,"webhook-"+id,DirectoryService.digest(codec.json(request)));
                return receipt(sub,secret);
            }finally{Arrays.fill(secret,(byte)0);Arrays.fill(encryption,(byte)0);}
        });
    }
    private Store.Reply receipt(ApplicationEventStore.Subscription sub,byte[] secret) {
        var response=new LinkedHashMap<String,Object>(projection(sub));response.put("signingSecret",Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        return new Store.Reply(201,codec.json(response),0);
    }
    public Store.Reply get(DirectoryService.Actor actor,String id) {
        Ids.valid(id);return store.transaction(actor.tenant(),false,tx->{var sub=required(tx,id);manage(tx,actor,sub);return new Store.Reply(200,codec.json(projection(sub)),0);});
    }
    public Store.Reply list(DirectoryService.Actor actor,String after) {
        if(after!=null)Ids.valid(after);
        return store.transaction(actor.tenant(),false,tx->{new ManagementAccess(codec).access(tx.load(),actor.userId());
            boolean elevated=new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId());
            var subscriptions=elevated?tx.applicationEvents().subscriptions(after==null?"":after,50):tx.applicationEvents().owned(actor.userId(),after==null?"":after,50);
            var rows=subscriptions.stream().filter(s->elevated||s.principalId().equals(actor.id())).map(this::projection).toList();
            return new Store.Reply(200,codec.json(Map.of("items",rows)),0);});
    }
    public Store.Reply revoke(DirectoryService.Actor actor,String id) {
        Ids.valid(id);return store.transaction(actor.tenant(),true,tx->{var sub=required(tx,id);boolean elevated=manage(tx,actor,sub);
            tx.applicationEvents().state(id,"REVOKED");tx.audit(actor.id(),"APPLICATION_WEBHOOK_REVOKE"+(elevated?"_SUPER_ADMIN":""),"application-webhook:"+id,1,"revoke-"+id,DirectoryService.digest(id));return new Store.Reply(204,"",0);});
    }
    public Store.Reply verify(DirectoryService.Actor actor,String id) {
        Ids.valid(id);
        var sub=store.transaction(actor.tenant(),false,tx->{var stored=required(tx,id);authorizeRegistration(tx,actor,stored);
            if(!stored.state().equals("PENDING"))throw Failure.conflict();return stored;});
        byte[] secret=store.transaction(actor.tenant(),false,tx->open(tx,actor.tenant(),sub));
        String challenge=UUID.randomUUID().toString();boolean verified;
        try{verified=transport.verify(sub.endpoint(),sub.id(),challenge,secret);}finally{Arrays.fill(secret,(byte)0);}
        if(!verified)throw new Failure(ErrorCode.CONFLICT,409,"Webhook ownership verification failed");
        return store.transaction(actor.tenant(),true,tx->{var current=required(tx,id);boolean elevated=authorizeRegistration(tx,actor,current);
            if(!current.state().equals("PENDING"))throw Failure.conflict();tx.applicationEvents().state(id,"ACTIVE");
            tx.audit(actor.id(),"APPLICATION_WEBHOOK_VERIFIED"+(elevated?"_SUPER_ADMIN":""),"application-webhook:"+id,1,"verify-"+id,DirectoryService.digest(id));return new Store.Reply(204,"",0);});
    }
    private Map<String,Object> projection(ApplicationEventStore.Subscription sub) {
        return Map.of("id",sub.id(),"sourceType",sub.sourceType(),"sourceId",sub.sourceId(),"eventKinds",sub.eventKinds().split(","),
            "callbackUrl",sub.endpoint(),"expiresAtUnixMs",sub.expiresAt(),"state",sub.expiresAt()<=clock.millis()&&Set.of("ACTIVE","PENDING").contains(sub.state())?"EXPIRED":sub.state(),
            "nextCursor",sub.cursor(),"attempts",sub.attempts());
    }
    private ApplicationEventStore.Subscription required(Store.Session tx,String id) {
        var sub=tx.applicationEvents().get(id);if(sub==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Subscription unavailable");return sub;
    }
    private static void owner(DirectoryService.Actor actor,ApplicationEventStore.Subscription sub) {
        if(actor.userId()==null||!actor.userId().equals(sub.userId())||!actor.id().equals(sub.principalId()))throw ManagementAccess.denied();
    }
    private boolean manage(Store.Session tx,DirectoryService.Actor actor,ApplicationEventStore.Subscription sub) {
        boolean elevated=new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId());
        if(!elevated)owner(actor,sub);
        return elevated&&(!actor.userId().equals(sub.userId())||!actor.id().equals(sub.principalId()));
    }
    private boolean authorizeRegistration(Store.Session tx,DirectoryService.Actor actor,ApplicationEventStore.Subscription sub) {
        boolean elevated=manage(tx,actor,sub);
        // An administrator manages the registration, not the subscriber's identity or signing key.
        var subscriber=elevated?new DirectoryService.Actor(actor.tenant(),sub.principalId(),false,false,sub.userId()):actor;
        authorize(tx,subscriber,sub);
        return elevated;
    }
    private static Set<ApplicationEventKind> kinds(ApplicationEventStore.Subscription sub) {
        return Arrays.stream(sub.eventKinds().split(",")).map(ApplicationEventKind::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private void authorize(Store.Session tx,DirectoryService.Actor actor,ApplicationEventStore.Subscription sub) {
        if(sub.expiresAt()<=clock.millis())throw ManagementAccess.denied();
        var source=events.authorize(tx,actor,ApplicationEventSource.valueOf(sub.sourceType()),sub.sourceId(),kinds(sub),true);
        if(!source.key().equals(sub.sourceKey())||!source.identityDigest().equals(sub.identityDigest()))throw ManagementAccess.denied();
    }
    private byte[] context(Ids.TenantId tenant,String id,long version) {return codec.json(List.of("toolgate/application-webhook/v1",tenant.value(),id,version)).getBytes(StandardCharsets.UTF_8);}
    private byte[] open(Store.Session tx,Ids.TenantId tenant,ApplicationEventStore.Subscription sub) {
        byte[] encryption=keys.encryption(tenant,tx.enterprise().vaultKey(sub.keyVersion()));
        try{return VaultEncryption.decrypt(encryption,sub.secret(),context(tenant,sub.id(),sub.keyVersion()));}
        finally{Arrays.fill(encryption,(byte)0);}
    }
    private record Delivery(ApplicationEventStore.Subscription subscription,String lease,ApplicationEvent event,long cursor,byte[] secret){}
    /** One bounded tenant tick. Source events and registration survive restarts; ACKs require the current lease. */
    public void tick(Ids.TenantId tenant) {
        store.transaction(tenant,true,tx->{tx.applicationEvents().prune(Math.max(0,clock.millis()-86400000));return null;});
        var due=store.transaction(tenant,false,tx->tx.applicationEvents().due(clock.millis(),20));
        for(var candidate:due) {
            Delivery delivery;
            try {
                delivery=store.transaction(tenant,true,tx->{
                    var sub=required(tx,candidate.id());var actor=new DirectoryService.Actor(tenant,sub.principalId(),false,false,sub.userId());
                    authorize(tx,actor,sub);
                    String lease="delivery-"+UUID.randomUUID();if(!tx.applicationEvents().lease(sub.id(),lease,clock.millis()))return null;
                    var page=tx.applicationEvents().page(ApplicationEventSource.valueOf(sub.sourceType()),sub.sourceKey(),sub.cursor(),200);
                    var event=page.stream().filter(e->kinds(sub).contains(e.kind())).findFirst().orElse(null);
                    long cursor=event!=null?event.sequence():page.isEmpty()?sub.cursor():page.getLast().sequence();
                    if(event==null){tx.applicationEvents().acknowledge(sub.id(),lease,cursor,clock.millis()+1000);return null;}
                    return new Delivery(sub,lease,event,cursor,open(tx,tenant,sub));
                });
            }catch(Failure denied){
                if(Set.of(400,403,404,409).contains(denied.status()))store.transaction(tenant,true,tx->{tx.applicationEvents().state(candidate.id(),"REVOKED");return null;});
                continue;
            }
            if(delivery==null)continue;
            boolean acknowledged=false;
            try {
                // Recheck immediately before outbound I/O, including a revoke after the lease was acquired.
                store.transaction(tenant,false,tx->{var current=required(tx,delivery.subscription().id());
                    if(!"ACTIVE".equals(current.state())||!delivery.lease().equals(current.leaseId()))throw ManagementAccess.denied();
                    authorize(tx,new DirectoryService.Actor(tenant,current.principalId(),false,false,current.userId()),current);return null;});
                acknowledged=transport.deliver(delivery.subscription().endpoint(),delivery.subscription().id(),delivery.event(),delivery.secret());
            }catch(Failure unavailable){acknowledged=false;}
            finally{Arrays.fill(delivery.secret(),(byte)0);}
            final boolean success=acknowledged;
            store.transaction(tenant,true,tx->{
                if(success)tx.applicationEvents().acknowledge(delivery.subscription().id(),delivery.lease(),delivery.cursor(),clock.millis());
                else {int attempts=delivery.subscription().attempts()+1;tx.applicationEvents().retry(delivery.subscription().id(),delivery.lease(),attempts,
                    clock.millis()+(1L<<Math.min(attempts,8))*1000,attempts>=8?"FAILED":"ACTIVE");}
                return null;
            });
        }
    }
}
