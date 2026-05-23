package org.opensearch.migrations.replay;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.http.retries.IRetryVisitorFactory;
import org.opensearch.migrations.replay.sink.ThreadLocalTupleWriter;
import org.opensearch.migrations.replay.sink.TupleSink;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that handleCompletedTransaction commits traffic streams even when the tuple
 * writer throws synchronously.
 *
 * <p>Regression test: a synchronous exception out of {@code packageAndWriteTuple}
 * (e.g. {@code S3TupleSink.openNewStream} -&gt; AWS SDK
 * {@code IllegalStateException("The service request was not made within 10 seconds...")})
 * used to escape {@code handleCompletedTransaction} before the writeFuture's
 * whenComplete could schedule {@code commitTrafficStreams}, leaving the offset at the
 * head of {@code OffsetLifecycleTracker}'s priority queue forever. Every subsequent
 * commit was blocked, and the consumer-group LAG never drained.</p>
 */
@Slf4j
public class HandleCompletedTransactionCommitTest {

    static class TestableReplayerCore extends TrafficReplayerCore {
        @SuppressWarnings("unchecked")
        TestableReplayerCore() {
            super(
                mock(IRootReplayerContext.class),
                URI.create("http://localhost:9200"),
                null,
                () -> mock(IJsonTransformer.class),
                mock(TrafficStreamLimiter.class),
                mock(TrafficReplayerCore.IWorkTracker.class),
                mock(IRetryVisitorFactory.class)
            );
        }

        @Override
        protected CompletableFuture<Void> shutdown(Error error) {
            return CompletableFuture.completedFuture(null);
        }

        // Expose handleCompletedTransaction for testing
        Void invokeHandle(IReplayContexts.IReplayerHttpTransactionContext ctx,
                          RequestResponsePacketPair rrPair,
                          TransformedTargetRequestAndResponseList summary,
                          Throwable t,
                          ThreadLocalTupleWriter tupleWriter,
                          ITrafficCaptureSource src) {
            var callbacks = new TrafficReplayerAccumulationCallbacks(
                null, tupleWriter, null, c -> {}, src, java.time.Duration.ZERO);
            return callbacks.handleCompletedTransaction(ctx, rrPair, summary, t);
        }
    }

    /** Tuple writer whose underlying sink throws synchronously on first accept(). */
    private static ThreadLocalTupleWriter throwingTupleWriter() {
        return new ThreadLocalTupleWriter(idx -> new TupleSink() {
            @Override
            public void accept(java.util.Map<String, Object> tupleMap, CompletableFuture<Void> future) {
                throw new IllegalStateException(
                    "Simulated AWS SDK BlockingOutputStreamAsyncRequestBody timeout");
            }

            @Override
            public void flush() {}

            @Override
            public void periodicFlush() {}

            @Override
            public void close() {}
        });
    }

    private static IReplayContexts.IReplayerHttpTransactionContext mockTransactionContext() {
        var ctx = mock(IReplayContexts.IReplayerHttpTransactionContext.class);
        var httpCtx = mock(IReplayContexts.IReplayerHttpTransactionContext.class);
        when(ctx.getReplayerRequestKey()).thenReturn(mock(UniqueReplayerRequestKey.class));
        when(httpCtx.createTupleContext()).thenReturn(mock(IReplayContexts.ITupleHandlingContext.class));
        return ctx;
    }

    @Test
    void synchronousTupleWriterFailure_stillCommitsTrafficStreams() {
        var core = new TestableReplayerCore();

        // Set up a real RequestResponsePacketPair-like rrPair with traffic stream keys.
        var trafficStreamKey = mock(ITrafficStreamKey.class);
        var trafficStreamCtx = mock(IReplayContexts.ITrafficStreamsLifecycleContext.class);
        when(trafficStreamKey.getTrafficStreamsContext()).thenReturn(trafficStreamCtx);

        var keysBeingHeld = new ArrayList<ITrafficStreamKey>();
        keysBeingHeld.add(trafficStreamKey);

        var rrPair = mock(RequestResponsePacketPair.class);
        var transactionContext = mock(IReplayContexts.IReplayerHttpTransactionContext.class);
        when(rrPair.getHttpTransactionContext()).thenReturn(transactionContext);
        when(transactionContext.createTupleContext())
            .thenReturn(mock(IReplayContexts.ITupleHandlingContext.class));
        rrPair.trafficStreamKeysBeingHeld = keysBeingHeld;
        rrPair.completionStatus = RequestResponsePacketPair.ReconstructionStatus.COMPLETE;

        // Track commit calls on the source.
        var commitCount = new AtomicInteger();
        var capturedSrc = mock(ITrafficCaptureSource.class);
        try {
            when(capturedSrc.commitTrafficStream(any())).thenAnswer(inv -> {
                commitCount.incrementAndGet();
                return ITrafficCaptureSource.CommitResult.AFTER_NEXT_READ;
            });
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        var ctx = mockTransactionContext();
        var summary = mock(TransformedTargetRequestAndResponseList.class);
        var tupleWriter = throwingTupleWriter();

        // Expect: handleCompletedTransaction throws (rethrows the IllegalStateException), but
        // commitTrafficStreams MUST have been called for the held keys before the rethrow.
        Assertions.assertThrows(RuntimeException.class, () ->
            core.invokeHandle(ctx, rrPair, summary, null, tupleWriter, capturedSrc));

        Assertions.assertEquals(1, commitCount.get(),
            "commitTrafficStream must be invoked once even when the tuple writer throws synchronously");
    }
}
