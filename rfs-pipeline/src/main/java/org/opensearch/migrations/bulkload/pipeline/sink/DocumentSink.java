package org.opensearch.migrations.bulkload.pipeline.sink;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;

import reactor.core.publisher.Mono;

/**
 * Port for writing documents to any target (OpenSearch cluster, file, test collector).
 *
 * Consumes the clean IR types — never sees Lucene, snapshot formats, or source-specific details.
 */
public interface DocumentSink extends AutoCloseable {

    /** Create an index on the target with the given metadata. */
    Mono<Void> createIndex(IndexMetadataSnapshot metadata);

    /**
     * Write a batch of documents to the target index.
     * Returns a progress cursor indicating what was written.
     */
    Mono<ProgressCursor> writeBatch(ShardId shardId, String indexName, List<DocumentChange> batch);

    @Override
    default void close() throws Exception {
        // Default no-op
    }
}
