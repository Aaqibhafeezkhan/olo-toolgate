// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.contracts.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.function.Function;

/** Pure request mapping over the platform's active-host lookup. Hostnames never authenticate a principal. */
@ApplicationScoped
public class TenantRequestResolver {
    public record Authority(String host,int port) {}
    public record Context(TenantId tenant,Authority authority) {}
    private final Function<String,TenantId> activeHost;
    @Inject public TenantRequestResolver(TenantRegistry registry){this.activeHost=registry::activeHost;}
    TenantRequestResolver(Function<String,TenantId> activeHost){this.activeHost=activeHost;}
    public static Authority authority(List<String> values,int defaultPort) {
        if(values==null||values.size()!=1||defaultPort<1||defaultPort>65535)throw Failure.validation();
        String value=values.getFirst();
        if(value==null||value.isEmpty()||value.length()>261||value.chars().anyMatch(c->c<=32||c==127||"/@,\\?#;".indexOf(c)>=0))throw Failure.validation();
        String host,port=null;
        if(value.startsWith("[")) {
            int end=value.indexOf(']');if(end<2||value.indexOf('[',1)>=0||value.indexOf(']',end+1)>=0)throw Failure.validation();
            host=value.substring(1,end);if(!host.contains(":"))throw Failure.validation();
            if(end+1<value.length()){if(value.charAt(end+1)!=':')throw Failure.validation();port=value.substring(end+2);}
        }else{
            int colon=value.indexOf(':');if(colon>=0){if(value.indexOf(':',colon+1)>=0)throw Failure.validation();host=value.substring(0,colon);port=value.substring(colon+1);}else host=value;
        }
        int selected=defaultPort;
        if(port!=null){if(!port.matches("[1-9][0-9]{0,4}"))throw Failure.validation();selected=Integer.parseInt(port);if(selected>65535)throw Failure.validation();}
        try{return new Authority(TenantHosts.normalize(host),selected);}catch(IllegalArgumentException invalid){throw Failure.validation();}
    }
    /** Expected UUID is optional, including protected device routes whose certificate supplies identity. */
    public Context resolve(List<String> authorities,int defaultPort,String sni,List<String> expectedTenant) {
        var authority=authority(authorities,defaultPort);
        if(sni!=null) {
            try{if(!authority.host().equals(TenantHosts.normalize(sni)))throw misdirected();}
            catch(IllegalArgumentException invalid){throw misdirected();}
        }
        var tenant=activeHost.apply(authority.host());if(tenant==null)throw Failure.tenantUnavailable();
        if(expectedTenant!=null&&!expectedTenant.isEmpty()) {
            if(expectedTenant.size()!=1)throw Failure.validation();
            TenantId expected;try{expected=new TenantId(expectedTenant.getFirst());}catch(IllegalArgumentException invalid){throw Failure.validation();}
            if(!tenant.equals(expected))throw mismatch();
        }
        return new Context(tenant,authority);
    }
    /** Call before identity observation, cookies, enrollment writes or tenant application operations. */
    public static void principal(Context context,String verifiedTenant) {
        TenantId tenant;
        try{tenant=new TenantId(verifiedTenant);}catch(IllegalArgumentException invalid){throw new Failure(ErrorCode.UNAUTHORIZED,401,"Invalid verified identity");}
        if(!context.tenant().equals(tenant))throw mismatch();
    }
    static Failure mismatch(){return new Failure(ErrorCode.FORBIDDEN,403,"TENANT_MISMATCH");}
    static Failure misdirected(){return new Failure(ErrorCode.VALIDATION,421,"MISDIRECTED_REQUEST");}
}
