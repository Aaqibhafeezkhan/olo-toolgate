// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.trigger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one {@code check()} found: its events, in the order they are emitted, and optionally the checkpoint to
 * commit after them. The runtime commits the checkpoint only once every one of these events is durable, so a check
 * that fails or is rejected is repeated from the previous checkpoint (plan 6.6). Immutable.
 *
 * @param <E> the declared event record
 */
public final class PollResult<E> {
    /** The most events one check may return. */
    public static final int MAX_EVENTS = 1024;

    private final List<TriggerEvent<E>> events;
    private final Checkpoint checkpoint;

    private PollResult(List<TriggerEvent<E>> events, Checkpoint checkpoint) {
        this.events = events;
        this.checkpoint = checkpoint;
    }

    public static <E> PollResult<E> of(List<TriggerEvent<E>> events) {
        List<TriggerEvent<E>> copy = List.copyOf(Objects.requireNonNull(events, "events"));
        if (copy.size() > MAX_EVENTS) throw new IllegalArgumentException("one check returns at most " + MAX_EVENTS + " events");
        return new PollResult<>(copy, null);
    }

    /** Nothing new, and the checkpoint stays where it is. */
    public static <E> PollResult<E> none() {
        return new PollResult<>(List.of(), null);
    }

    /** A copy that commits {@code checkpoint} after its events. */
    public PollResult<E> checkpoint(Checkpoint checkpoint) {
        return new PollResult<>(events, Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    public List<TriggerEvent<E>> events() {
        return events;
    }

    public Optional<Checkpoint> checkpoint() {
        return Optional.ofNullable(checkpoint);
    }
}
