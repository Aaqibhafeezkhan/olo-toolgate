// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.contracts.*;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ResponseTelemetryTest {
    private Object filter(int status,Object initial){
        var entity=new AtomicReference<>(initial);var headers=new MultivaluedHashMap<String,Object>();
        var response=(ContainerResponseContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ContainerResponseContext.class},(proxy,method,args)->switch(method.getName()){
            case "getStatus" -> status;case "getEntity" -> entity.get();case "getHeaders" -> headers;
            case "setEntity" -> {entity.set(args[0]);yield null;}default -> throw new AssertionError(method.getName());
        });
        var filter=new ResponseTelemetry();filter.correlation=new Correlation();filter.filter(null,response);
        assertEquals("no-store",headers.getFirst("Cache-Control"));return entity.get();
    }
    @Test void preservesTypedApplicationErrorsAndActionableDetails(){
        for(var code:List.of(ErrorCode.TENANT_MISMATCH,ErrorCode.TENANT_UNAVAILABLE,ErrorCode.MISDIRECTED_REQUEST)){
            var error=new ErrorEnvelope(code,"request",false);assertSame(error,filter(403,error));
        }
        var detail=new ControlErrorDetail(ControlErrorDetailCode.ONE_DEVICE_PER_ACCOUNT,List.of("device"),"request");assertSame(detail,filter(409,detail));
    }
    @Test void normalizesUntypedFrameworkErrors(){
        assertEquals(ErrorCode.FORBIDDEN,((ErrorEnvelope)filter(403,null)).code());
        assertEquals(ErrorCode.UNAUTHORIZED,((ErrorEnvelope)filter(401,"Authentication failed")).code());
    }
}
