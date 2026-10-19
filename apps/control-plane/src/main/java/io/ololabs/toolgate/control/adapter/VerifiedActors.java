// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.ErrorCode;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** The HTTP security layer verifies JWT signatures, issuer and audience before this adapter runs.
 * Stable human identity is resolved once per request; IdP role names never grant directory access.
 * Dedicated machine routes retain their authenticated service roles and cannot become human admins.
 */
@RequestScoped
public class VerifiedActors {
    @Inject JsonWebToken jwt;
    @Inject PostgresStore store;
    @Inject ContractCodec codec;
    @Inject DirectoryService directory;
    @Inject TenantRequest request;
    @Inject io.quarkus.security.identity.SecurityIdentity security;
    private DirectoryService.Actor human; private long sessionEpoch;

    private DirectoryService.Actor principal() {
        if(security.isAnonymous())throw unauthorized();
        Object tenant=jwt.getClaim("tenant_id");long now=System.currentTimeMillis()/1000;
        if(!(tenant instanceof String value)||jwt.getIssuer()==null||jwt.getSubject()==null||jwt.getSubject().isBlank()
            ||jwt.getIssuedAtTime()>now||jwt.getIssuedAtTime()<now-900||jwt.getExpirationTime()<=now
            ||jwt.getExpirationTime()<=jwt.getIssuedAtTime()||jwt.getExpirationTime()-jwt.getIssuedAtTime()>900)throw unauthorized();
        TenantRequestResolver.principal(request.context(),value);
        try {return new DirectoryService.Actor(new Ids.TenantId(value),DirectoryService.digest(jwt.getIssuer()+"\n"+jwt.getSubject()),false);}
        catch(IllegalArgumentException invalid) {throw unauthorized();}
    }
    public DirectoryService.Actor human() {
        if(human!=null)return human;
        var principal=principal();Object user=jwt.getClaim("user_id"),epoch=jwt.getClaim("session_epoch");
        epoch=integerClaim(epoch);
        if(user!=null&&!(user instanceof String)||epoch!=null&&(!(epoch instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.longValue()||n.longValue()<1||n.longValue()>9007199254740991L))throw unauthorized();
        var identity=new IdentityIntake(store,codec).observe(principal.tenant(),jwt.getIssuer(),jwt.getSubject(),
            user instanceof String id?id:null,epoch instanceof Number n?n.longValue():null,
            jwt.getIssuedAtTime()*1000,jwt.getExpirationTime()*1000,System.currentTimeMillis());
        if(!identity.enabled())throw new Failure(ErrorCode.FORBIDDEN,403,"User awaits administrator enablement");
        sessionEpoch=identity.sessionEpoch();var session=DirectoryService.digest(jwt.getRawToken());
        var current=store.transaction(principal.tenant(),false,tx->ConsoleDeviceSessions.current(tx,codec,session,identity.userId(),System.currentTimeMillis()));
        human=directory.portalActor(principal.tenant(),principal.id(),identity.userId()).consoleSession(session,jwt.getExpirationTime()*1000,current);return human;
    }
    public long humanEpoch(){human();return sessionEpoch;}
    public DirectoryService.Actor management() {
        var actor=human();directory.requirePortal(actor,actor.userId());return actor;
    }
    public DirectoryService.Actor service(String role) {
        var actor=principal();
        if(!security.hasRole(role))throw new Failure(ErrorCode.FORBIDDEN,403,"Service identity required");
        // Authentication roles are accepted only by explicit machine endpoints, never CRUD adapters.
        return actor;
    }
    public DirectoryService.Actor bundleReader() {
        return security.hasRole("toolgate-bundle-reader")?service("toolgate-bundle-reader"):management();
    }
    private static Failure unauthorized() {return new Failure(ErrorCode.UNAUTHORIZED,401,"Invalid verified identity");}
    /** MicroProfile custom numeric claims can arrive as JSON-P values. */
    static Object integerClaim(Object value) {
        if(value instanceof jakarta.json.JsonNumber number) {
            if(!number.isIntegral())throw unauthorized();
            try {return number.longValueExact();}catch(ArithmeticException invalid){throw unauthorized();}
        }
        return value;
    }
}
