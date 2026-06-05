package org.opensearch.migrations.replay.sink;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import org.opensearch.migrations.replay.limiter.ProducerLimiter;
import org.opensearch.migrations.replay.limiter.WeightedProducerLimiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Writes tuples as gzip-compressed JSON lines to S3.
 *
 * <p>Each "file" is first written to a local temp file and then uploaded with a
 * single PutObject request when rotation is reached. This avoids buffering the full
 * compressed object in memory and keeps the sink compatible with the standard
 * {@link S3AsyncClient}, whose blocking stream request body can deadlock when opened
 * from a single-threaded event loop.</p>
 *
 * <p>S3 key format: {@code {prefix}{replayerId}/{yyyy/MM/dd/HH}/tuples-{sinkIndex}-{timestamp}-{seq}.log.gz}</p>
 *
 * <p>Each instance is single-threaded (one per Netty event loop). The {@code sinkIndex}
 * is embedded in keys to avoid collisions between concurrent writers.</p>
 *
 * <p><b>Threading.</b> {@code accept()} does no work on the calling (Netty event-loop) thread
 * beyond reserving an in-flight permit and enqueueing — JSON serialization, gzip, and disk I/O
 * all run on the sink's single worker thread. This keeps request replay off the critical path.</p>
 *
 * <p><b>Backpressure (block, don't fail).</b> S3 upload failures retry indefinitely so replay
 * progress stays tied to durable output. Failing a tuple future is NOT an option for transient
 * pressure: a failed tuple write is fatal to the replayer and it shuts down WITHOUT committing the
 * held offsets (TrafficReplayerCore.failReplayForTupleWrite), so on restart it resumes at the same
 * offset, re-reads the same tuples, and — if S3 is still down — crash-loops forever, never
 * advancing. So instead we apply real backpressure via TWO gates, each guarding the resource that
 * accumulates at its stage; both BLOCK (never fail) so the replayer rides out an S3 outage and
 * resumes, with throughput dropping toward zero during the outage by design:</p>
 * <ul>
 *   <li><b>Intake gate (producer-side, count + coarse bytes).</b> {@code accept()} blocks until both
 *       a tuple permit (cap {@code maxInFlightTuples}) and enough byte permits for the tuple's
 *       estimated size (cap {@code maxInFlightTupleBytes}) are free, bounding the heap held by
 *       accepted-but-not-yet-durably-uploaded tuples (queued tupleMaps + pending futures) by both
 *       count and size — whichever fills first throttles. The byte figure is a coarse structural
 *       estimate (sum of CharSequence lengths, computed on the calling thread without serializing),
 *       which is why it's paired with the count cap; the byte-accurate bound is the disk gate below.
 *       Both permit sets release when each tuple future settles. Blocks the replay response-completion
 *       thread — independent of the worker/SDK threads that drain it, so no deadlock.</li>
 *   <li><b>Disk-backlog gate (worker-side, bytes + file count).</b> Rotated-but-not-yet-uploaded
 *       temp files are the genuinely unbounded resource under an outage (each rotation adds a file
 *       to /tmp; none delete until upload succeeds). Before handing a finished file to the upload
 *       retry loop, the worker thread blocks until {@code maxOutstandingUploadBytes} AND
 *       {@code maxOutstandingUploadFiles} permits are free, then releases them on upload success.
 *       Because this blocks the single worker thread, new writes and the age flush also pause while
 *       the disk backlog is full — intended backpressure. Drains via the SDK's upload threads.</li>
 * </ul>
 */
@Slf4j
public class S3TupleSink implements TupleSink {
    static final Duration DEFAULT_UPLOAD_RETRY_DELAY = Duration.ofSeconds(10);

    /**
     * Default intake-gate cap on the number of accepted-but-not-yet-durably-uploaded tuples (coarse
     * count bound on heap). When reached, accept() blocks (waits for an upload to drain) rather than
     * failing.
     */
    static final int DEFAULT_MAX_IN_FLIGHT_TUPLES = 100_000;

    /**
     * Default intake-gate cap on the estimated bytes of accepted-but-not-yet-durably-uploaded tuples
     * (256 MiB). Guards against a small number of very large tuples (big request/response payloads)
     * blowing the heap before the count cap above would trip. Coarse by design — see
     * {@link #estimateTupleBytes}.
     */
    static final long DEFAULT_MAX_IN_FLIGHT_TUPLE_BYTES = 256L * 1024L * 1024L;

    /** Default disk-backlog cap on bytes of rotated-but-not-yet-uploaded temp files (1 GiB). */
    static final long DEFAULT_MAX_OUTSTANDING_UPLOAD_BYTES = 1024L * 1024L * 1024L;

    /** Default disk-backlog cap on the number of rotated-but-not-yet-uploaded temp files. */
    static final int DEFAULT_MAX_OUTSTANDING_UPLOAD_FILES = 64;

    /**
     * Upper bound on nodes walked by {@link #estimateTupleBytes}. Caps the calling-thread cost of
     * the estimate and makes it immune to deeply-nested / self-referential maps. A normal tuple has
     * far fewer nodes than this; the bulk of its size is in a handful of long payload strings, each
     * counted in O(1), so the budget only bites on pathological structures.
     */
    static final int ESTIMATE_NODE_BUDGET = 10_000;

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SHARD_FORMAT =
        DateTimeFormatter.ofPattern("yyyy/MM/dd/HH").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private final S3AsyncClient s3Client;
    private final String bucket;
    private final String prefix;
    private final String replayerId;
    private final int sinkIndex;
    private final long rotateAfterBytes;
    private final Duration rotateAfterAge;
    private final int rotateAfterTuples;
    private final Duration uploadRetryDelay;
    // Single work thread that owns ALL buffer state (gzipOut / pendingFutures / currentFile /
    // counters) AND all CPU work (JSON serialization + gzip). accept()/flush()/close() marshal
    // onto it, and it self-schedules the age-based flush. Because every buffer mutation runs on
    // this one thread, no lock is needed — this is the sink's own "event loop". (The async S3
    // upload callback runs on an SDK thread but only touches atomics, never buffer state.) This
    // keeps the Netty event loop free of all per-tuple serialize/gzip/disk work.
    private final ScheduledExecutorService executor;
    // Separate scheduler for upload retries + the upload-success release path. MUST be distinct from
    // `executor`: the worker thread can block in rotate() on the disk-backlog gate, and the permits
    // it waits on are only released after an upload succeeds. If retries were rescheduled onto the
    // (single, parked) worker they could never run → the gate would never drain → deadlock. Keeping
    // retries on their own thread means the worker can park safely.
    private final ScheduledExecutorService uploadRetryScheduler;
    // Intake gate: bounds accepted-but-not-yet-durably-uploaded tuples by count AND estimated bytes
    // (either fills first). Released when each tuple future settles. See class javadoc.
    private final ProducerLimiter intakeTupleLimiter;
    private final ProducerLimiter intakeByteLimiter;
    // Disk-backlog gate: bounds rotated-but-not-yet-uploaded temp files by count AND total size.
    // Released on upload success — and that release MUST run off the worker thread (see the
    // uploadRetryScheduler note above), because the worker parks on this gate inside rotate().
    private final ProducerLimiter diskFileLimiter;
    private final ProducerLimiter diskByteLimiter;
    private final AtomicInteger activeUploads = new AtomicInteger();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicLong sequenceCounter = new AtomicLong();

    private OutputStream fileOutputStream;
    private GZIPOutputStream gzipOut;
    private String currentKey;
    private Path currentFile;
    private long uncompressedBytes;
    private int tupleCount;
    private Instant fileOpenedAt;
    private final List<CompletableFuture<Void>> pendingFutures = new ArrayList<>();

    public S3TupleSink(
        S3AsyncClient s3Client,
        String bucket,
        String prefix,
        String replayerId,
        int sinkIndex,
        long rotateAfterBytes,
        Duration rotateAfterAge,
        int rotateAfterTuples
    ) {
        this(
            s3Client,
            bucket,
            prefix,
            replayerId,
            sinkIndex,
            rotateAfterBytes,
            rotateAfterAge,
            rotateAfterTuples,
            DEFAULT_UPLOAD_RETRY_DELAY,
            DEFAULT_MAX_IN_FLIGHT_TUPLES
        );
    }

    S3TupleSink(
        S3AsyncClient s3Client,
        String bucket,
        String prefix,
        String replayerId,
        int sinkIndex,
        long rotateAfterBytes,
        Duration rotateAfterAge,
        int rotateAfterTuples,
        Duration uploadRetryDelay
    ) {
        this(s3Client, bucket, prefix, replayerId, sinkIndex, rotateAfterBytes, rotateAfterAge,
            rotateAfterTuples, uploadRetryDelay, DEFAULT_MAX_IN_FLIGHT_TUPLES);
    }

    S3TupleSink(
        S3AsyncClient s3Client,
        String bucket,
        String prefix,
        String replayerId,
        int sinkIndex,
        long rotateAfterBytes,
        Duration rotateAfterAge,
        int rotateAfterTuples,
        Duration uploadRetryDelay,
        int maxInFlightTuples
    ) {
        this(s3Client, bucket, prefix, replayerId, sinkIndex, rotateAfterBytes, rotateAfterAge,
            rotateAfterTuples, uploadRetryDelay, maxInFlightTuples, DEFAULT_MAX_IN_FLIGHT_TUPLE_BYTES,
            DEFAULT_MAX_OUTSTANDING_UPLOAD_BYTES, DEFAULT_MAX_OUTSTANDING_UPLOAD_FILES);
    }

    S3TupleSink(
        S3AsyncClient s3Client,
        String bucket,
        String prefix,
        String replayerId,
        int sinkIndex,
        long rotateAfterBytes,
        Duration rotateAfterAge,
        int rotateAfterTuples,
        Duration uploadRetryDelay,
        int maxInFlightTuples,
        long maxInFlightTupleBytes,
        long maxOutstandingUploadBytes,
        int maxOutstandingUploadFiles
    ) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.prefix = prefix;
        this.replayerId = replayerId;
        this.sinkIndex = sinkIndex;
        this.rotateAfterBytes = rotateAfterBytes;
        this.rotateAfterAge = rotateAfterAge;
        this.rotateAfterTuples = rotateAfterTuples;
        this.uploadRetryDelay = uploadRetryDelay;
        this.intakeTupleLimiter = WeightedProducerLimiter.ofCount("s3-intake-tuples-" + sinkIndex, maxInFlightTuples);
        this.intakeByteLimiter = WeightedProducerLimiter.ofBytes("s3-intake-bytes-" + sinkIndex, maxInFlightTupleBytes);
        this.diskFileLimiter = WeightedProducerLimiter.ofCount("s3-disk-files-" + sinkIndex, maxOutstandingUploadFiles);
        this.diskByteLimiter = WeightedProducerLimiter.ofBytes("s3-disk-bytes-" + sinkIndex, maxOutstandingUploadBytes);
        this.executor = Executors.newSingleThreadScheduledExecutor(makeThreadFactory("worker"));
        this.uploadRetryScheduler = Executors.newSingleThreadScheduledExecutor(makeThreadFactory("upload-retry"));
        openNewStream();
        // Self-scheduled age flush: re-checks file age on its own thread so a sink that stops
        // receiving tuples still rotates its trailing batch (otherwise those tuple futures never
        // complete and the replayer's Kafka offset never advances). Cadence is a fraction of the
        // max age so the worst-case extra latency past max-age is bounded. Min 1s floor avoids a
        // busy schedule for tiny test ages.
        var flushPeriodMs = Math.max(1000L, rotateAfterAge.toMillis() / 2);
        executor.scheduleAtFixedRate(
            this::periodicFlushOnWorker, flushPeriodMs, flushPeriodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void accept(Map<String, Object> tupleMap, CompletableFuture<Void> future) {
        // Backpressure: block the caller until this tuple fits within both the count and byte caps
        // on in-flight (accepted-but-not-yet-durably-uploaded) tuples. When S3 is backed up this
        // throttles tuple production (replay slows) instead of growing memory without bound or
        // failing the tuple (which would crash the replayer into a no-progress restart loop). The
        // reservations cover the whole lifecycle and release exactly once, when `future` settles.
        if (!reserveIntakeUntilSettled(estimateTupleBytes(tupleMap), future)) {
            return;  // interrupted/shutting down — future already completed exceptionally
        }

        // Do NO per-tuple CPU on the calling (event-loop) thread: JSON serialization and gzip both
        // run on the worker thread, so accept() only estimates size, reserves permits, and enqueues.
        runOnWorker(() -> {
            try {
                byte[] json = mapper.writeValueAsBytes(tupleMap);
                gzipOut.write(json);
                gzipOut.write('\n');
                uncompressedBytes += json.length + 1;
                tupleCount++;
                pendingFutures.add(future);
            } catch (IOException e) {
                // Serialization/gzip failure is per-tuple and not helped by retry — fail it.
                future.completeExceptionally(e);
                return;
            }
            if (shouldRotate()) {
                rotate(true);
            }
        }, future);
    }

    /**
     * Reserve both intake dimensions (one tuple + its estimated bytes) and release both exactly once
     * when {@code future} settles. Both reservations are taken BEFORE registering the release so an
     * interrupt mid-way frees the one already held; if interrupted, the future is completed
     * exceptionally and false is returned. Blocking here parks the replay response-completion thread,
     * which is independent of the worker/SDK threads that drain the gates, so it can't deadlock.
     */
    private boolean reserveIntakeUntilSettled(long estimatedBytes, CompletableFuture<Void> future) {
        var tupleReservation = intakeTupleLimiter.reserve(1);
        if (tupleReservation.isEmpty()) {
            future.completeExceptionally(new InterruptedException("interrupted reserving intake tuple permit"));
            return false;
        }
        var byteReservation = intakeByteLimiter.reserve(estimatedBytes);
        if (byteReservation.isEmpty()) {
            tupleReservation.get().release();
            future.completeExceptionally(new InterruptedException("interrupted reserving intake byte permits"));
            return false;
        }
        future.whenComplete((v, t) -> {
            tupleReservation.get().release();
            byteReservation.get().release();
        });
        return true;
    }

    /**
     * Coarse estimate of a tuple's serialized size, used only to bound in-flight heap (the intake
     * byte gate) — NOT for rotation, which uses the exact compressed byte count. Sums CharSequence
     * lengths (each O(1)) plus a small fixed charge per node for keys/structure; the bulk of a tuple
     * is HTTP payload strings, which this captures cheaply. It intentionally ignores
     * encoding/JSON-escaping overhead; the count cap and the byte-accurate disk gate cover the rest.
     *
     * <p>Deliberately iterative with a fixed node budget so it stays cheap on the calling
     * (event-loop) thread and is immune to deeply-nested or self-referential maps — it walks at most
     * {@code ESTIMATE_NODE_BUDGET} nodes, under-counting a pathologically large tuple rather than
     * risking a StackOverflow or a long traversal. (Container values only; it never invokes methods
     * on scalar/bean values, so estimating a tuple does not trigger its serialization.)</p>
     */
    static long estimateTupleBytes(Object root) {
        long sum = 0;
        int visited = 0;
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && visited < ESTIMATE_NODE_BUDGET) {
            Object value = stack.pop();
            visited++;
            if (value == null) {
                sum += 4;  // "null"
            } else if (value instanceof CharSequence) {
                sum += ((CharSequence) value).length();
            } else if (value instanceof Map) {
                for (var entry : ((Map<?, ?>) value).entrySet()) {
                    sum += 4;  // per-entry structural/key charge
                    stack.push(entry.getKey());
                    stack.push(entry.getValue());
                }
            } else if (value instanceof Iterable) {
                for (var element : (Iterable<?>) value) {
                    sum += 1;
                    stack.push(element);
                }
            } else {
                // Numbers, booleans, beans, and other scalars: a small fixed charge, no method call.
                sum += 8;
            }
        }
        return sum;
    }

    @Override
    public void flush() {
        runOnWorker(() -> {
            if (!pendingFutures.isEmpty()) {
                rotate(true);
            }
        }, null);
    }

    /** Age-driven safety flush; runs on the worker thread (self-scheduled in the constructor).
     * Only rotates buffered tuples once the file has reached its max age (size/count rotation is
     * handled inline in accept()). */
    private void periodicFlushOnWorker() {
        if (!pendingFutures.isEmpty() && hasReachedMaxAge()) {
            rotate(true);
        }
    }

    @Override
    public void close() {
        closeRequested.set(true);
        // Drain on the worker thread and block until it's done, so resources are released and
        // pending tuples are flushed (their futures complete via the async upload) before return.
        try {
            runAndAwaitOnWorker(() -> {
                if (gzipOut == null) {
                    return;
                }
                if (!pendingFutures.isEmpty()) {
                    rotate(false);
                } else {
                    closeCurrentStream();
                    deleteFile(currentFile);
                    clearCurrentStream();
                }
            });
        } finally {
            shutdownExecutorIfDone();
        }
    }

    /** Submit buffer work to the single worker thread; on rejection (post-close) fail the
     * associated future if any, so callers never wait forever. */
    private void runOnWorker(Runnable task, CompletableFuture<Void> futureToFailOnReject) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            if (futureToFailOnReject != null) {
                futureToFailOnReject.completeExceptionally(e);
            }
        }
    }

    private void runAndAwaitOnWorker(Runnable task) {
        try {
            executor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RejectedExecutionException e) {
            // already shutting down — nothing to drain
        } catch (java.util.concurrent.ExecutionException e) {
            log.atError().setCause(e.getCause()).setMessage("Error draining S3 tuple sink on close").log();
        }
    }

    private boolean shouldRotate() {
        return uncompressedBytes >= rotateAfterBytes
            || (rotateAfterTuples > 0 && tupleCount >= rotateAfterTuples)
            || hasReachedMaxAge();
    }

    private boolean hasReachedMaxAge() {
        return Duration.between(fileOpenedAt, Instant.now()).compareTo(rotateAfterAge) >= 0;
    }

    private void rotate(boolean openNextStream) {
        var key = currentKey;
        var futures = new ArrayList<>(pendingFutures);
        pendingFutures.clear();

        var file = currentFile;
        if (!closeCurrentStream()) {
            deleteFile(file);
            futures.forEach(f -> f.completeExceptionally(
                new IOException("Failed to finish gzip stream for s3://" + bucket + "/" + key)));
            if (openNextStream) {
                openNewStream();
            } else {
                clearCurrentStream();
            }
            return;
        }

        // Disk-backlog gate: block the worker until the rotated file fits within the outstanding-
        // upload caps (file count + bytes). This is the byte-accurate bound that protects /tmp from
        // a sustained S3 outage; the worker parking here also pauses new writes + the age flush
        // (intended backpressure). Reservations are released on upload success (see
        // uploadFileWithRetries) — on a thread OTHER than this worker, so the parked worker can drain.
        var diskReservations = acquireDiskBacklogReservations(file);

        log.atInfo().setMessage("Completing S3 upload to s3://{}/{}").addArgument(bucket).addArgument(key).log();

        activeUploads.incrementAndGet();
        uploadFileWithRetries(key, file, futures, diskReservations, 1);

        if (openNextStream) {
            openNewStream();
        } else {
            clearCurrentStream();
        }
    }

    /**
     * Block (on the worker thread) until the just-finished file fits within the outstanding-upload
     * file-count and byte caps, then return the held reservations (to release on upload success).
     * Acquire order is file-then-bytes; an oversized file is clamped to the byte cap by the limiter
     * so it proceeds solo rather than deadlock. On interrupt (shutdown) a reservation may be empty;
     * releasing an empty reservation is a no-op, so release stays balanced.
     */
    private DiskBacklogReservations acquireDiskBacklogReservations(Path file) {
        long sizeBytes;
        try {
            sizeBytes = Files.size(file);
        } catch (IOException e) {
            // Can't size it — don't block on the byte gate for this file; still take a file permit.
            sizeBytes = 0;
        }
        // Acquire file-then-bytes (consistent order; releasers never acquire → no deadlock). A null
        // reservation (interrupted, or size unknown for the byte gate) releases as a no-op.
        var fileReservation = diskFileLimiter.reserve(1).orElse(null);
        var byteReservation = sizeBytes > 0 ? diskByteLimiter.reserve(sizeBytes).orElse(null) : null;
        return new DiskBacklogReservations(fileReservation, byteReservation);
    }

    /** Held file + byte reservations for one in-flight upload; released together on success. */
    private static final class DiskBacklogReservations {
        private final ProducerLimiter.Reservation fileReservation;
        private final ProducerLimiter.Reservation byteReservation;

        DiskBacklogReservations(ProducerLimiter.Reservation fileReservation,
                                ProducerLimiter.Reservation byteReservation) {
            this.fileReservation = fileReservation;
            this.byteReservation = byteReservation;
        }

        void release() {
            if (fileReservation != null) {
                fileReservation.release();
            }
            if (byteReservation != null) {
                byteReservation.release();
            }
        }
    }

    /** Finish gzip and close the local temp file before upload. Returns true on success. */
    private boolean closeCurrentStream() {
        try {
            gzipOut.finish();
            fileOutputStream.close();
            return true;
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to close S3 upload stream").log();
            return false;
        }
    }

    private String buildS3Key() {
        // Keep time/sequence-based object names for now. Once Kafka identity is threaded
        // into this sink, prefer keys or metadata that include partition/offset ranges
        // plus a stable run id so downstream consumers can dedupe replay attempts.
        var now = Instant.now();
        var timestamp = TIMESTAMP_FORMAT.format(now);
        var shard = SHARD_FORMAT.format(now);
        var seq = sequenceCounter.getAndIncrement();
        var filename = String.format("tuples-%d-%s-%d.log.gz", sinkIndex, timestamp, seq);
        return prefix + replayerId + "/" + shard + "/" + filename;
    }

    private void openNewStream() {
        currentKey = buildS3Key();
        try {
            currentFile = Files.createTempFile("tuple-sink-" + sinkIndex + "-", ".log.gz");
            fileOutputStream = Files.newOutputStream(currentFile);
            gzipOut = new GZIPOutputStream(fileOutputStream, true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create gzip temp file for S3 upload", e);
        }
        uncompressedBytes = 0;
        tupleCount = 0;
        fileOpenedAt = Instant.now();
    }

    private void clearCurrentStream() {
        gzipOut = null;
        fileOutputStream = null;
        currentFile = null;
        currentKey = null;
    }

    private void uploadFileWithRetries(
        String key,
        Path file,
        List<CompletableFuture<Void>> futures,
        DiskBacklogReservations diskReservations,
        int attempt
    ) {
        uploadFile(key, file).whenComplete((response, error) -> {
            if (error == null) {
                deleteFile(file);
                // Release the disk-backlog gate first: the file is gone and its futures are about
                // to complete, so unblock the worker (which may be parked in rotate()) promptly.
                // This runs on an SDK/retry thread, never the worker, so the parked worker can drain.
                diskReservations.release();
                futures.forEach(f -> f.complete(null));
                activeUploads.decrementAndGet();
                shutdownExecutorIfDone();
                return;
            }

            log.atWarn().setCause(error).setMessage(
                    "Failed to upload tuple file to s3://{}/{} on attempt {}; retrying in {} ms")
                .addArgument(bucket)
                .addArgument(key)
                .addArgument(attempt)
                .addArgument(uploadRetryDelay::toMillis)
                .log();
            uploadRetryScheduler.schedule(
                () -> uploadFileWithRetries(key, file, futures, diskReservations, attempt + 1),
                uploadRetryDelay.toMillis(),
                TimeUnit.MILLISECONDS
            );
        });
    }

    private CompletableFuture<Void> uploadFile(String key, Path file) {
        var putRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType("application/gzip")
            .build();
        return s3Client.putObject(putRequest, AsyncRequestBody.fromFile(file))
            .thenApply(r -> null);
    }

    private ThreadFactory makeThreadFactory(String role) {
        return runnable -> {
            var thread = new Thread(runnable, "s3-tuple-sink-" + role + "-" + sinkIndex);
            thread.setDaemon(false);
            return thread;
        };
    }

    private void shutdownExecutorIfDone() {
        if (closeRequested.get() && activeUploads.get() == 0) {
            executor.shutdown();
            uploadRetryScheduler.shutdown();
        }
    }

    private void deleteFile(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.atWarn().setCause(e).setMessage("Failed to delete S3 tuple temp file {}")
                .addArgument(file).log();
        }
    }
}
