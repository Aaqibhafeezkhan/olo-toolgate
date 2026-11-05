// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;

/** Current-only read-only inventory. Readiness never substitutes for operation-specific retry proof. */
public final class RecoveryPreflight {
    private final Store store;private final Codec codec;private final ServerPackages packages;
    private final BiFunction<String,Integer,List<Ids.TenantId>> tenants;
    public RecoveryPreflight(Store store,Codec codec,ServerPackages packages,BiFunction<String,Integer,List<Ids.TenantId>> tenants){
        this.store=Objects.requireNonNull(store);this.codec=Objects.requireNonNull(codec);this.packages=Objects.requireNonNull(packages);
        this.tenants=Objects.requireNonNull(tenants);
    }
    private record Cursor(String tenant,String tool,long revision){}
    private record Batch(List<ToolRecoveryReadiness> items,boolean more,long revision){}
    private Cursor cursor(String value,String filter){
        if(value==null)return new Cursor("","",0);
        if(!value.matches("[A-Za-z0-9_-]{1,2048}"))throw Failure.validation();
        try{
            var node=(JsonNode)codec.value(new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8));
            if(!node.isArray()||node.size()!=4||!node.get(0).isTextual()||!node.get(1).isTextual()||!node.get(2).isTextual()
                ||!node.get(3).isIntegralNumber()||!node.get(3).canConvertToLong()||!node.get(0).asText().equals(filter))throw Failure.validation();
            String tenant=node.get(1).asText(),tool=node.get(2).asText();new Ids.TenantId(tenant);
            if(!tool.isEmpty())Ids.valid(tool);long revision=node.get(3).asLong();
            if(revision<0||revision>9007199254740991L||tool.isEmpty()&&revision!=0)throw Failure.validation();
            if(!filter.isEmpty()&&(!filter.equals(tenant)||tool.isEmpty()))throw Failure.validation();
            return new Cursor(tenant,tool,revision);
        }catch(IllegalArgumentException invalid){throw Failure.validation();}
    }
    private String encode(String filter,String tenant,String tool,long revision){
        return Base64.getUrlEncoder().withoutPadding().encodeToString(codec.json(List.of(filter,tenant,tool,revision)).getBytes(StandardCharsets.UTF_8));
    }
    public RecoveryPreflightPage page(DirectoryService.Actor actor,String after,int limit,long now){
        if(actor.userId()==null)throw ManagementAccess.denied();
        return scan(actor.tenant().value(),after,limit,now,actor);
    }
    /** Installation-operator API only: the caller is authenticated by the separate operator mTLS listener. */
    public RecoveryPreflightPage operatorPage(String tenant,String after,int limit,long now){
        try{return scan(tenant==null?"":new Ids.TenantId(tenant).value(),after,limit,now,null);}
        catch(IllegalArgumentException invalid){throw Failure.validation();}
    }
    private RecoveryPreflightPage scan(String filter,String after,int limit,long now,DirectoryService.Actor actor){
        if(limit<1||limit>100||now<0)throw Failure.validation();
        var cursor=cursor(after,filter);var ids=new ArrayList<Ids.TenantId>();boolean additional=false;
        if(!filter.isEmpty())ids.add(new Ids.TenantId(filter));
        else{
            if(!cursor.tool().isEmpty())ids.add(new Ids.TenantId(cursor.tenant()));
            int room=32-ids.size();var next=tenants.apply(cursor.tenant(),room+1);
            additional=next.size()>room;ids.addAll(next.subList(0,Math.min(room,next.size())));
        }
        var result=new ArrayList<ToolRecoveryReadiness>();
        for(int n=0;n<ids.size();n++){
            var tenant=ids.get(n);boolean resume=tenant.value().equals(cursor.tenant())&&!cursor.tool().isEmpty();
            var batch=store.transaction(tenant,false,tx->batch(tx,tenant,actor,resume?cursor.tool():"",
                resume?cursor.revision():null,limit-result.size(),now));
            result.addAll(batch.items());
            if(batch.more())return new RecoveryPreflightPage(result,encode(filter,tenant.value(),result.getLast().toolId(),batch.revision()),now,"CURRENT_ONLY");
            if(result.size()==limit)return new RecoveryPreflightPage(result,
                n<ids.size()-1||additional?encode(filter,tenant.value(),"",0):null,now,"CURRENT_ONLY");
        }
        return new RecoveryPreflightPage(result,additional?encode(filter,ids.getLast().value(),"",0):null,now,"CURRENT_ONLY");
    }
    private Batch batch(Store.Session tx,Ids.TenantId tenant,DirectoryService.Actor actor,String after,Long revision,int limit,long now){
        var graph=tx.load();if(revision!=null&&revision!=graph.revision())throw Failure.conflict();
        var entries=graph.entries().values().stream().filter(e->e.id().kind()==Ids.Kind.TOOL&&e.id().value().compareTo(after)>0)
            .sorted(Comparator.comparing(e->e.id().value())).toList();
        var result=new ArrayList<ToolRecoveryReadiness>();boolean more=false;
        for(var entry:entries){
            var tool=codec.model(entry.document(),ControlTool.class);
            if(tool.definition().reconciliation()!=ReconciliationMethod.DOWNSTREAM_IDEMPOTENCY)continue;
            if(actor!=null)try{new ManagementAccess(codec).requireEntry(graph,actor.userId(),"read",entry,now);}
                catch(Failure denied){if(denied.status()!=403)throw denied;continue;}
            if(result.size()==limit){more=true;break;}
            result.add(tool(tx,tenant,tool,now));
        }
        return new Batch(List.copyOf(result),more,graph.revision());
    }
    private ToolRecoveryReadiness unavailable(Ids.TenantId tenant,ControlTool tool,RecoveryReadinessReason reason){
        return new ToolRecoveryReadiness(tenant.value(),tool.id(),tool.name(),false,ReconciliationMethod.MANUAL,reason,List.of());
    }
    private ToolRecoveryReadiness tool(Store.Session tx,Ids.TenantId tenant,ControlTool tool,long now){
        if(!tool.enabled())return unavailable(tenant,tool,RecoveryReadinessReason.DISABLED);
        int slash=tool.id().indexOf('/');if(slash<1)return unavailable(tenant,tool,RecoveryReadinessReason.MISSING_PROFILE);
        String raw=tx.packages().deployment(tool.id().substring(0,slash));
        if(raw==null)return unavailable(tenant,tool,RecoveryReadinessReason.MISSING_PROFILE);
        var deployment=(JsonNode)codec.value(raw);var version=tx.packages().version(deployment.path("packageDigest").asText());
        String profileDigest=deployment.path("serviceProfileDigest").asText(),document=tx.packages().document(profileDigest);
        if(version==null||document==null)return unavailable(tenant,tool,RecoveryReadinessReason.MISSING_PROFILE);
        if(!version.digest().equals("sha256:"+tool.packageDigest())||!Canonical.digest((JsonNode)codec.value(document)).equals(profileDigest))
            return unavailable(tenant,tool,RecoveryReadinessReason.PROFILE_CHANGED);
        var profile=codec.model(document,ServiceProfile.class);var pkg=(JsonNode)codec.value(version.document());
        var specs=new ArrayList<JsonNode>();
        for(var spec:pkg.path("manifest").path("tools"))if(spec.path("descriptor").path("toolId").asText().equals(tool.id()))specs.add(spec);
        if(specs.size()!=1)return unavailable(tenant,tool,RecoveryReadinessReason.MISSING_PROFILE);
        var spec=specs.getFirst();var descriptor=spec.path("descriptor");var routes=new ArrayList<RouteRecoveryReadiness>();
        if(!descriptor.path("routes").isArray()||descriptor.path("routes").size()>256)
            return unavailable(tenant,tool,RecoveryReadinessReason.NO_WRITE_ROUTES);
        for(var declared:descriptor.path("routes")){
            String id=declared.path("id").asText();
            var reviews=profile.effectReviews()==null?List.<RouteEffectReview>of():profile.effectReviews().stream()
                .filter(r->r.policy().routeId().equals(id)).toList();
            if(reviews.size()==1&&reviews.getFirst().policy().classification()==EffectClassification.READ)continue;
            if(reviews.size()!=1){routes.add(new RouteRecoveryReadiness(id,RecoveryReadinessReason.UNREVIEWED_ROUTE,false,null));continue;}
            var review=reviews.getFirst();var guarantee=review.policy().targetDedupeGuarantee();RecoveryReadinessReason reason;
            if(guarantee==null)reason=RecoveryReadinessReason.MISSING_GUARANTEE;
            else if(guarantee.reviewExpiresAtUnixMs()<=now)reason=RecoveryReadinessReason.REVIEW_EXPIRED;
            else if(TargetDedupeGuarantees.approved(tx,codec,tool.id(),profileDigest,review,now)==null)
                reason=RecoveryReadinessReason.INDEPENDENT_REVIEW_REQUIRED;
            else if(!credentialReady(tx,descriptor,profile,id,guarantee))reason=RecoveryReadinessReason.UNSUPPORTED_CREDENTIAL;
            else reason=RecoveryReadinessReason.ELIGIBLE;
            boolean lookup=false;
            if(reason==RecoveryReadinessReason.ELIGIBLE&&guarantee.statusRouteId()!=null)try{
                TargetLookupRoutes.require(profile,id,guarantee.statusRouteId());
                var route=profile.routes().stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(Failure::conflict);
                lookup=route.auth()==null||auth(descriptor,route.auth()).path("type").asText().equals("API_KEY");
            }catch(Failure unsupported){if(unsupported.status()!=400&&unsupported.status()!=409)throw unsupported;}
            routes.add(new RouteRecoveryReadiness(id,reason,lookup,guarantee==null?null:guarantee.reviewExpiresAtUnixMs()));
        }
        if(routes.isEmpty())return unavailable(tenant,tool,RecoveryReadinessReason.NO_WRITE_ROUTES);
        var reason=routes.stream().map(RouteRecoveryReadiness::reason).filter(r->r!=RecoveryReadinessReason.ELIGIBLE)
            .findFirst().orElse(RecoveryReadinessReason.ELIGIBLE);
        boolean ready=reason==RecoveryReadinessReason.ELIGIBLE,qualified=false;
        if(ready)for(var runtime:pkg.path("manifest").path("runtimes"))if(runtime.path("runtimeId").asText().equals(spec.path("runtimeId").asText()))
            qualified=packages.qualifiedForRecovery(tx,deployment,descriptor,runtime,(JsonNode)codec.value(document));
        boolean automatic=ready&&qualified;
        if(ready&&!qualified)reason=RecoveryReadinessReason.UNQUALIFIED_EXECUTOR;
        boolean lookup=routes.stream().allMatch(RouteRecoveryReadiness::lookupAvailable);
        return new ToolRecoveryReadiness(tenant.value(),tool.id(),tool.name(),automatic,
            automatic?ReconciliationMethod.DOWNSTREAM_IDEMPOTENCY:lookup?ReconciliationMethod.STATUS_LOOKUP:ReconciliationMethod.MANUAL,reason,routes);
    }
    private JsonNode auth(JsonNode descriptor,String id){
        var matches=new ArrayList<JsonNode>();for(var auth:descriptor.path("httpAuth"))if(auth.path("id").asText().equals(id))matches.add(auth);
        if(matches.size()!=1)throw Failure.conflict();return matches.getFirst();
    }
    private boolean credentialReady(Store.Session tx,JsonNode descriptor,ServiceProfile profile,String routeId,TargetDedupeGuarantee guarantee){
        var route=profile.routes().stream().filter(r->r.id().equals(routeId)).findFirst().orElse(null);if(route==null)return false;
        if(route.auth()==null)return guarantee.scope()==TargetDedupeScope.GLOBAL;
        JsonNode auth;try{auth=auth(descriptor,route.auth());}catch(Failure missing){return false;}
        if(!auth.path("type").asText().equals("API_KEY")||!auth.path("secret").isTextual()||!auth.path("header").isTextual())return false;
        var secret=tx.enterprise().secret(auth.path("secret").asText());
        return secret!=null&&(guarantee.scope()==TargetDedupeScope.GLOBAL
            ||Objects.equals(guarantee.credentialVersion(),secret.version()));
    }
}
