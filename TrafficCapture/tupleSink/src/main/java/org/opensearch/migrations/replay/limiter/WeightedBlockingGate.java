package org.opensearch.migrations.replay.limiter;

import java.util.concurrent.Semaphore;

/**
 * A weighted, blocking capacity gate over a single {@link Semaphore} — one gate bounds one
 * dimension (e.g. a count of items, or a number of bytes).
 *
 * <p>The gate's job is the small bundle of logic that backpressure callers otherwise hand-roll
 * around a raw semaphore:</p>
 * <ul>
 *   <li><b>Weight → permits.</b> A caller thinks in raw weights (1 item, N bytes); the gate
 *       converts to permits. Byte gates are denominated in KiB ({@link #ofBytes}) so a multi-GiB
 *       capacity still fits the int permit count a {@link Semaphore} uses.</li>
 *   <li><b>Clamp to capacity.</b> {@link #permitsFor} clamps an oversized request down to the full
 *       capacity, so a single item larger than the whole gate can still proceed solo rather than
 *       block forever against a permit count it could never satisfy.</li>
 *   <li><b>Balanced release by construction.</b> A caller computes {@code int p = permitsFor(w)}
 *       once and passes the same {@code p} to {@link #acquire(int)} and {@link #release(int)}, so
 *       the amount released always matches the amount acquired.</li>
 * </ul>
 *
 * <p>The gate is intentionally unaware of {@link java.util.concurrent.CompletableFuture} or any
 * release lifecycle — callers (or a {@link ProducerLimiter}) decide when to release. It is safe for
 * concurrent use; the underlying semaphore is non-fair.</p>
 */
public final class WeightedBlockingGate {

    /** KiB granularity for byte-denominated gates, so a large byte capacity stays within int permits. */
    private static final long BYTE_PERMIT_UNIT = 1024L;

    private final String name;
    private final int capacityPermits;
    private final long unitBytes;
    private final Semaphore semaphore;

    private WeightedBlockingGate(String name, int capacityPermits, long unitBytes) {
        this.name = name;
        this.capacityPermits = capacityPermits;
        this.unitBytes = unitBytes;
        this.semaphore = new Semaphore(capacityPermits);
    }

    /** A gate whose weight is a raw count (1 permit per unit of weight). */
    public static WeightedBlockingGate ofCount(String name, int capacity) {
        return new WeightedBlockingGate(name, Math.max(0, capacity), 1L);
    }

    /** A gate whose weight is a byte count; capacity and requests are denominated in KiB permits. */
    public static WeightedBlockingGate ofBytes(String name, long capacityBytes) {
        return new WeightedBlockingGate(name, toPermits(capacityBytes, BYTE_PERMIT_UNIT), BYTE_PERMIT_UNIT);
    }

    /**
     * Convert a raw weight (count or bytes) into this gate's permit unit (ceil), clamped to
     * {@code [0, capacity]}. Clamping to capacity is what lets an oversized item proceed solo.
     */
    public int permitsFor(long rawWeight) {
        return Math.min(capacityPermits, toPermits(rawWeight, unitBytes));
    }

    /**
     * Block until {@code permits} are available and acquire them. {@code permits} is expected to be
     * an already-clamped value from {@link #permitsFor}. A non-positive count is a no-op.
     *
     * @return {@code true} once acquired; {@code false} iff the thread was interrupted while waiting
     *         (the interrupt flag is re-set). Never returns {@code false} merely because the gate is
     *         full — that just waits.
     */
    public boolean acquire(int permits) {
        if (permits <= 0) {
            return true;
        }
        try {
            semaphore.acquire(permits);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Release {@code permits} previously acquired from this gate. A non-positive count is a no-op. */
    public void release(int permits) {
        if (permits > 0) {
            semaphore.release(permits);
        }
    }

    /** Currently-available permits (for logging/heartbeats; inherently racy). */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    public int capacityPermits() {
        return capacityPermits;
    }

    public String name() {
        return name;
    }

    private static int toPermits(long rawWeight, long unit) {
        if (rawWeight <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (rawWeight + unit - 1) / unit);
    }
}
