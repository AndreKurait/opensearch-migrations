package org.opensearch.migrations.replay.sink;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3TupleSinkTest {

    private Map<String, Object> makeTuple(String id) {
        var map = new LinkedHashMap<String, Object>();
        map.put("connectionId", id);
        map.put("numRequests", 1);
        return map;
    }

    private Map<String, Object> makeTupleWithPayload(String id, String payload) {
        var map = makeTuple(id);
        map.put("payload", payload);
        return map;
    }

    @Test
    void flushWithoutPendingTuplesDoesNotUpload() {
        var s3Client = mock(S3AsyncClient.class);

        try (var sink = makeSink(s3Client, 1)) {
            sink.flush();
        }

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class));
    }

    @Test
    void serializationFailureCompletesOnlyThatTupleFuture() throws Exception {
        var s3Client = mock(S3AsyncClient.class);
        var recursiveTuple = new LinkedHashMap<String, Object>();
        recursiveTuple.put("self", recursiveTuple);

        try (var sink = makeSink(s3Client, 1)) {
            var future = new CompletableFuture<Void>();
            sink.accept(recursiveTuple, future);

            // Serialization runs on the worker thread, so completion is async —
            // await it rather than asserting synchronously right after accept().
            var ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof IOException);
        }

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class));
    }

    @Test
    void serializationRunsOffTheCallingThread() throws Exception {
        // accept() must not serialize on the calling thread: a tuple's serialization runs on the
        // worker, so the calling (event-loop) thread should be released immediately.
        var s3Client = mock(S3AsyncClient.class);
        var callingThread = Thread.currentThread().getName();
        var serializeThread = new java.util.concurrent.CompletableFuture<String>();

        // A scalar VALUE whose Jackson serialization (getValue) records the thread. Using a value
        // (not a Map override) ensures we capture true JSON serialization, not the cheap structural
        // size estimate accept() runs on the calling thread — the estimator never invokes value
        // getters, so only the worker-thread serialize trips this.
        var probeValue = new Object() {
            @com.fasterxml.jackson.annotation.JsonValue
            public String getValue() {
                serializeThread.complete(Thread.currentThread().getName());
                return "probe";
            }
        };
        var probeTuple = new LinkedHashMap<String, Object>();
        probeTuple.put("connectionId", "probe.0");
        probeTuple.put("probe", probeValue);

        try (var sink = makeSink(s3Client, 100)) {
            sink.accept(probeTuple, new CompletableFuture<>());
            var threadThatSerialized = serializeThread.get(2, TimeUnit.SECONDS);
            assertFalse(threadThatSerialized.equals(callingThread),
                "serialization should run on the sink worker, not the calling thread");
            assertTrue(threadThatSerialized.startsWith("s3-tuple-sink-worker-"),
                "expected the sink worker thread, got: " + threadThatSerialized);
        }
    }

    @Test
    void acceptBlocksWhenInFlightCapExhaustedThenProceedsAsUploadsDrain() throws Exception {
        var s3Client = mock(S3AsyncClient.class);
        var upload = new CompletableFuture<PutObjectResponse>();
        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
            .thenReturn(upload);

        // Cap of 1 in-flight tuple, rotate every tuple → first accept consumes the only permit and
        // starts an upload that we hold open; the second accept must block until that upload drains.
        try (var sink = makeSink(s3Client, /*rotateAfterTuples*/ 1, Duration.ofMinutes(10),
                                 /*maxInFlight*/ 1)) {
            var f1 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), f1);

            // Second accept on a separate thread; it should BLOCK (permit held by f1's pending upload).
            var f2 = new CompletableFuture<Void>();
            var secondAcceptReturned = new CompletableFuture<Void>();
            var t = new Thread(() -> {
                sink.accept(makeTuple("conn2.0"), f2);
                secondAcceptReturned.complete(null);
            }, "second-accept");
            t.start();

            // It must not return while the permit is held.
            assertThrows(TimeoutException.class,
                () -> secondAcceptReturned.get(500, TimeUnit.MILLISECONDS),
                "second accept should block while the in-flight cap is exhausted");

            // Drain the first upload → releases the permit → second accept proceeds.
            upload.complete(PutObjectResponse.builder().build());
            f1.get(2, TimeUnit.SECONDS);
            secondAcceptReturned.get(2, TimeUnit.SECONDS);
            t.join(2000);
        }
    }

    @Test
    void acceptBlocksWhenInFlightByteCapExhaustedThenProceedsAsUploadsDrain() throws Exception {
        var s3Client = mock(S3AsyncClient.class);
        var upload = new CompletableFuture<PutObjectResponse>();
        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
            .thenReturn(upload);

        // Generous tuple-count cap so the BYTE gate is what blocks. Byte cap = 2 KiB; each tuple's
        // payload is ~1.5 KiB, so the first tuple fits but the second can't until the first drains.
        var bigPayload = "x".repeat(1500);
        try (var sink = makeSink(s3Client, /*rotateAfterTuples*/ 1, Duration.ofMinutes(10),
                                 /*maxInFlight*/ 1000, /*maxInFlightTupleBytes*/ 2048L,
                                 S3TupleSink.DEFAULT_MAX_OUTSTANDING_UPLOAD_BYTES,
                                 S3TupleSink.DEFAULT_MAX_OUTSTANDING_UPLOAD_FILES)) {
            var f1 = new CompletableFuture<Void>();
            sink.accept(makeTupleWithPayload("conn1.0", bigPayload), f1);

            // Second accept on a separate thread; it should BLOCK on the byte gate (first tuple's
            // ~1.5 KiB leaves <1.5 KiB free under the 2 KiB cap), held until f1's upload drains.
            var f2 = new CompletableFuture<Void>();
            var secondAcceptReturned = new CompletableFuture<Void>();
            var t = new Thread(() -> {
                sink.accept(makeTupleWithPayload("conn2.0", bigPayload), f2);
                secondAcceptReturned.complete(null);
            }, "second-accept-bytes");
            t.start();

            assertThrows(TimeoutException.class,
                () -> secondAcceptReturned.get(500, TimeUnit.MILLISECONDS),
                "second accept should block while the in-flight BYTE cap is exhausted");

            // Drain the first upload → releases the byte permits → second accept proceeds.
            upload.complete(PutObjectResponse.builder().build());
            f1.get(2, TimeUnit.SECONDS);
            secondAcceptReturned.get(2, TimeUnit.SECONDS);
            t.join(2000);
        }
    }

    @Test
    void estimateTupleBytesIsCoarseStructuralSum() {
        // CharSequence values dominate (counted by length); a nested map + list sums children.
        var tuple = new LinkedHashMap<String, Object>();
        tuple.put("connectionId", "conn.0");
        tuple.put("payload", "x".repeat(1000));
        var nested = new LinkedHashMap<String, Object>();
        nested.put("k", "value");
        tuple.put("nested", nested);
        tuple.put("list", java.util.List.of("a", "bb"));

        long estimate = S3TupleSink.estimateTupleBytes(tuple);
        // Must be dominated by the 1000-char payload and strictly exceed the sum of string lengths.
        long stringChars = "conn.0".length() + 1000 + "value".length() + "a".length() + "bb".length();
        assertTrue(estimate > stringChars, "estimate should exceed bare string length sum: " + estimate);
        assertTrue(estimate < stringChars + 200, "estimate should stay coarse/cheap, not balloon: " + estimate);
    }

    @Test
    void selfScheduledFlushUploadsPendingTupleOnceMaxAgeReached() throws Exception {
        var s3Client = mock(S3AsyncClient.class);
        var upload = new CompletableFuture<PutObjectResponse>();
        var putCallCount = new AtomicInteger();

        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
            .thenAnswer(invocation -> {
                putCallCount.incrementAndGet();
                return upload;
            });

        // Short max-age: the sink's own scheduled worker should rotate the trailing batch
        // WITHOUT any further accept() calls or external flush — this is the stall fix.
        try (var sink = makeSink(s3Client, 100, Duration.ofMillis(50))) {
            var future = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), future);

            waitForPutCalls(putCallCount, 1);
            assertFalse(future.isDone(), "Tuple future should still wait for the upload result");

            upload.complete(PutObjectResponse.builder().build());
            future.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void doesNotUploadBeforeMaxAgeWhileQuiet() throws Exception {
        var s3Client = mock(S3AsyncClient.class);

        // Long max-age: a quiet sink must NOT upload a tiny object on its scheduled ticks.
        try (var sink = makeSink(s3Client, 100, Duration.ofMinutes(10))) {
            var future = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), future);

            // Give the scheduled worker several opportunities to (incorrectly) flush.
            Thread.sleep(200);

            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class));
            assertFalse(future.isDone(), "Tuple future stays pending until size/count/age rotation");
        }
    }

    @Test
    void closeUploadsPendingTuple() throws Exception {
        var s3Client = mock(S3AsyncClient.class);
        var upload = new CompletableFuture<PutObjectResponse>();
        var putCallCount = new AtomicInteger();

        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
            .thenAnswer(invocation -> {
                putCallCount.incrementAndGet();
                return upload;
            });

        var sink = makeSink(s3Client, 100);
        try {
            var future = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), future);

            sink.close();
            assertEquals(1, putCallCount.get(), "Close should upload pending tuples before releasing them");
            assertFalse(future.isDone(), "Tuple future should still wait for the upload result");

            upload.complete(PutObjectResponse.builder().build());
            future.get(1, TimeUnit.SECONDS);
        } finally {
            sink.close();
        }
    }

    @Test
    void retriesUploadWithSameS3KeyUntilSuccess() throws Exception {
        var s3Client = mock(S3AsyncClient.class);
        var putRequests = new CopyOnWriteArrayList<PutObjectRequest>();
        var putCallCount = new AtomicInteger();
        var successfulRetry = new CompletableFuture<PutObjectResponse>();

        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
            .thenAnswer(invocation -> {
                putRequests.add(invocation.getArgument(0));
                var call = putCallCount.incrementAndGet();
                if (call < 3) {
                    return CompletableFuture.failedFuture(new IOException("S3 unavailable"));
                }
                return successfulRetry;
            });

        try (var sink = makeSink(s3Client, 1)) {

            var future = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), future);

            waitForPutCalls(putCallCount, 3);
            assertFalse(future.isDone(), "Tuple future should remain pending until an upload succeeds");
            assertEquals(putRequests.get(0).key(), putRequests.get(1).key());
            assertEquals(putRequests.get(1).key(), putRequests.get(2).key());

            successfulRetry.complete(PutObjectResponse.builder().build());
            future.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void diskBacklogGateBlocksWorkerWhenOutstandingFilesExceedCapThenDrains() throws Exception {
        var s3Client = mock(S3AsyncClient.class);
        var firstUpload = new CompletableFuture<PutObjectResponse>();
        var secondUpload = new CompletableFuture<PutObjectResponse>();
        var putCallCount = new AtomicInteger();
        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
            .thenAnswer(invocation -> putCallCount.incrementAndGet() == 1 ? firstUpload : secondUpload);

        // rotate every tuple; intake cap is generous so the DISK gate (max 1 outstanding file) is
        // what blocks; large byte cap so only the file-count gate is exercised here.
        try (var sink = makeSink(s3Client, /*rotateAfterTuples*/ 1, Duration.ofMinutes(10),
                                 /*maxInFlight*/ 1000,
                                 /*maxOutstandingUploadBytes*/ 1024L * 1024L * 1024L,
                                 /*maxOutstandingUploadFiles*/ 1)) {
            var f1 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), f1);
            // First file rotates and starts uploading (held open) — consumes the only file permit.
            waitForPutCalls(putCallCount, 1);

            // Second tuple: it gets buffered+rotated on the worker, which must then BLOCK acquiring
            // the disk file-permit. So no second putObject happens while the first upload is open.
            var f2 = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn2.0"), f2);
            Thread.sleep(300);
            assertEquals(1, putCallCount.get(),
                "worker should be parked on the disk-backlog gate; no 2nd upload until the 1st drains");
            assertFalse(f2.isDone());

            // Complete the first upload → releases the file permit → worker proceeds to upload #2.
            firstUpload.complete(PutObjectResponse.builder().build());
            f1.get(2, TimeUnit.SECONDS);
            waitForPutCalls(putCallCount, 2);

            secondUpload.complete(PutObjectResponse.builder().build());
            f2.get(2, TimeUnit.SECONDS);
        }
    }

    private void waitForPutCalls(AtomicInteger putCallCount, int expectedCalls) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (putCallCount.get() < expectedCalls && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expectedCalls, putCallCount.get(), "Unexpected S3 putObject attempt count");
    }

    private S3TupleSink makeSink(S3AsyncClient s3Client, int rotateAfterTuples) {
        return makeSink(s3Client, rotateAfterTuples, Duration.ofMinutes(10));
    }

    private S3TupleSink makeSink(S3AsyncClient s3Client, int rotateAfterTuples, Duration rotateAfterAge) {
        return makeSink(s3Client, rotateAfterTuples, rotateAfterAge, S3TupleSink.DEFAULT_MAX_IN_FLIGHT_TUPLES);
    }

    private S3TupleSink makeSink(S3AsyncClient s3Client, int rotateAfterTuples, Duration rotateAfterAge,
                                 int maxInFlightTuples) {
        return makeSink(s3Client, rotateAfterTuples, rotateAfterAge, maxInFlightTuples,
            S3TupleSink.DEFAULT_MAX_IN_FLIGHT_TUPLE_BYTES,
            S3TupleSink.DEFAULT_MAX_OUTSTANDING_UPLOAD_BYTES, S3TupleSink.DEFAULT_MAX_OUTSTANDING_UPLOAD_FILES);
    }

    private S3TupleSink makeSink(S3AsyncClient s3Client, int rotateAfterTuples, Duration rotateAfterAge,
                                 int maxInFlightTuples, long maxOutstandingUploadBytes,
                                 int maxOutstandingUploadFiles) {
        return makeSink(s3Client, rotateAfterTuples, rotateAfterAge, maxInFlightTuples,
            S3TupleSink.DEFAULT_MAX_IN_FLIGHT_TUPLE_BYTES, maxOutstandingUploadBytes, maxOutstandingUploadFiles);
    }

    private S3TupleSink makeSink(S3AsyncClient s3Client, int rotateAfterTuples, Duration rotateAfterAge,
                                 int maxInFlightTuples, long maxInFlightTupleBytes,
                                 long maxOutstandingUploadBytes, int maxOutstandingUploadFiles) {
        return new S3TupleSink(
            s3Client,
            "bucket",
            "tuples/",
            "replayer-1",
            0,
            1024 * 1024,
            rotateAfterAge,
            rotateAfterTuples,
            Duration.ofMillis(10),
            maxInFlightTuples,
            maxInFlightTupleBytes,
            maxOutstandingUploadBytes,
            maxOutstandingUploadFiles
        );
    }
}
