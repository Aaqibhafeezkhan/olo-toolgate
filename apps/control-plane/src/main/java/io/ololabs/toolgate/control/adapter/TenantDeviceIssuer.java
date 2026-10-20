// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.contracts.*;
import java.security.cert.X509Certificate;
import java.util.List;

/** An issuer is immutable to a tenant/server and verifies only that tenant's retained CA ring. */
final class TenantDeviceIssuer implements DeviceIssuer {
    private final TenantId tenant;
    private final String server;
    private final DeviceIssuer active;
    private final List<X509Certificate> authorities;
    TenantDeviceIssuer(TenantId tenant,String server,DeviceIssuer active,List<X509Certificate> authorities){this.tenant=tenant;this.server=server;this.active=active;this.authorities=List.copyOf(authorities);}
    public String fingerprint(String csr){return active.fingerprint(csr);}
    public String issuerCertificate(){return active.issuerCertificate();}
    public DeviceIdentity issue(String csr,String device,String tenant,String user,String server,long now){return issue(csr,device,tenant,user,server,now,Long.MAX_VALUE);}
    public DeviceIdentity issue(String csr,String device,String tenant,String user,String server,long now,long expires){
        if(!this.tenant.value().equals(tenant)||!this.server.equals(server))throw new Failure(ErrorCode.TENANT_MISMATCH,403,"Tenant identity mismatch");
        return active.issue(csr,device,tenant,user,server,now,expires);
    }
    public SignedClientDiscovery discovery(String payload){throw Failure.unavailable();}
    public Peer verifyPeer(X509Certificate peer,long now){
        for(var ca:authorities){
            Peer verified;
            try{verified=X509DeviceIssuer.verifyPeer(ca,peer,now);}catch(Failure rejected){continue;}
            if(!verified.tenant().equals(tenant)||!verified.serverId().equals(server))throw new Failure(ErrorCode.TENANT_MISMATCH,403,"Tenant identity mismatch");
            return verified;
        }
        throw new Failure(ErrorCode.UNAUTHORIZED,401,"Device certificate required");
    }
}
