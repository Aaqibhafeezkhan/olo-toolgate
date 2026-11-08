// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.trigger;

import java.time.Instant;
import java.util.Objects;

/**
 * One event a trigger reports. The executor wraps its data in a CloudEvents envelope; the author never sets
 * {@code source}, {@code type} or the extension attributes. Immutable: each setter returns a copy.
 *
 * @param <E> the declared event record
 */
public final class TriggerEvent<E> {
    /** Bounds of the trigger frame contract (trigger.schema.json). */
    public static final int MAX_ID_LENGTH = 256, MAX_SUBJECT_LENGTH = 256;

    private final E data;
    private final String id;
    private final String subject;
    private final Instant time;

    private TriggerEvent(E data, String id, String subject, Instant time) {
        this.data = data;
        this.id = id;
        this.subject = subject;
        this.time = time;
    }

    public static <E> TriggerEvent<E> of(E data) {
        return new TriggerEvent<>(Objects.requireNonNull(data, "data"), null, null, null);
    }

    /**
     * The stable source-level identity: the same item gives the same id across restarts and checks, and it holds
     * every component the source needs for uniqueness. Without one the executor assigns a UUIDv7, so a re-reported
     * item is not deduplicated.
     */
    public TriggerEvent<E> id(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) throw new IllegalArgumentException("an event id has 1-" + MAX_ID_LENGTH + " characters");
        return new TriggerEvent<>(data, id, subject, time);
    }

    public TriggerEvent<E> subject(String subject) {
        if (subject == null || subject.isEmpty() || subject.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("an event subject has 1-" + MAX_SUBJECT_LENGTH + " characters");
        }
        return new TriggerEvent<>(data, id, subject, time);
    }

    /** The source's time for the event; the executor's clock when unset. Ordering never uses it. */
    public TriggerEvent<E> time(Instant time) {
        return new TriggerEvent<>(data, id, subject, Objects.requireNonNull(time, "time"));
    }

    public E data() {
        return data;
    }

    public String id() {
        return id;
    }

    public String subject() {
        return subject;
    }

    public Instant time() {
        return time;
    }

    @Override
    public String toString() {
        return "TriggerEvent[id=" + id + ", subject=" + subject + ", time=" + time + ", data=" + data + "]";
    }
}
