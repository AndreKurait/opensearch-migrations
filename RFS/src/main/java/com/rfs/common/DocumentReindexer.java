package com.rfs.common;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;

@RequiredArgsConstructor
public class DocumentReindexer {
    private static final Logger logger = LogManager.getLogger(DocumentReindexer.class);
    protected final OpenSearchClient client;
    private final int numDocsPerBulkRequest;
    private final long numBytesPerBulkRequest;
    private final int maxConcurrentRequests;
    private final double maxRequestsPerSecond;

    public Mono<Void> reindex(
        String indexName,
        Flux<Document> documentStream,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {
        // After a pause/slowdown, allow up to 5 seconds of bursting or twice the allowed concurrent requests
        final int requestBuffer = Math.min((int) (maxRequestsPerSecond * 5), maxConcurrentRequests * 2);
        final long millisBetweenRequestStarts = (long) (1000 / maxRequestsPerSecond);
        return documentStream
            .map(this::convertDocumentToBulkSection)
            .bufferWhile(bufferPredicate(numDocsPerBulkRequest, numBytesPerBulkRequest))
            .subscribeOn(Schedulers.parallel()) // Process documents in parallel
            .delayElements(Duration.ofMillis(millisBetweenRequestStarts), Schedulers.single())
            .limitRate(requestBuffer) // Buffer delayed requests to allow for limited bursting
            .flatMap(
                bulkSections -> client
                    .sendBulkRequest(indexName,
                        this.convertToBulkRequestBody(bulkSections),
                        context.createBulkRequest()) // Send the request
                    .doOnRequest(ignored ->
                        logger.info("{} documents in current bulk request. First doc is size {} bytes",
                            bulkSections.size(),
                            bulkSections.get(0).getBytes(StandardCharsets.UTF_8).length)
                    )
                    .doOnSuccess(unused -> logger.debug("Batch succeeded"))
                    .doOnError(error -> logger.error("Batch failed", error))
                    // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
                    .onErrorResume(e -> Mono.empty()),
                maxConcurrentRequests
            )
            .publishOn(Schedulers.parallel()) // Parallel for Non-I/O Tasks after this
            .doOnComplete(() -> logger.debug("All batches processed"))
            .then();
    }

    private String convertDocumentToBulkSection(Document document) {
        String id = Uid.decodeId(document.getBinaryValue("_id").bytes);
        String source = document.getBinaryValue("_source").utf8ToString();
        String action = "{\"index\": {\"_id\": \"" + id + "\"}}";

        return action + "\n" + source;
    }

    private String convertToBulkRequestBody(List<String> bulkSections) {
        StringBuilder builder = new StringBuilder();
        for (String section : bulkSections) {
            builder.append(section).append("\n");
        }
        return builder.toString();
    }

    private static java.util.function.Predicate<String> bufferPredicate(int maxItems, long maxSizeInBytes) {
        return new java.util.function.Predicate<>() {
            private int currentItemCount = 0;
            private long currentSize = 0;

            @Override
            public boolean test(String next) {
                currentItemCount++;
                // TODO: Move to Bytebufs to convert from string to bytes only once
                // Add one for newline between bulk sections
                currentSize += next.getBytes(StandardCharsets.UTF_8).length + 1;

                // Return true to keep buffering while conditions are met
                if (currentSize == 0 ||
                    (currentItemCount <= maxItems && currentSize <= maxSizeInBytes)) {
                    return true;
                }

                // Reset and return false to signal to stop buffering.
                // Next item is excluded from current buffer
                currentItemCount = 0;
                currentSize = 0;
                return false;
            }
        };
    }
}
