package org.opensearch.migrations.replay.limiter;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link ProducerLimiter} backed by a single {@link WeightedBlockingGate}. Each {@link #reserve}
 * computes the clamped permit count once and hands back a {@link Reservation} that releases exactly
 * that many permits, at most once (guarded by an {@link AtomicBoolean}).
 */
public final class WeightedProducerLimiter implements ProducerLimiter {

    private final WeightedBlockingGate gate;

    public WeightedProducerLimiter(WeightedBlockingGate gate) {
        this.gate = gate;
    }

    /** A count-bounded producer limiter (1 permit per unit of weight). */
    public static WeightedProducerLimiter ofCount(String name, int capacity) {
        return new WeightedProducerLimiter(WeightedBlockingGate.ofCount(name, capacity));
    }

    /** A byte-bounded producer limiter (KiB-denominated permits). */
    public static WeightedProducerLimiter ofBytes(String name, long capacityBytes) {
        return new WeightedProducerLimiter(WeightedBlockingGate.ofBytes(name, capacityBytes));
    }

    @Override
    public Optional<Reservation> reserve(long weight) {
        int permits = gate.permitsFor(weight);
        if (!gate.acquire(permits)) {
            return Optional.empty();  // interrupted while waiting
        }
        var released = new AtomicBoolean(false);
        return Optional.of(() -> {
            if (released.compareAndSet(false, true)) {
                gate.release(permits);
            }
        });
    }

    @Override
    public int availablePermits() {
        return gate.availablePermits();
    }

    @Override
    public String name() {
        return gate.name();
    }
}
