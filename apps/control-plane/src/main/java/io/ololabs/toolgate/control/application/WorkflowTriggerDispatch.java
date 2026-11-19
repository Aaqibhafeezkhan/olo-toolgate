// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Ids;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Backend-owned durable dispatch. Reuses stream owner/source checks; no workload receives an Admin token. */
public final class WorkflowTriggerDispatch {
    private final Store store;
    private final Codec codec;
    private final TriggerStream stream;
    private final TriggerSubscriptions subscriptions;
    private final WorkflowProfiles profiles;
    private final WorkflowAdmissions admissions;
    public WorkflowTriggerDispatch(Store store, Codec codec, TriggerStream stream, TriggerSubscriptions subscriptions,
            WorkflowProfiles profiles, WorkflowAdmissions admissions) {
        this.store=store; this.codec=codec; this.stream=stream; this.subscriptions=subscriptions;
        this.profiles=profiles; this.admissions=admissions;
    }
    private record Owned(String name, String uid, DirectoryService.Actor actor) {}
    public int tick(Ids.TenantId tenant) {
        var desired=store.transaction(tenant, false, tx -> {
            var selected=new LinkedHashMap<String,Owned>();
            var heads=tx.workflowProfiles().publications("",257);
            if(heads.size()>256) throw Failure.unavailable();
            var catalog=new LinkedHashMap<String,TriggerSubscriptions.Sub>();
            String after="";
            while(true) {
                var rows=tx.triggers().page(TriggerStore.Kind.SUBSCRIPTION,"",after,101);
                for(var row:rows) {
                    after=row.id();
                    var sub=subscriptions.stored(tx,row.id());
                    if(sub!=null && !sub.removed())catalog.put(sub.uid(),sub);
                    if(catalog.size()>1000)throw Failure.unavailable();
                }
                if(rows.size()<101)break;
            }
            for(var head:heads) {
                var profile=profiles.published(tx,tenant,head.profileId());
                for(var binding:profile.workflows().dispatchBindings()) {
                    var sub=catalog.get(binding.subscriptionUid());
                    if(sub!=null)selected.putIfAbsent(sub.uid(),new Owned(sub.name(),sub.uid(),subscriptions.owner(sub)));
                }
            }
            if(selected.size()>64) throw Failure.unavailable();
            return selected.values().stream().toList();
        });
        int count=0;
        for(var sub:desired) {
            // Ephemeral session; authoritative cursor and admission ledger always live in the tenant store.
            var session=stream.new Session(sub.actor());
            session.receive(codec.json(Map.of("type","subscribe","subscription",sub.name())));
            for(var text:session.pump()) {
                var frame=(com.fasterxml.jackson.databind.JsonNode)codec.value(text);
                if(!"event".equals(frame.path("type").asText())) continue;
                String cursor=frame.path("cursor").asText();
                try {
                    admissions.admit(sub.actor(),codec.json(Map.of("subscriptionUid",sub.uid(),"cursor",cursor)),
                        "dispatch-"+UUID.randomUUID());
                    session.receive(codec.json(Map.of("type","ack","subscription",sub.name(),"cursor",cursor)));
                    count++;
                } catch(Failure unavailable) { break; } // No ACK on refused/unknown admission; replay uses its durable ledger.
            }
        }
        return count;
    }
}
