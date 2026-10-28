// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("try")
final class TenantAdmissionTest {
    @Test void exhaustionAndWindowAreTenantLocalAndReleaseIsIdempotent(){
        var time=new AtomicLong();var limiter=new TenantAdmission(2,1,4,time::get);
        var a=TenantId.create();var b=TenantId.create();
        var first=limiter.enter(a);
        assertEquals(429,assertThrows(Failure.class,()->limiter.enter(a)).status());
        try(var other=limiter.enter(b)){}
        first.close();first.close();
        try(var second=limiter.enter(a)){}
        assertEquals(429,assertThrows(Failure.class,()->limiter.enter(a)).status());
        time.set(1_000_000_000L);
        try(var next=limiter.enter(a)){}
        assertEquals(2,limiter.denied());
    }
    @Test void aFullMapNeverEvictsActiveOwnersAndIdleOwnersExpire(){
        var time=new AtomicLong();var limiter=new TenantAdmission(0,1,1,time::get);
        var a=TenantId.create();var b=TenantId.create();var active=limiter.enter(a);
        time.set(2_000_000_000L);
        assertEquals(503,assertThrows(Failure.class,()->limiter.enter(b)).status());
        assertEquals(429,assertThrows(Failure.class,()->limiter.enter(a)).status());
        active.close();time.set(4_000_000_000L);
        try(var admitted=limiter.enter(b)){}
    }
}
