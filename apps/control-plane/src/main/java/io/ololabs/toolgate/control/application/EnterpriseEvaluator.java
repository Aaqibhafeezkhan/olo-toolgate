// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.ActorHop;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.time.*;
import java.util.*;
import static io.ololabs.toolgate.contracts.EnterpriseDecisionReason.*;

/** Pure group authorization. No I/O, mutable cache, clock reads or permission from ownership. */
public final class EnterpriseEvaluator {
    private final Codec codec;
    public EnterpriseEvaluator(Codec codec) { this.codec=codec; }
    public EnterpriseDecision evaluate(Ids.TenantId tenant,Directory directory,EnterpriseEvaluation request) {
        return evaluate(tenant,directory,request,null);
    }
    /** Quota usage, when required, must come from the authoritative durable reservation window. */
    public EnterpriseDecision evaluate(Ids.TenantId tenant,Directory directory,EnterpriseEvaluation request,Long invocationsInWindow) {
        return evaluateWithQuotas(tenant,directory,request,budget->invocationsInWindow);
    }
    public EnterpriseDecision evaluateWithQuotas(Ids.TenantId tenant,Directory directory,EnterpriseEvaluation request,java.util.function.Function<String,Long> quotas){
        var evaluation=new Evaluation(directory,request,quotas);
        try {return evaluation.run(tenant);} catch(Rejected failure) {return evaluation.result(Decision.BLOCK,failure.reason,List.of(),List.of());}
        catch(IllegalArgumentException failure) {return evaluation.result(Decision.BLOCK,INVALID_CONTEXT,List.of(),List.of());}
        catch(Failure failure) {if(failure.status()!=400&&failure.status()!=422)throw failure;return evaluation.result(Decision.BLOCK,RESOURCE_REJECTED,List.of(),List.of());}
    }
    public String toolDigest(Directory directory,ControlTool tool) {
        var extractor=directory.entries().get(Kind.EXTRACTOR.id(tool.extractorId()));
        return DirectoryService.digest(codec.json(tool.definition())+"\n"+tool.version()+"\n"+tool.packageDigest()+"\n"+(extractor==null?"":extractor.document()));
    }
    public static boolean selected(GroupSelection selection,String id) { return selection.all()||selection.ids().contains(id); }
    public static boolean intersects(GroupSelection selection,List<String> ids) { return selection.all()||ids.stream().anyMatch(selection.ids()::contains); }
    public static boolean resource(EnterpriseResourceRule rule,ResourceDescriptor actual) {
        if(rule.kind()!=actual.kind())return false;
        return switch(rule.match()) {
            case ANY->rule.locator().isEmpty();
            case EXACT->rule.locator().equals(actual.locator());
            case PREFIX->{var p=rule.locator();yield actual.locator().equals(p)||actual.locator().startsWith(p.endsWith("/")?p:p+"/");}
        };
    }
    private static final class Rejected extends RuntimeException {
        private static final long serialVersionUID=1L;
        final EnterpriseDecisionReason reason;
        Rejected(EnterpriseDecisionReason reason) { this.reason=reason; }
    }
    private final class Evaluation {
        final Directory directory; final EnterpriseEvaluation request; final EnterpriseContext context; final java.util.function.Function<String,Long> quotas;
        final List<String> teams=new ArrayList<>(); final List<List<String>> actorGroups=new ArrayList<>();
        ControlExecutionBinding binding; ControlDeviceEvidence evidence; String toolGroup; long validUntil;
        Evaluation(Directory directory,EnterpriseEvaluation request,java.util.function.Function<String,Long> quotas) { this.directory=directory;this.request=request;this.context=request.context();this.quotas=quotas;validUntil=Math.min(9007199254740991L,request.nowUnixMs()+15000); }
        void require(boolean condition,EnterpriseDecisionReason reason) { if(!condition)throw new Rejected(reason); }
        <T> T active(Kind kind,String id,Class<T> type,EnterpriseDecisionReason reason) {
            require(id!=null,reason); var entry=directory.entries().get(kind.id(id));require(entry!=null&&entry.enabled(),reason);return codec.model(entry.document(),type);
        }
        <T> List<T> records(Kind kind,Class<T> type) {
            return directory.entries().values().stream().filter(e->e.id().kind()==kind&&e.enabled()).sorted(Comparator.comparing(e->e.id().value())).map(e->codec.model(e.document(),type)).toList();
        }
        EnterpriseDecision result(Decision decision,EnterpriseDecisionReason reason,List<EnterpriseWitness> witnesses,List<String> obligations) {
            var diagnostic=DirectoryService.digest(context.tenantId()+"\n"+context.requestId()+"\n"+directory.revision()).substring(0,32);
            return new EnterpriseDecision(decision,reason,witnesses,obligations,directory.revision(),directory.revision(),validUntil,diagnostic);
        }
        EnterpriseDecision run(Ids.TenantId tenant) {
            require(tenant.value().equals(context.tenantId()),INVALID_CONTEXT);
            require(java.util.Objects.equals(request.authorityRevision(),directory.revision()),STALE_AUTHORITY);
            require(request.nowUnixMs()>=0&&request.resources().size()>0&&request.resources().size()<=256,RESOURCE_REJECTED);
            for(var resource:request.resources())require(ResourceExtraction.normalize(resource.kind(),resource.locator()).equals(resource.locator()),RESOURCE_REJECTED);
            var tool=active(Kind.TOOL,request.toolId(),ControlTool.class,VERSION_MISMATCH);
            require(request.toolDigest().equals(toolDigest(directory,tool))&&request.packageDigest().equals(tool.packageDigest()),VERSION_MISMATCH);
            require(tool.definition().actions().stream().anyMatch(a->a.name().equals(request.action())&&request.resources().stream().allMatch(r->a.resourceKinds().contains(r.kind()))),RESOURCE_REJECTED);
            active(Kind.EXTRACTOR,tool.extractorId(),ControlResourceExtractor.class,VERSION_MISMATCH);
            var groups=GroupGraph.memberships(directory,codec,Kind.TOOL,tool.id(),true); require(groups.size()==1,GROUP_UNAVAILABLE);toolGroup=groups.getFirst();
            var device=active(Kind.DEVICE,context.deviceId(),ControlDevice.class,DEVICE_UNTRUSTED);
            binding=active(Kind.BINDING,context.bindingId(),ControlExecutionBinding.class,NO_BINDING);
            StandardAccessPresets.validateBinding(binding);
            require(binding.toolGroupId().equals(toolGroup)&&binding.actions().contains(request.action())&&binding.allowedPackageDigests().contains(request.packageDigest()),NO_BINDING);
            active(Kind.DEVICE_GROUP,binding.deviceGroupId(),ControlDeviceGroup.class,GROUP_UNAVAILABLE);
            require(GroupGraph.memberships(directory,codec,Kind.DEVICE,device.id(),true).contains(binding.deviceGroupId()),NO_BINDING);
            require(!binding.requireOnline()||request.online(),STALE_AUTHORITY);
            if(binding.ownerDependency()) active(Kind.USER,device.ownerUserId(),ControlUser.class,IDENTITY_DISABLED);
            evidence=records(Kind.DEVICE_EVIDENCE,ControlDeviceEvidence.class).stream().filter(e->e.deviceId().equals(device.id())&&e.verifiedAtUnixMs()<=request.nowUnixMs()&&e.expiresAtUnixMs()>request.nowUnixMs()).max(Comparator.comparing(ControlDeviceEvidence::verifiedAtUnixMs)).orElse(null);
            if(evidence!=null)validUntil=Math.min(validUntil,evidence.expiresAtUnixMs());
            identity();
            var grants=records(Kind.GRANT,ControlAccessGrant.class);
            var witnesses=new ArrayList<EnterpriseWitness>();
            if(context.mode()==RequestMode.HUMAN) {
                for(var team:teams)for(var grant:grants)if(grant.purpose()==EnterpriseGrantPurpose.HUMAN&&source(grant,Kind.TEAM,team)&&matches(grant.scope(),false,"GRANT:"+grant.id())&&secret(grants,Kind.TEAM,team))
                    witnesses.add(new EnterpriseWitness(team,grant.id(),null,null,null,null,binding.id(),List.of(team,grant.id(),binding.id())));
            } else if(context.mode()==RequestMode.SERVICE) {
                for(var group:actorGroups.getFirst())for(var service:grants)if(service.purpose()==EnterpriseGrantPurpose.SERVICE&&source(service,Kind.AGENT_GROUP,group)&&matches(service.scope(),false,"GRANT:"+service.id()))
                    for(var cap:capabilities(grants,group))for(var path:chain(grants,0,group,new ArrayList<>(List.of(group,service.id(),cap.id()))))
                        witnesses.add(new EnterpriseWitness(null,null,path.group,path.capability,null,service.id(),binding.id(),path.ids));
            } else {
                for(var team:teams)for(var grant:grants)if(grant.purpose()==EnterpriseGrantPurpose.HUMAN&&source(grant,Kind.TEAM,team)&&matches(grant.scope(),false,"GRANT:"+grant.id())&&secret(grants,Kind.TEAM,team))
                    for(var delegation:records(Kind.DELEGATION,ControlDelegation.class))if(delegation.teamId().equals(team)&&actorGroups.getFirst().contains(delegation.agentGroupId())&&matches(delegation.scope(),false,"DELEGATION:"+delegation.id()))
                        for(var cap:capabilities(grants,delegation.agentGroupId()))for(var path:chain(grants,0,delegation.agentGroupId(),new ArrayList<>(List.of(team,grant.id(),delegation.id(),delegation.agentGroupId(),cap.id()))))
                            witnesses.add(new EnterpriseWitness(team,grant.id(),path.group,path.capability,delegation.id(),null,binding.id(),path.ids));
            }
            require(!witnesses.isEmpty(),NO_GRANT);require(witnesses.size()<=512,INVALID_CONTEXT);
            var obligations=new TreeSet<String>();
            var finalGroups=actorGroups.isEmpty()?List.<String>of():actorGroups.getLast();
            for(var policy:records(Kind.POLICY,ControlPolicy.class)) {
                boolean applies=context.mode()==RequestMode.SERVICE?policy.teams().all():intersects(policy.teams(),teams);
                applies&=context.mode()==RequestMode.HUMAN?policy.agentGroups().all():intersects(policy.agentGroups(),finalGroups);
                if(!applies||!matches(policy.scope(),true,"POLICY:"+policy.id()))continue;
                if(policy.decision()==Decision.BLOCK)throw new Rejected(BLOCKED);
                if(policy.decision()==Decision.ASK)obligations.add(policy.id());
            }
            witnesses.sort(Comparator.comparing(codec::json));
            return result(obligations.isEmpty()?Decision.ALLOW:Decision.ASK,obligations.isEmpty()?MATCHED:APPROVAL_REQUIRED,List.copyOf(witnesses),List.copyOf(obligations));
        }
        void identity() {
            if(context.mode()!=RequestMode.SERVICE) {
                var user=active(Kind.USER,context.userId(),ControlUser.class,IDENTITY_DISABLED);
                require(context.sessionEpoch()!=null,INVALID_CONTEXT);
                require(records(Kind.IDENTITY_BINDING,ControlIdentityBinding.class).stream().anyMatch(b->b.userId().equals(user.id())&&b.sessionEpoch().equals(context.sessionEpoch())),IDENTITY_DISABLED);
                teams.addAll(GroupGraph.memberships(directory,codec,Kind.USER,user.id(),true));require(!teams.isEmpty(),GROUP_UNAVAILABLE);
            } else require(context.userId()==null&&context.sessionEpoch()==null,INVALID_CONTEXT);
            if(context.mode()==RequestMode.HUMAN) {
                require(context.agentId()==null&&context.workloadBindingId()==null&&context.credentialEpoch()==null&&context.chain().isEmpty(),INVALID_CONTEXT);return;
            }
            require(context.chain().size()<8&&context.credentialEpoch()!=null,INVALID_CONTEXT);
            var hops=new ArrayList<>(context.chain());hops.add(new ActorHop(context.agentId(),context.workloadBindingId()));
            var seen=new HashSet<String>();String previous=null;
            for(var hop:hops) {
                require(seen.add(hop.agentId()),INVALID_CONTEXT);active(Kind.AGENT,hop.agentId(),ControlAgent.class,IDENTITY_DISABLED);
                var workload=active(Kind.WORKLOAD_BINDING,hop.workloadBindingId(),ControlWorkloadBinding.class,IDENTITY_DISABLED);
                require(workload.agentId().equals(hop.agentId())&&workload.mode()==context.mode()&&Objects.equals(workload.delegatedUserId(),context.userId())&&Objects.equals(workload.parentBindingId(),previous),INVALID_CONTEXT);
                require(Objects.equals(workload.delegatedSessionEpoch(),context.sessionEpoch()),IDENTITY_DISABLED);
                require(workload.expiresAtUnixMs()>request.nowUnixMs(),EXPIRED);
                if(hop==hops.getLast())require(workload.credentialEpoch().equals(context.credentialEpoch()),IDENTITY_DISABLED);
                validUntil=Math.min(validUntil,workload.expiresAtUnixMs());previous=workload.id();
                var groups=GroupGraph.memberships(directory,codec,Kind.AGENT,hop.agentId(),true);require(!groups.isEmpty(),GROUP_UNAVAILABLE);actorGroups.add(groups);
            }
        }
        boolean source(ControlAccessGrant grant,Kind kind,String group) {
            if(grant.sourceType().name().equals(kind.name()))return grant.sourceId().equals(group);
            if(grant.sourceType()!=EnterpriseSourceType.ROLE)return false;
            var entry=directory.entries().get(Kind.ROLE.id(grant.sourceId()));if(entry==null||!entry.enabled())return false;
            return GroupGraph.roles(directory.entries().get(kind.id(group)),codec).contains(grant.sourceId());
        }
        List<ControlAccessGrant> capabilities(List<ControlAccessGrant> grants,String group) {
            if(!secret(grants,Kind.AGENT_GROUP,group))return List.of();
            return grants.stream().filter(g->g.purpose()==EnterpriseGrantPurpose.CAPABILITY&&source(g,Kind.AGENT_GROUP,group)&&matches(g.scope(),false,"GRANT:"+g.id())).toList();
        }
        boolean secret(List<ControlAccessGrant> grants,Kind kind,String group){
            var secrets=request.resources().stream().filter(r->r.kind()==ResourceKind.CUSTOM&&r.locator().startsWith("secret://")).toList();if(secrets.isEmpty())return true;
            return grants.stream().anyMatch(g->{var s=g.scope();return g.purpose()==EnterpriseGrantPurpose.SECRET&&source(g,kind,group)&&selected(s.toolGroups(),toolGroup)&&selected(s.deviceGroups(),binding.deviceGroupId())&&(s.allActions()||s.actions().contains(request.action()))&&secrets.stream().allMatch(r->s.resources().stream().anyMatch(rule->resource(rule,r)))&&conditions(s.conditions(),"GRANT:"+g.id());});
        }
        record Path(String group,String capability,List<String> ids) {}
        List<Path> chain(List<ControlAccessGrant> grants,int hop,String group,List<String> provenance) {
            if(hop==actorGroups.size()-1) {
                var result=new ArrayList<>(provenance);result.add(binding.id());
                return List.of(new Path(group,provenance.getLast(),List.copyOf(new LinkedHashSet<>(result))));
            }
            var results=new ArrayList<Path>();
            for(var edge:records(Kind.AGENT_DELEGATION,ControlAgentDelegation.class))if(edge.fromAgentGroupId().equals(group)&&actorGroups.get(hop+1).contains(edge.toAgentGroupId())&&edge.maximumDepth()>=actorGroups.size()-1&&matches(edge.scope(),false,"AGENT_DELEGATION:"+edge.id()))
                for(var cap:capabilities(grants,edge.toAgentGroupId())) {
                    var path=new ArrayList<>(provenance);path.addAll(List.of(edge.id(),edge.toAgentGroupId(),cap.id()));results.addAll(chain(grants,hop+1,edge.toAgentGroupId(),path));require(results.size()<=512,INVALID_CONTEXT);
                }
            return results;
        }
        boolean matches(EnterpriseScope scope,boolean guardrail,String budget) {
            if(!selected(scope.toolGroups(),toolGroup)||!selected(scope.deviceGroups(),binding.deviceGroupId())||!scope.allActions()&&!scope.actions().contains(request.action()))return false;
            boolean resources=guardrail?request.resources().stream().anyMatch(r->scope.resources().stream().anyMatch(s->resource(s,r))):request.resources().stream().allMatch(r->scope.resources().stream().anyMatch(s->resource(s,r)));
            if(!resources)return false;
            if(guardrail) {
                if(scope.conditions().maxAmountMinorUnits()!=null)require(request.amountMinorUnits()!=null,RESOURCE_REJECTED);
                if(scope.conditions().maxInvocationsPerMinute()!=null)require(quotas.apply(budget)!=null,STALE_AUTHORITY);
                if(scope.conditions().requireOnline()||scope.conditions().highRisk())require(request.online(),STALE_AUTHORITY);
            }
            return conditions(scope.conditions(),budget);
        }
        boolean conditions(EnterpriseConditions c,String budget) {
            Long quota=c.maxInvocationsPerMinute()==null?null:quotas.apply(budget);
            if(request.nowUnixMs()<c.notBeforeUnixMs()||c.expiresAtUnixMs()!=0&&request.nowUnixMs()>=c.expiresAtUnixMs())return false;
            if(c.expiresAtUnixMs()!=0)validUntil=Math.min(validUntil,c.expiresAtUnixMs());
            if((c.highRisk()||c.requireOnline())&&!request.online())return false;
            boolean needsEvidence=!c.devicePosture().isEmpty()||!c.regions().isEmpty()||!c.networkCidrs().isEmpty();
            require(!needsEvidence||evidence!=null,DEVICE_UNTRUSTED);
            if(evidence!=null&&(!evidence.posture().containsAll(c.devicePosture())||!c.regions().isEmpty()&&!c.regions().contains(evidence.region())))return false;
            if(!c.networkCidrs().isEmpty()&&c.networkCidrs().stream().noneMatch(n->NetworkRange.parse(n).contains(evidence.verifiedNetworkAddress())))return false;
            if(c.maxAmountMinorUnits()!=null&&(request.amountMinorUnits()==null||request.amountMinorUnits()>c.maxAmountMinorUnits()))return false;
            if(c.maxInvocationsPerMinute()!=null&&(quota==null||quota>=c.maxInvocationsPerMinute()))return false;
            if(!c.hoursUtc().isEmpty()) {
                var now=Instant.ofEpochMilli(request.nowUnixMs()).atZone(ZoneOffset.UTC);int day=now.getDayOfWeek().getValue()%7,minute=now.getHour()*60+now.getMinute();
                if(c.hoursUtc().stream().noneMatch(h->h.dayOfWeek()==day&&h.startMinute()<=minute&&minute<h.endMinute()))return false;
                validUntil=Math.min(validUntil,request.nowUnixMs()+60000-now.getSecond()*1000-now.getNano()/1000000);
            }
            return true;
        }
    }
}
