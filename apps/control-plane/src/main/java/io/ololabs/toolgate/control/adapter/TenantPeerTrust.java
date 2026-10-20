// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.contracts.*;
import java.security.cert.X509Certificate;
import java.util.List;

/** Public listener roots may identify a foreign peer, but can never authorize it. */
final class TenantPeerTrust implements DeviceIssuer {
    private final DeviceIssuer delegate;
    private final TenantId tenant;
    private final List<X509Certificate> listenerRoots;
    TenantPeerTrust(DeviceIssuer delegate,TenantId tenant,List<X509Certificate> listenerRoots){this.delegate=delegate;this.tenant=tenant;this.listenerRoots=List.copyOf(listenerRoots);}
    public String fingerprint(String csr){return delegate.fingerprint(csr);}
    public String issuerCertificate(){return delegate.issuerCertificate();}
    public DeviceIdentity issue(String csr,String device,String tenant,String user,String server,long now){return delegate.issue(csr,device,tenant,user,server,now);}
    public DeviceIdentity issue(String csr,String device,String tenant,String user,String server,long now,long expires){return delegate.issue(csr,device,tenant,user,server,now,expires);}
    public SignedClientDiscovery discovery(String payload){throw Failure.unavailable();}
    public Peer verifyPeer(X509Certificate certificate,long now){
        try{return delegate.verifyPeer(certificate,now);}
        catch(Failure rejected){
            if(rejected.code()==ErrorCode.TENANT_MISMATCH)throw rejected;
            for(var root:listenerRoots){
                Peer peer;
                try{peer=X509DeviceIssuer.verifyPeer(root,certificate,now);}catch(Failure invalid){continue;}
                if(!peer.tenant().equals(tenant))throw new Failure(ErrorCode.TENANT_MISMATCH,403,"Tenant identity mismatch");
            }
            // A retired own-tenant CA in the TLS store does not override authoritative metadata.
            throw rejected;
        }
    }
}
