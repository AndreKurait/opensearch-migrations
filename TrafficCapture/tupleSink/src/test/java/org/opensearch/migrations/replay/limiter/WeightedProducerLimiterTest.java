package org.opensearch.migrations.replay.limiter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedProducerLimiterTest {

    @Test
    void reserveBlocksWhenExhaustedThenProceedsOnRelease() throws Exception {
        var limiter = WeightedProducerLimiter.ofCount("count", 1);
        var first = limiter.reserve(1).orElseThrow();  // consume the only permit

        var secondReserved = new CompletableFuture<Void>();
        var t = new Thread(() -> {
            limiter.reserve(1).orElseThrow();  // must block until `first` is released
            secondReserved.complete(null);
        }, "second-reserve");
        t.start();

        assertThrows(TimeoutException.class,
            () -> secondReserved.get(500, TimeUnit.MILLISECONDS),
            "second reserve should block while the limiter is exhausted");

        first.release();
        secondReserved.get(2, TimeUnit.SECONDS);
        t.join(2000);
    }

    @Test
    void releaseIsIdempotentAndDoesNotOverCreditCapacity() {
        var gate = WeightedBlockingGate.ofCount("count", 2);
        var limiter = new WeightedProducerLimiter(gate);

        var reservation = limiter.reserve(1).orElseThrow();
        assertEquals(1, gate.availablePermits());

        reservation.release();
        assertEquals(2, gate.availablePermits());
        reservation.release();  // second release must be a no-op
        assertEquals(2, gate.availablePermits(), "double release must not exceed capacity");
    }

    @Test
    void reserveUntilSettledReleasesOnNormalCompletion() throws Exception {
        var gate = WeightedBlockingGate.ofCount("count", 1);
        var limiter = new WeightedProducerLimiter(gate);
        var future = new CompletableFuture<Void>();

        assertTrue(limiter.reserveUntilSettled(1, future));
        assertEquals(0, gate.availablePermits());

        future.complete(null);
        assertEquals(1, gate.availablePermits());
    }

    @Test
    void reserveUntilSettledReleasesOnExceptionalCompletion() throws Exception {
        var gate = WeightedBlockingGate.ofCount("count", 1);
        var limiter = new WeightedProducerLimiter(gate);
        var future = new CompletableFuture<Void>();

        assertTrue(limiter.reserveUntilSettled(1, future));
        assertEquals(0, gate.availablePermits());

        future.completeExceptionally(new RuntimeException("boom"));
        assertEquals(1, gate.availablePermits(), "permits must be returned even on failure");
    }

    @Test
    void oversizedWeightClampsToCapacityAndProceedsSolo() {
        var gate = WeightedBlockingGate.ofBytes("bytes", 2L * 1024L);  // 2 KiB permits
        var limiter = new WeightedProducerLimiter(gate);

        // A request far larger than the whole gate must still succeed (clamped), not block forever.
        var reservation = limiter.reserve(100L * 1024L * 1024L).orElseThrow();
        assertEquals(0, gate.availablePermits());
        reservation.release();
        assertEquals(2, gate.availablePermits());
    }
}
