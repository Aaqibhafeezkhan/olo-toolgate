// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class SdkBuilderBootstrapTest {
    @TempDir Path temp;
    @Test void enabledBuilderRejectsInvalidToolchainBeforeSchedulingTenantWork(){
        var bootstrap=new SdkBuilderBootstrap();
        bootstrap.config=(org.eclipse.microprofile.config.Config)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.eclipse.microprofile.config.Config.class},(proxy,method,args)->{
            if(!method.getName().equals("getValue"))throw new AssertionError(method.getName());
            return args[0].equals("toolgate.control.builder.enabled")?true:args[0].equals("toolgate.control.builder.data-directory")?temp.toString():temp.resolve("missing").toString();
        });
        assertThrows(IllegalStateException.class,()->bootstrap.start(null));bootstrap.stop(null);
    }
}
