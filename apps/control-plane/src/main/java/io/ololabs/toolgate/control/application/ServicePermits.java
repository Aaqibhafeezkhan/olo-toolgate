// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.*;

/** Downstream authority derived exclusively from an admitted executor invocation. */
public final class ServicePermits {
    public static final long MAX_LIFETIME_MS = 15000;
    private final Store store;
    private final Codec codec;
    private final EnterpriseOperations operations;
    public ServicePermits(Store store, Codec codec, EnterpriseOperations operations) {
        this.store=store;this.codec=codec;this.operations=operations;
    }
    public Store.Reply issue(Ids.TenantId tenant, java.util.function.Function<Store.Session,String> certificate,
            String executorKeySha256, String document) {
        var request=codec.model(document,ServicePermitIssue.class);
        return store.transaction(tenant,true,tx->{
            String device=certificate.apply(tx);
            var i=operations.invocationIn(tx,request.invocationId());
            var c=i.evaluation().context();
            if(i.state()!=EnterpriseInvocationState.EXECUTING||!device.equals(c.deviceId())
                ||!Objects.equals(i.reservedNonce(),request.fencingToken()))throw ManagementAccess.denied();
            var admission=tx.enterprise().nonce(i.reservedNonce());
            if(admission==null||admission.consumedAt()==null)throw ManagementAccess.denied();
            operations.requireFresh(tenant,tx,i);
            String op=i.evaluation().action();
            if(c.mode()==RequestMode.DELEGATED || (c.mode()==RequestMode.SERVICE&&!op.equals("rag.update")))throw ManagementAccess.denied();
            if(!audience(op).equals(request.audience())||i.evaluation().resources().isEmpty()
                ||i.evaluation().resources().stream().anyMatch(r->r.kind()!=ResourceKind.CUSTOM||!r.locator().startsWith("olo://"+tenant.value()+"/")))throw ManagementAccess.denied();
            String subject=c.mode()==RequestMode.SERVICE?c.agentId():c.userId();
            if(subject==null)throw ManagementAccess.denied();
            long now=operations.clock(tx);
            Long credentialDeadline=tx.enterprise().userCredentialDeadline(i.id());
            if(c.mode()!=RequestMode.SERVICE&&credentialDeadline==null)throw ManagementAccess.denied();
            boolean read=Set.of("memory.read","rag.query").contains(op);
            String nonceId=read?UUID.randomUUID().toString():"service-"+DirectoryService.digest(i.id()+"\n"+request.audience()).substring(0,48);
            var existing=tx.enterprise().nonce(nonceId);
            // One write capability per logical operation, even across executor retries or fencing-token renewal.
            // An ambiguous consumed write is reconciled; minting another nonce must never repeat its effect.
            if(existing!=null&&(existing.consumedAt()!=null||existing.expiresAt()<=now))throw ManagementAccess.denied();
            long expiry=existing==null?Math.min(now+MAX_LIFETIME_MS,Math.min(i.expiresAtUnixMs(),credentialDeadline==null?Long.MAX_VALUE:credentialDeadline)):existing.expiresAt();
            long issuedAt=existing==null?now:existing.issuedAt();
            if(expiry>i.expiresAtUnixMs())throw ManagementAccess.denied();
            var claims=new ServicePermitClaims(operations.issuer(),request.audience(),tenant.value(),subject,
                c.mode()==RequestMode.DELEGATED?c.agentId():null,c.mode(),op,i.evaluation().resources(),
                i.evaluation().argumentsDigest(),executorKeySha256,i.id(),nonceId,i.authorizationEpoch(),issuedAt,expiry,i.reservedNonce());
            String digest=DirectoryService.digest(codec.json(claims));
            if(existing!=null&&!existing.evaluationDigest().equals(digest))throw ManagementAccess.denied();
            var permit=operations.signer().signService(claims);
            if(existing==null)tx.enterprise().reserveNonce(new EnterpriseStore.Nonce(claims.nonce(),i.id(),digest,i.authorizationEpoch(),issuedAt,expiry,null));
            tx.audit(DirectoryService.digest(device),"SERVICE_PERMIT_ISSUE","invocation:"+i.id(),i.revision(),c.requestId(),i.requestDigest());
            return new Store.Reply(200,codec.json(permit),i.revision());
        });
    }
    public Store.Reply consume(DirectoryService.Actor service, ServiceAudience audience, String document) {
        var request=codec.model(document,ServicePermitConsumption.class);
        var claims=operations.signer().verifyService(request.permit());
        return store.transaction(service.tenant(),true,tx->{
            long now=operations.clock(tx);
            var i=operations.invocationIn(tx,claims.invocationId());
            var nonce=tx.enterprise().nonce(claims.nonce());
            if(!claims.iss().equals(operations.issuer())||claims.aud()!=audience
                ||!claims.tenantId().equals(service.tenant().value())||claims.iat()>now||claims.exp()<=now
                ||claims.exp()<=claims.iat()||claims.exp()-claims.iat()>MAX_LIFETIME_MS
                ||!request.operation().equals(claims.operation())||!request.resources().equals(claims.resources())
                ||!request.requestFingerprint().equals(claims.requestFingerprint())||!request.executorKeySha256().equals(claims.executorKeySha256())
                ||i.state()!=EnterpriseInvocationState.EXECUTING||!Objects.equals(i.reservedNonce(),claims.executionNonce())
                ||nonce==null||nonce.consumedAt()!=null||!nonce.invocationId().equals(i.id())
                ||!nonce.evaluationDigest().equals(DirectoryService.digest(codec.json(claims)))
                ||nonce.authorizationEpoch()!=claims.authorizationEpoch())throw ManagementAccess.denied();
            operations.requireFresh(service.tenant(),tx,i);
            tx.enterprise().consumeNonce(claims.nonce(),now);
            tx.audit(service.id(),"SERVICE_PERMIT_CONSUME","invocation:"+i.id(),i.revision(),i.evaluation().context().requestId(),i.requestDigest());
            return new Store.Reply(200,codec.json(new ServicePermitConsumed(true)),i.revision());
        });
    }
    public Store.Reply epoch(DirectoryService.Actor service) {
        // Reading the epoch also persists the anti-rollback clock. Reserve a
        // writer transaction before borrowing a connection (including SQLite).
        return store.transaction(service.tenant(),true,tx->new Store.Reply(200,codec.json(new ServiceAuthorizationEpoch(
            service.tenant().value(),tx.enterprise().authorizationEpoch(),operations.clock(tx))),tx.load().revision()));
    }
    public Map<String,Object> keys(){return Map.of("keys",List.of(operations.signer().servicePublicKey()));}
    private static ServiceAudience audience(String operation) {
        return switch(operation){
            case "memory.read","memory.append","memory.delete","memory.share","memory.export" -> ServiceAudience.olo_memory;
            case "rag.query","rag.ingest","rag.update","rag.delete","rag.share" -> ServiceAudience.olo_rag;
            default -> throw ManagementAccess.denied();
        };
    }
}
