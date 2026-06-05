package org.opensearch.migrations.replay.limiter;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Producer-side backpressure: a producer blocks until there is capacity for the work it is about to
 * enqueue, then holds a {@link Reservation} for the lifetime of that work and releases it when the
 * work is durably done. Used to bound a resource that accumulates faster than it drains (e.g.
 * accepted-but-not-yet-uploaded tuples, or rotated-but-not-yet-uploaded files) so the producer slows
 * down under pressure instead of failing or growing without bound.
 *
 * <p>Weight is whatever the gate measures — a count of items, or a number of bytes. An oversized
 * request is clamped to the limiter's capacity so a single large item can still proceed solo rather
 * than block forever.</p>
 */
public interface ProducerLimiter {

    /** A held reservation. {@link #release()} returns the reserved weight to the limiter and is idempotent. */
    interface Reservation {
        void release();
    }

    /**
     * Block until {@code weight} fits within the limiter (clamped to capacity), then return a held
     * {@link Reservation}. Returns {@link Optional#empty()} iff the thread was interrupted while
     * waiting (interrupt flag re-set); callers typically treat that as shutdown.
     */
    Optional<Reservation> reserve(long weight);

    /**
     * Convenience for the common single-limiter case: {@link #reserve} and, on success, auto-release
     * exactly once when {@code future} settles (normally or exceptionally).
     *
     * <p>Callers that must hold reservations across <i>multiple</i> limiters should use
     * {@link #reserve} directly and register a single combined release, so a reservation already
     * taken is freed if a later reservation is interrupted.</p>
     *
     * @return {@code true} if reserved (release wired to {@code future}); {@code false} iff interrupted.
     */
    default boolean reserveUntilSettled(long weight, CompletableFuture<?> future) {
        var reservation = reserve(weight);
        if (reservation.isEmpty()) {
            return false;
        }
        future.whenComplete((v, t) -> reservation.get().release());
        return true;
    }

    /** Currently-available capacity in the underlying gate's permit unit (for logging; racy). */
    int availablePermits();

    String name();
}
