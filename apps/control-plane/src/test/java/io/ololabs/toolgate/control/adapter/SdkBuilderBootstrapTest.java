// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Optional;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.control.application.Failure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class SdkBuilderBootstrapTest {
    @TempDir Path temp;
    private SdkBuilderBootstrap configured(String mode,boolean quickstart) {
        var bootstrap=new SdkBuilderBootstrap();
        bootstrap.tenancy=new TenancyConfiguration(){
            @Override public String mode(){return mode;}
            @Override public TenantId defaultId(){return new TenantId("10000000-0000-4000-8000-000000000001");}
            @Override public void checkSingleRegistry(){}
        };
        bootstrap.config=(org.eclipse.microprofile.config.Config)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.eclipse.microprofile.config.Config.class},(proxy,method,args)->{
            if(method.getName().equals("getOptionalValue")&&args[0].equals("toolgate.quickstart.enabled"))return Optional.of(quickstart);
            if(!method.getName().equals("getValue"))throw new AssertionError(method.getName());
            return args[0].equals("toolgate.control.builder.enabled")?true:args[0].equals("toolgate.control.builder.data-directory")?temp.resolve("builder").toString():temp.resolve("missing").toString();
        });
        return bootstrap;
    }
    @Test void enabledBuilderRejectsInvalidToolchainBeforeSchedulingTenantWork(){
        var bootstrap=configured("single",true);
        assertTrue(assertThrows(IllegalStateException.class,()->bootstrap.start(null)).getMessage().contains("fixed JDK"));bootstrap.stop(null);
    }
    @Test void sharedAndProductionModesRejectBeforeCreatingBuildState(){
        for(var setting:java.util.List.of(new Object[]{"subdomain",true},new Object[]{"subdomain",false},new Object[]{"single",false})){
            var bootstrap=configured((String)setting[0],(Boolean)setting[1]);
            assertTrue(assertThrows(IllegalStateException.class,()->bootstrap.start(null)).getMessage().contains("isolated external builds"));
            assertFalse(Files.exists(temp.resolve("builder")));bootstrap.stop(null);
        }
    }
    @Test void localBuilderNeverCreatesForeignTenantDirectories() throws Exception {
        var bootstrap=configured("single",true);
        // Toolchain validation leaves the selected owner and private root, but schedules no work.
        assertThrows(IllegalStateException.class,()->bootstrap.start(null));
        var foreign=TenantId.create();
        assertThrows(Failure.class,()->bootstrap.forTenant(foreign));
        assertFalse(Files.exists(temp.resolve("builder").resolve(foreign.value())));
    }
}
