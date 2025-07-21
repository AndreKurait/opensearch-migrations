package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Simplified interface for version-specific operations in snapshot extraction.
 * Uses intermediate data formats to minimize parsing and focus on essential operations.
 */
public interface VersionAdapter {
    
    /**
     * Parse snapshot metadata to extract list of indices with minimal information.
     * Only parses the fields actually needed for extraction.
     * 
     * @param snapshotRef The snapshot reference
     * @param metadataStream Input stream containing snapshot metadata
     * @return List of indices in the snapshot
     * @throws IOException if parsing fails
     */
    List<IndexInfo> parseSnapshotIndices(SnapshotReference snapshotRef, InputStream metadataStream) throws IOException;
    
    /**
     * Parse index metadata to get essential index information.
     * Only extracts shard count and other minimal fields needed.
     * 
     * @param snapshotRef The snapshot reference
     * @param indexName The index name
     * @return Index reference with minimal information
     * @throws IOException if reading metadata fails
     */
    IndexReference parseIndexMetadata(SnapshotReference snapshotRef, String indexName) throws IOException;
    
    /**
     * Read shard metadata for a specific shard.
     * Only parses file information needed for extraction.
     * 
     * @param snapshotRef The snapshot reference
     * @param indexRef The index reference
     * @param shardId The shard ID
     * @return Shard metadata
     * @throws IOException if reading metadata fails
     */
    ShardMetadata readShardMetadata(SnapshotReference snapshotRef, IndexReference indexRef, int shardId) throws IOException;
    
    /**
     * Create a document reader for the specific Lucene version used by this ES version.
     * 
     * @param shardDir Directory containing extracted Lucene files
     * @param indexRef The index reference
     * @param shardId The shard ID
     * @return Document reader for this version
     * @throws IOException if reader creation fails
     */
    LuceneDocumentReader createDocumentReader(Path shardDir, IndexReference indexRef, int shardId) throws IOException;
    
    /**
     * Get the path to snapshot metadata file using version-specific naming.
     * 
     * @param snapshotRef The snapshot reference
     * @return Path to snapshot metadata file
     */
    String getSnapshotMetadataPath(SnapshotReference snapshotRef);
    
    /**
     * Get the path to index metadata file using version-specific naming.
     * 
     * @param snapshotRef The snapshot reference
     * @param indexRef The index reference
     * @return Path to index metadata file
     */
    String getIndexMetadataPath(SnapshotReference snapshotRef, IndexReference indexRef);
    
    /**
     * Get the path to shard metadata file using version-specific naming.
     * 
     * @param snapshotRef The snapshot reference
     * @param indexRef The index reference
     * @param shardId The shard ID
     * @return Path to shard metadata file
     */
    String getShardMetadataPath(SnapshotReference snapshotRef, IndexReference indexRef, int shardId);
    
    /**
     * Get the version identifier for this adapter.
     * 
     * @return Version string (e.g., "ES1", "ES6", "ES7")
     */
    String getVersionIdentifier();
}
