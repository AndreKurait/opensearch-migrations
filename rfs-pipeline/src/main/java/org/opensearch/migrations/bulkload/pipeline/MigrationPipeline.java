package org.opensearch.migrations.bulkload.pipeline;


import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import reactor.core.publisher.Flux;

/**
 * Wires a DocumentSource to a DocumentSink with batching.
 *
 * This is the core pipeline — it knows nothing about Lucene, snapshots, OpenSearch,
 * or any specific source/target. It just moves DocumentChange records from source to sink
 * in batches, emitting ProgressCursor records for tracking.
 */
public class MigrationPipeline {

    private final DocumentSource source;
    private final DocumentSink sink;
    private final int maxDocsPerBatch;
    private final long maxBytesPerBatch;

    public MigrationPipeline(DocumentSource source, DocumentSink sink, int maxDocsPerBatch, long maxBytesPerBatch) {
        this.source = source;
        this.sink = sink;
        this.maxDocsPerBatch = maxDocsPerBatch;
        this.maxBytesPerBatch = maxBytesPerBatch;
    }

    /**
     * Migrate all documents for a shard from source to sink.
     * Returns a Flux of progress cursors, one per batch written.
     */
    public Flux<ProgressCursor> migrateShard(ShardId shardId, String indexName, int startingDocOffset) {
        return source.readDocuments(shardId, startingDocOffset)
            .bufferUntil(new BatchPredicate(maxDocsPerBatch, maxBytesPerBatch))
            .flatMapSequential(batch -> sink.writeBatch(shardId, indexName, batch));
    }

    /**
     * Migrate all shards for an index. Creates the index first, then migrates each shard.
     */
    public Flux<ProgressCursor> migrateIndex(String indexName) {
        var metadata = source.readIndexMetadata(indexName);
        return Flux.from(sink.createIndex(metadata))
            .thenMany(Flux.fromIterable(source.listShards(indexName)))
            .flatMapSequential(shardId -> migrateShard(shardId, indexName, 0));
    }

    /**
     * Batching predicate that groups documents by count and byte size.
     * Stateful — tracks current batch size and resets on batch boundary.
     */
    static class BatchPredicate implements java.util.function.Predicate<DocumentChange> {
        private final int maxDocs;
        private final long maxBytes;
        private int currentCount;
        private long currentBytes;

        BatchPredicate(int maxDocs, long maxBytes) {
            this.maxDocs = maxDocs;
            this.maxBytes = maxBytes;
        }

        @Override
        public boolean test(DocumentChange doc) {
            currentCount++;
            currentBytes += doc.source() != null ? doc.source().length : 0;

            if (currentCount >= maxDocs || currentBytes >= maxBytes) {
                currentCount = 0;
                currentBytes = 0;
                return true; // End of batch
            }
            return false;
        }
    }
}
