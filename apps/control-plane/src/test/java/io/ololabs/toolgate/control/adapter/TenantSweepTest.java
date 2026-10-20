// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TenantSweepTest {
    private List<TenantId> tenants(){
        return java.util.stream.IntStream.rangeClosed(1,70).mapToObj(n->new TenantId("10000000-0000-4000-8000-"+String.format("%012d",n))).toList();
    }
    @Test void boundedTicksContinuePastFailingTenantAndRestartOnlyAfterCompleteCycle(){
        var all=tenants();var visited=new ArrayList<TenantId>();
        var sweep=new TenantSweep(32,(cursor,limit)->all.stream().filter(t->t.value().compareTo(cursor)>0).limit(limit).toList(),tenant->{
            visited.add(tenant);if(tenant.equals(all.getFirst()))throw new IllegalStateException("Unavailable tenant");
        });
        assertEquals(new TenantSweep.Result(32,1,false),sweep.tick());assertEquals(32,visited.size());
        assertEquals(new TenantSweep.Result(32,0,false),sweep.tick());assertEquals(64,visited.size());
        assertEquals(new TenantSweep.Result(6,0,true),sweep.tick());assertEquals(all,visited);
        assertEquals(new TenantSweep.Result(32,1,false),sweep.tick());assertEquals(all.getFirst(),visited.get(70));
    }
    @Test void registryFailureRetriesSamePageWithoutReplayingCompletedWork(){
        var all=tenants();var visited=new ArrayList<TenantId>();var fail=new AtomicBoolean();
        var sweep=new TenantSweep(32,(cursor,limit)->{
            if(fail.get())throw new IllegalStateException("Unavailable registry");
            return all.stream().filter(t->t.value().compareTo(cursor)>0).limit(limit).toList();
        },visited::add);
        sweep.tick();fail.set(true);assertThrows(IllegalStateException.class,sweep::tick);assertEquals(32,visited.size());
        fail.set(false);sweep.tick();sweep.tick();assertEquals(all,visited);
    }
}
