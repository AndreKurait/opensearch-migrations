package org.opensearch.migrations.replay.limiter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedBlockingGateTest {

    @Test
    void countGateConvertsWeightOneToOnePermit() {
        var gate = WeightedBlockingGate.ofCount("count", 10);
        assertEquals(10, gate.capacityPermits());
        assertEquals(3, gate.permitsFor(3));
        assertEquals(0, gate.permitsFor(0));
    }

    @Test
    void byteGateIsKiBDenominatedAndRoundsUp() {
        // 4 KiB capacity -> 4 permits; sub-KiB and partial-KiB weights round up.
        var gate = WeightedBlockingGate.ofBytes("bytes", 4L * 1024L);
        assertEquals(4, gate.capacityPermits());
        assertEquals(1, gate.permitsFor(1));      // 1 byte -> 1 KiB permit
        assertEquals(1, gate.permitsFor(1024));   // exactly 1 KiB -> 1 permit
        assertEquals(2, gate.permitsFor(1025));   // 1 KiB + 1 byte -> 2 permits (ceil)
    }

    @Test
    void permitsForClampsOversizedRequestToCapacitySoItCanProceedSolo() {
        var gate = WeightedBlockingGate.ofBytes("bytes", 2L * 1024L);  // 2 permits
        assertEquals(2, gate.permitsFor(100L * 1024L * 1024L));        // 100 MiB clamps to capacity
    }

    @Test
    void acquireAndReleaseTrackAvailablePermits() {
        var gate = WeightedBlockingGate.ofCount("count", 5);
        assertEquals(5, gate.availablePermits());

        assertTrue(gate.acquire(3));
        assertEquals(2, gate.availablePermits());

        gate.release(3);
        assertEquals(5, gate.availablePermits());
    }

    @Test
    void acquireAndReleaseOfNonPositiveAreNoOps() {
        var gate = WeightedBlockingGate.ofCount("count", 5);
        assertTrue(gate.acquire(0));
        assertTrue(gate.acquire(-1));
        gate.release(0);
        gate.release(-1);
        assertEquals(5, gate.availablePermits());
    }

    @Test
    void acquireReturnsFalseAndSetsInterruptFlagWhenInterruptedWhileWaiting() throws Exception {
        var gate = WeightedBlockingGate.ofCount("count", 1);
        assertTrue(gate.acquire(1));  // exhaust the single permit

        var acquired = new java.util.concurrent.atomic.AtomicBoolean(true);
        var interruptFlagObserved = new java.util.concurrent.atomic.AtomicBoolean(false);
        var t = new Thread(() -> {
            boolean ok = gate.acquire(1);  // must block (no permits), then be interrupted
            acquired.set(ok);
            interruptFlagObserved.set(Thread.currentThread().isInterrupted());
        }, "gate-waiter");
        t.start();
        // Give it a moment to park on the semaphore, then interrupt.
        Thread.sleep(100);
        t.interrupt();
        t.join(2000);

        assertFalse(acquired.get(), "acquire should return false when interrupted while waiting");
        assertTrue(interruptFlagObserved.get(), "interrupt flag should be re-set after a false return");
    }
}
