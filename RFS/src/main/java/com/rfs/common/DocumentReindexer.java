package com.rfs.common;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

        return documentStream
            .map(this::convertDocumentToBulkSection)  // Convert each Document to part of a bulk operation
            .bufferUntil(bufferPredicate(numDocsPerBulkRequest, numBytesPerBulkRequest)) // Collect until you hit the batch size or max size
            .doOnNext(bulk -> logger.info("{} documents in current bulk request", bulk.size()))
            .map(this::convertToBulkRequestBody)  // Assemble the bulk request body from the parts
            .delayElements(Duration.ofMillis((long) (1000 / maxRequestsPerSecond))) // Slow down rate of requests
            .limitRate(Math.max((int) maxRequestsPerSecond * 2, 10), 1) // Control accumulation of requests
            .flatMap(
                bulkJson -> client.sendBulkRequest(indexName, bulkJson, context.createBulkRequest()) // Send the request
                    .doOnSuccess(unused -> logger.debug("Batch succeeded"))
                    .doOnError(error -> logger.error("Batch failed", error))
                    // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
                    .onErrorResume(e -> Mono.empty()),
                maxConcurrentRequests)
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
//                currentSize += next.getBytes(StandardCharsets.UTF_8).length;
                currentSize += next.length();

                // Return false to keep buffering while conditions are met
                if (currentSize == 0 ||
                    (currentItemCount <= maxItems && currentSize <= maxSizeInBytes)) {
                    return false;
                }

                // Reset and return true to signal to stop buffering.
                // Next item is excluded from current buffer
                currentItemCount = 0;
                currentSize = 0;
                return true;
            }
        };
    }
}
