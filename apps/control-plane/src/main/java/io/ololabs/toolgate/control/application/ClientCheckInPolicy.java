// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.ClientCheckIn;
import io.ololabs.toolgate.contracts.v2.ValueSource;

/** Phase A client check-in (platform-requirements.md §17.2). Env-owned: read from the environment at startup, never from the database.
 *  An idle device learns of a job only at its next check-in, so the check-in is the job pickup latency. */
public record ClientCheckInPolicy(long checkInMs,int jitterPct,long offlineAfterMs,ValueSource source) {
    /** The sync deadline a client job must fit in, with the margin left for execution (ADR 016). */
    static final long SYNC_DEADLINE_MS=30000,EXECUTION_MARGIN_MS=5000;
    public static final long QUICKSTART_CHECK_IN_MS=2000,ENTERPRISE_CHECK_IN_MS=2000;
    public static final int ENTERPRISE_JITTER_PCT=20;
    /** Quickstart uses 2 seconds, no jitter, and is offline after two minutes. */
    public static final ClientCheckInPolicy QUICKSTART=new ClientCheckInPolicy(QUICKSTART_CHECK_IN_MS,0,120000,ValueSource.DEFAULT);

    public ClientCheckInPolicy {
        java.util.Objects.requireNonNull(source,"source");
        if(checkInMs<500||checkInMs>3600000||jitterPct<0||jitterPct>50)throw new IllegalArgumentException("Client check-in out of range");
        if(longestIntervalMs(checkInMs,jitterPct)+EXECUTION_MARGIN_MS>SYNC_DEADLINE_MS)
            throw new IllegalArgumentException("Client check-in plus jitter and a 5 s execution margin must fit the 30 s sync deadline; use tasks for longer client work");
        if(offlineAfterMs<1000||offlineAfterMs>86400000||offlineAfterMs<=longestIntervalMs(checkInMs,jitterPct))
            throw new IllegalArgumentException("Client offline threshold must exceed the longest check-in interval");
    }

    /** Enterprise defaults apply to unset values; an unset offline threshold is max(120000, 6 x check-in). */
    public static ClientCheckInPolicy configure(Long checkInMs,Integer jitterPct,Long offlineAfterMs){
        long checkIn=checkInMs==null?ENTERPRISE_CHECK_IN_MS:checkInMs;
        int jitter=jitterPct==null?ENTERPRISE_JITTER_PCT:jitterPct;
        long offline=offlineAfterMs==null?Math.max(120000,6*checkIn):offlineAfterMs;
        var source=checkInMs==null&&jitterPct==null&&offlineAfterMs==null?ValueSource.DEFAULT:ValueSource.ENVIRONMENT;
        return new ClientCheckInPolicy(checkIn,jitter,offline,source);
    }

    private static long longestIntervalMs(long checkInMs,int jitterPct){return checkInMs+checkInMs*jitterPct/100;}

    /** Check-ins closer together than half the shortest jittered interval are request bursts, not transport jitter. */
    public long burstGuardMs(){return Math.max(1,(checkInMs-checkInMs*jitterPct/100)/2);}

    /** The next check-in interval, spread by the jitter. */
    public long nextIntervalMs(){
        long spread=checkInMs*jitterPct/100;
        long value=spread==0?checkInMs:checkInMs-spread+java.util.concurrent.ThreadLocalRandom.current().nextLong(2*spread+1);
        return Math.max(500,value);
    }

    public boolean offline(long lastSeenUnixMs,long now){return lastSeenUnixMs==0||now-lastSeenUnixMs>offlineAfterMs;}

    /** Effective values for the read-only console view. */
    public ClientCheckIn effective(){return new ClientCheckIn(checkInMs,(long)jitterPct,offlineAfterMs,source);}
}
