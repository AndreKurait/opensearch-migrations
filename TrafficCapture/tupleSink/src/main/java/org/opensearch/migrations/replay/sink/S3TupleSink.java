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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

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
 * advancing. So instead we apply real backpressure: a semaphore caps accepted-but-not-yet-durably-
 * uploaded tuples at {@code maxInFlightTuples}, and when the cap is reached {@code accept()} BLOCKS
 * the calling thread until a permit frees (i.e. until an in-flight upload completes). This bounds
 * memory (buffered tuples + temp files + pending futures) while letting the replayer ride out an S3
 * outage and resume — throughput drops to zero during the outage by design, rather than failing.
 * The blocking happens on the replay response-completion thread, which is independent of the S3 SDK
 * threads and the sink's worker thread, so the upload/retry machinery keeps draining (no deadlock).
 * Permits are released when each tuple's future settles.</p>
 */
@Slf4j
public class S3TupleSink implements TupleSink {
    static final Duration DEFAULT_UPLOAD_RETRY_DELAY = Duration.ofSeconds(10);

    /**
     * Default cap on accepted-but-not-yet-durably-uploaded tuples. Bounds memory under a sustained
     * S3 outage; when reached, accept() blocks (waits for an upload to drain) rather than failing.
     */
    static final int DEFAULT_MAX_IN_FLIGHT_TUPLES = 100_000;

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
    // Bounds accepted-but-not-yet-durably-uploaded tuples. See class javadoc (Backpressure).
    private final Semaphore inFlightPermits;
    private final int maxInFlightTuples;
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
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.prefix = prefix;
        this.replayerId = replayerId;
        this.sinkIndex = sinkIndex;
        this.rotateAfterBytes = rotateAfterBytes;
        this.rotateAfterAge = rotateAfterAge;
        this.rotateAfterTuples = rotateAfterTuples;
        this.uploadRetryDelay = uploadRetryDelay;
        this.maxInFlightTuples = maxInFlightTuples;
        this.inFlightPermits = new Semaphore(maxInFlightTuples);
        this.executor = Executors.newSingleThreadScheduledExecutor(makeWorkerThreadFactory());
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
        // Backpressure: block the caller until an in-flight permit is available. When S3 is backed
        // up this throttles tuple production (replay slows) instead of growing memory without bound
        // or failing the tuple (which would crash the replayer into a no-progress restart loop).
        // The permit covers the whole lifecycle and is released exactly once when `future` settles.
        if (!acquirePermit(future)) {
            return;  // interrupted/shutting down — future already completed exceptionally
        }
        releasePermitWhenSettled(future);

        // Option A: do NO per-tuple CPU on the calling (event-loop) thread. JSON serialization and
        // gzip both run on the worker thread; accept() only reserves a permit and enqueues.
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
     * Block until an in-flight permit is available. Returns false (and fails the future) only if
     * the thread is interrupted — never on cap exhaustion, which just waits, so a transient S3
     * outage throttles rather than fails. The blocked thread is the replay response-completion
     * thread, independent of the worker/SDK threads that drain permits, so this can't deadlock.
     */
    private boolean acquirePermit(CompletableFuture<Void> future) {
        try {
            inFlightPermits.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(e);
            return false;
        }
    }

    /** Release the in-flight permit exactly once, when the tuple's future settles. */
    private void releasePermitWhenSettled(CompletableFuture<Void> future) {
        future.whenComplete((v, t) -> inFlightPermits.release());
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

        log.atInfo().setMessage("Completing S3 upload to s3://{}/{}").addArgument(bucket).addArgument(key).log();

        activeUploads.incrementAndGet();
        uploadFileWithRetries(key, file, futures, 1);

        if (openNextStream) {
            openNewStream();
        } else {
            clearCurrentStream();
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
        int attempt
    ) {
        uploadFile(key, file).whenComplete((response, error) -> {
            if (error == null) {
                deleteFile(file);
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
            executor.schedule(
                () -> uploadFileWithRetries(key, file, futures, attempt + 1),
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

    private ThreadFactory makeWorkerThreadFactory() {
        return runnable -> {
            var thread = new Thread(runnable, "s3-tuple-sink-worker-" + sinkIndex);
            thread.setDaemon(false);
            return thread;
        };
    }

    private void shutdownExecutorIfDone() {
        if (closeRequested.get() && activeUploads.get() == 0) {
            executor.shutdown();
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
