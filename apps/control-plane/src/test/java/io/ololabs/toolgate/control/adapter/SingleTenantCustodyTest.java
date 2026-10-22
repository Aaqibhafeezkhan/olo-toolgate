// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class SingleTenantCustodyTest {
    @TempDir Path directory;
    @Test void localModeRefusesOtherCustodyEvenBeforeItsTenantBecomesActive(){
        var registry=new TenantRegistry(SqliteState.open(directory.resolve("state.sqlite")),new ContractCodec());
        var first=registry.allocate("default","Default");
        var single=new TenancyConfiguration(){
            @Override public String mode(){return "single";}
            @Override public io.ololabs.toolgate.control.domain.Ids.TenantId defaultId(){return first.id();}
        };
        single.registry=registry;single.checkSingleRegistry();single.checkManifestTenants(1);
        assertThrows(IllegalStateException.class,()->single.checkManifestTenants(2));
        registry.allocate("other","Other protected tenant");
        assertThrows(IllegalStateException.class,single::checkSingleRegistry);
        var shared=new TenancyConfiguration(){@Override public String mode(){return "subdomain";}};
        shared.checkManifestTenants(2);shared.checkSingleRegistry();
    }
}
