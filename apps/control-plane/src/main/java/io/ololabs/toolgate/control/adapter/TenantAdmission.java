// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.contracts.ErrorCode;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Bounded per-replica admission. No tenant names become metric labels or unbounded caches. */
final class TenantAdmission {
    private static final long WINDOW=1_000_000_000L;
    private final int rate,concurrent,capacity;
    private final LongSupplier clock;
    private final Map<TenantId,Bucket> buckets=new LinkedHashMap<>();
    private long denied;
    private static final class Bucket { long window;int requests,active;Bucket(long now){window=now;} }
    TenantAdmission(int rate,int concurrent){this(rate,concurrent,4096,System::nanoTime);}
    TenantAdmission(int rate,int concurrent,int capacity,LongSupplier clock){
        if(rate<0||rate>100000||concurrent<1||concurrent>1024||capacity<1||capacity>4096)throw new IllegalArgumentException("Invalid tenant admission limits");
        this.rate=rate;this.concurrent=concurrent;this.capacity=capacity;this.clock=clock;
    }
    synchronized Lease enter(TenantId tenant){
        long now=clock.getAsLong();var bucket=buckets.get(Objects.requireNonNull(tenant));
        if(bucket==null){
            if(buckets.size()>=capacity)buckets.entrySet().removeIf(entry->entry.getValue().active==0&&now-entry.getValue().window>=WINDOW);
            if(buckets.size()>=capacity){denied++;throw Failure.unavailable();}
            bucket=new Bucket(now);buckets.put(tenant,bucket);
        }
        if(now-bucket.window>=WINDOW){bucket.window=now;bucket.requests=0;}
        if(bucket.active>=concurrent||rate>0&&bucket.requests>=rate){denied++;throw new Failure(ErrorCode.CONFLICT,429,"Tenant admission exhausted");}
        bucket.requests++;bucket.active++;return new Lease(bucket);
    }
    synchronized long denied(){return denied;}
    final class Lease implements AutoCloseable {
        private final Bucket bucket;private final AtomicBoolean closed=new AtomicBoolean();
        Lease(Bucket bucket){this.bucket=bucket;}
        public void close(){if(closed.compareAndSet(false,true))synchronized(TenantAdmission.this){bucket.active--;}}
    }
}
