package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

/**
 * Simplified interface for extracting documents from Elasticsearch/OpenSearch snapshots.
 * Provides methods to discover snapshots, indices, and stream source documents.
 */
public interface SnapshotExtractor {
    
    /**
     * List all snapshots available in the repository.
     * 
     * @return List of snapshot information
     * @throws IOException if there's an error reading the repository
     */
    List<SnapshotInfo> listSnapshots() throws IOException;
    
    /**
     * List all indices contained in a specific snapshot.
     * 
     * @param snapshotName The name of the snapshot
     * @return List of index information
     * @throws IOException if there's an error reading the snapshot metadata
     */
    List<IndexInfo> listIndices(String snapshotName) throws IOException;
    
    /**
     * Stream source documents from a specific index in a snapshot.
     * The returned stream should be closed after use to free resources.
     * 
     * @param snapshotName The name of the snapshot
     * @param indexName The name of the index
     * @return Stream of source documents
     * @throws IOException if there's an error reading the index data
     */
    Stream<SourceDocument> getDocuments(String snapshotName, String indexName) throws IOException;
}
