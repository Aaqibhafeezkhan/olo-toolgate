// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.List;

/** Tenant-transactional run-response events (docs/contracts/platform-requirements.md). The stream row allocates cursors under a row lock and keeps
 *  the counters that tell a reader what was refused, dropped or purged; events are immutable once appended. */
public interface RunEventStore {
    record Stream(String invocationId,long lastCursor,int events,long bytes,long droppedFrames,long refusedFrames,long retainUntil,Long purgedAt) {}
    /** fencingToken, attempt and sequence are set for EXECUTOR events only. */
    record Event(long cursor,String source,String kind,Integer attempt,String fencingToken,Integer sequence,long recordedAt,String document) {}
    Stream stream(String invocationId);
    /** Appends documents in order, allocating consecutive cursors; returns the stream after the append. */
    Stream append(String invocationId,List<Event> events,long bytes,long droppedFrames,long refusedFrames,long retainUntil);
    /** The executor sequences already retained for one attempt's fencing token, ascending. */
    List<Integer> sequences(String invocationId,String fencingToken);
    /** The attempt recorded with a fencing token, or null before its first frame. */
    Integer attempt(String invocationId,String fencingToken);
    List<Event> page(String invocationId,long after,int limit);
    /** Deletes events of streams whose retention ended and marks those streams purged; returns how many streams. */
    int purge(long now,int limit);
}
