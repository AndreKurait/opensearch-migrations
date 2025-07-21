package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.io.BlobSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Core engine for snapshot extraction that orchestrates the extraction process
 * while delegating version-specific operations to a VersionAdapter.
 * 
 * This class contains the business logic for:
 * - Orchestrating document extraction across shards
 * - Managing temporary files and cleanup
 * - Error handling and logging
 * - Resource management
 */
@Slf4j
public class SnapshotExtractionEngine implements SnapshotExtractor {
    
    private final VersionAdapter versionAdapter;
    private final SnapshotRepository repository;
    private final BlobSource blobSource;
    private final Path tempDirectory;
    
    /**
     * Create a new snapshot extraction engine.
     * 
     * @param versionAdapter The version-specific adapter
     * @param repository The snapshot repository
     * @throws IOException if temp directory cannot be created
     */
    public SnapshotExtractionEngine(VersionAdapter versionAdapter, SnapshotRepository repository) throws IOException {
        this.versionAdapter = versionAdapter;
        this.repository = repository;
        this.blobSource = repository.getBlobSource();
        this.tempDirectory = Files.createTempDirectory("snapshot-extraction-");
        
        log.info("Created SnapshotExtractionEngine with {} adapter, temp directory: {}", 
                versionAdapter.getVersionIdentifier(), tempDirectory);
    }
    
    /**
     * Create a new snapshot extraction engine with explicit blob source.
     * 
     * @param versionAdapter The version-specific adapter
     * @param blobSource The blob source for reading data
     * @throws IOException if temp directory cannot be created
     */
    public SnapshotExtractionEngine(VersionAdapter versionAdapter, BlobSource blobSource) throws IOException {
        this.versionAdapter = versionAdapter;
        this.repository = null; // Not available in this constructor
        this.blobSource = blobSource;
        this.tempDirectory = Files.createTempDirectory("snapshot-extraction-");
        
        log.info("Created SnapshotExtractionEngine with {} adapter and explicit blob source, temp directory: {}", 
                versionAdapter.getVersionIdentifier(), tempDirectory);
    }
    
    @Override
    public List<SnapshotInfo> listSnapshots() throws IOException {
        if (repository == null) {
            throw new UnsupportedOperationException(
                "listSnapshots requires a repository - use constructor with SnapshotRepository");
        }
        
        log.debug("Listing snapshots using repository metadata");
        RepositoryMetadata metadata = repository.readRepositoryMetadata();
        List<SnapshotInfo> snapshots = metadata.snapshots();
        
        log.info("Found {} snapshots in repository", snapshots.size());
        return snapshots;
    }
    
    @Override
    public List<IndexInfo> listIndices(String snapshotName) throws IOException {
        log.info("Listing indices for snapshot: {}", snapshotName);
        
        SnapshotReference snapshotRef = SnapshotReference.of(snapshotName);
        String metadataPath = versionAdapter.getSnapshotMetadataPath(snapshotRef);
        log.debug("Reading snapshot metadata from path: {}", metadataPath);
        
        try (InputStream stream = blobSource.getBlob(metadataPath)) {
            List<IndexInfo> indices = versionAdapter.parseSnapshotIndices(snapshotRef, stream);
            log.info("Found {} indices in snapshot {}", indices.size(), snapshotName);
            return indices;
        }
    }
    
    @Override
    public Stream<SourceDocument> getDocuments(String snapshotName, String indexName) throws IOException {
        log.info("Getting documents for snapshot: {}, index: {}", snapshotName, indexName);
        
        // Create intermediate data formats
        SnapshotReference snapshotRef = SnapshotReference.of(snapshotName);
        IndexReference indexRef = versionAdapter.parseIndexMetadata(snapshotRef, indexName);
        log.debug("Index {} has {} shards", indexName, indexRef.getShardCount());
        
        // Create a stream that processes each shard
        return Stream.iterate(0, i -> i < indexRef.getShardCount(), i -> i + 1)
            .flatMap(shardId -> {
                try {
                    return processShardDocuments(snapshotRef, indexRef, shardId);
                } catch (IOException e) {
                    log.error("Failed to read shard {}/{}/{}: {}", snapshotName, indexName, shardId, e.getMessage());
                    return Stream.empty();
                }
            });
    }
    
    /**
     * Process documents from a single shard.
     * This method contains the core business logic for shard processing.
     * 
     * @param snapshotRef The snapshot reference
     * @param indexRef The index reference
     * @param shardId The shard ID
     * @return Stream of documents from this shard
     * @throws IOException if processing fails
     */
    private Stream<SourceDocument> processShardDocuments(SnapshotReference snapshotRef, IndexReference indexRef, int shardId) 
            throws IOException {
        log.debug("Processing shard {}/{}/{}", snapshotRef.getName(), indexRef.getName(), shardId);
        
        // Read shard metadata using version-specific adapter
        ShardMetadata shardMetadata = versionAdapter.readShardMetadata(snapshotRef, indexRef, shardId);
        log.debug("Shard metadata: {} files, {} total docs, {} deleted docs", 
                shardMetadata.getFiles().size(), 
                shardMetadata.getTotalDocs(), 
                shardMetadata.getDeletedDocs());
        
        // Extract shard files to temporary directory
        ShardExtractor extractor = new ShardExtractor(blobSource, tempDirectory);
        Path shardDir = extractor.extractShard(shardMetadata);
        log.debug("Extracted shard files to: {}", shardDir);
        
        try {
            // Create version-specific document reader
            LuceneDocumentReader reader = versionAdapter.createDocumentReader(shardDir, indexRef, shardId);
            
            // Return a stream that will clean up resources when closed
            return reader.streamDocuments()
                .onClose(() -> cleanupShardResources(reader, extractor, shardDir));
                
        } catch (IOException e) {
            // Clean up on error
            cleanupShardResources(null, extractor, shardDir);
            throw e;
        }
    }
    
    /**
     * Clean up resources for a single shard.
     * 
     * @param reader The document reader (may be null)
     * @param extractor The shard extractor
     * @param shardDir The shard directory
     */
    private void cleanupShardResources(LuceneDocumentReader reader, ShardExtractor extractor, Path shardDir) {
        log.debug("Cleaning up shard resources for directory: {}", shardDir);
        
        // Close reader if available
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                log.warn("Failed to close document reader: {}", e.getMessage());
            }
        }
        
        // Clean up extracted files
        extractor.cleanup(shardDir);
    }
    
    /**
     * Get the underlying BlobSource for advanced operations.
     * 
     * @return The blob source
     */
    public BlobSource getBlobSource() {
        return blobSource;
    }
    
    /**
     * Get the version adapter being used.
     * 
     * @return The version adapter
     */
    public VersionAdapter getVersionAdapter() {
        return versionAdapter;
    }
    
    /**
     * Clean up all temporary files created by this engine.
     * This should be called when the engine is no longer needed.
     */
    public void cleanup() {
        log.info("Cleaning up SnapshotExtractionEngine temp directory: {}", tempDirectory);
        
        try {
            if (Files.exists(tempDirectory)) {
                Files.walk(tempDirectory)
                     .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                     .forEach(path -> {
                         try {
                             Files.delete(path);
                         } catch (IOException e) {
                             log.warn("Failed to delete {}: {}", path, e.getMessage());
                         }
                     });
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup temp directory {}: {}", tempDirectory, e.getMessage());
        }
    }
    
    /**
     * Get statistics about the extraction engine.
     * 
     * @return Engine statistics
     */
    public EngineStatistics getStatistics() {
        return EngineStatistics.builder()
            .versionAdapter(versionAdapter.getVersionIdentifier())
            .tempDirectory(tempDirectory.toString())
            .build();
    }
    
    /**
     * Statistics about the extraction engine.
     */
    public static class EngineStatistics {
        private final String versionAdapter;
        private final String tempDirectory;
        
        private EngineStatistics(String versionAdapter, String tempDirectory) {
            this.versionAdapter = versionAdapter;
            this.tempDirectory = tempDirectory;
        }
        
        public String getVersionAdapter() { return versionAdapter; }
        public String getTempDirectory() { return tempDirectory; }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private String versionAdapter;
            private String tempDirectory;
            
            public Builder versionAdapter(String versionAdapter) {
                this.versionAdapter = versionAdapter;
                return this;
            }
            
            public Builder tempDirectory(String tempDirectory) {
                this.tempDirectory = tempDirectory;
                return this;
            }
            
            public EngineStatistics build() {
                return new EngineStatistics(versionAdapter, tempDirectory);
            }
        }
        
        @Override
        public String toString() {
            return String.format("EngineStatistics{versionAdapter='%s', tempDirectory='%s'}", 
                    versionAdapter, tempDirectory);
        }
    }
}
