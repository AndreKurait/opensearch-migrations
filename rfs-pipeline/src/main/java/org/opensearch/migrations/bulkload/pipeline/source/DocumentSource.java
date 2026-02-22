package org.opensearch.migrations.bulkload.pipeline.source;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;

import reactor.core.publisher.Flux;

/**
 * Port for reading documents from any source (snapshot, remote cluster, synthetic test data).
 *
 * This is the key abstraction that enables N+M testing:
 * - Source-side tests verify that a real SnapshotDocumentSource produces correct DocumentChange records
 * - Sink-side tests verify that a SyntheticDocumentSource feeds correctly into a real target
 * - No need for both source AND target in the same test
 */
public interface DocumentSource extends AutoCloseable {

    /** List all available indices. */
    List<String> listIndices();

    /** List all shards for an index. */
    List<ShardId> listShards(String indexName);

    /** Read metadata for an index. */
    IndexMetadataSnapshot readIndexMetadata(String indexName);

    /**
     * Stream document changes for a shard, starting from the given document offset.
     * Returns a cold Flux — subscription triggers the read.
     */
    Flux<DocumentChange> readDocuments(ShardId shardId, int startingDocOffset);

    @Override
    default void close() throws Exception {
        // Default no-op for sources that don't hold resources
    }
}
