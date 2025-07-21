package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.opensearch.migrations.io.BlobSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of SnapshotExtractor for ES1 snapshots.
 * Provides simplified access to snapshot data with focus on document extraction.
 */
@Slf4j
public class ES1SnapshotExtractor implements SnapshotExtractor {
    
    private final SnapshotRepository repository;
    private final BlobSource blobSource;
    private final Path tempDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    /**
     * Create a new ES1 snapshot extractor.
     * 
     * @param repository The snapshot repository
     * @throws IOException if temp directory cannot be created
     */
    public ES1SnapshotExtractor(SnapshotRepository repository) throws IOException {
        this.repository = repository;
        this.blobSource = repository.getBlobSource();
        this.tempDirectory = Files.createTempDirectory("es1-snapshot-");
        log.info("Created ES1 snapshot extractor with temp directory: {}", tempDirectory);
    }
    
    /**
     * Create a new ES1 snapshot extractor from a repository path.
     * 
     * @param repositoryPath Path to the snapshot repository
     * @return A new ES1SnapshotExtractor instance
     * @throws IOException if initialization fails
     */
    public static ES1SnapshotExtractor fromPath(Path repositoryPath) throws IOException {
        return new ES1SnapshotExtractor(SnapshotRepository.fromPath(repositoryPath));
    }
    
    @Override
    public List<SnapshotInfo> listSnapshots() throws IOException {
        RepositoryMetadata metadata = repository.readRepositoryMetadata();
        return metadata.snapshots();
    }
    
    @Override
    public List<IndexInfo> listIndices(String snapshotName) throws IOException {
        // For ES1, we need to read the snapshot metadata file
        String snapshotPath = "snapshot-" + snapshotName;
        
        try (InputStream stream = blobSource.getBlob(snapshotPath)) {
            ES1SnapshotMetadata snapshotMetadata = objectMapper.readValue(stream, ES1SnapshotMetadata.class);
            
            // Convert ES1 format to our IndexInfo format
            List<IndexInfo> indices = new ArrayList<>();
            
            // ES1 format has snapshot wrapper
            if (snapshotMetadata.snapshot != null && snapshotMetadata.snapshot.indices != null) {
                for (String indexName : snapshotMetadata.snapshot.indices) {
                    indices.add(IndexInfo.builder()
                        .name(indexName)
                        .id(indexName) // ES1 uses name as ID
                        .snapshotUuids(List.of(snapshotName))
                        .build());
                }
            }
            
            return indices;
        }
    }
    
    @Override
    public Stream<SourceDocument> getDocuments(String snapshotName, String indexName) throws IOException {
        log.info("Getting documents for snapshot: {}, index: {}", snapshotName, indexName);
        
        // Get index metadata to determine number of shards
        int shardCount = getShardCount(snapshotName, indexName);
        
        // Create a stream that processes each shard
        return Stream.iterate(0, i -> i < shardCount, i -> i + 1)
            .flatMap(shardId -> {
                try {
                    return getShardDocuments(snapshotName, indexName, shardId);
                } catch (IOException e) {
                    log.error("Failed to read shard {}/{}: {}", indexName, shardId, e.getMessage());
                    return Stream.empty();
                }
            });
    }
    
    private int getShardCount(String snapshotName, String indexName) throws IOException {
        // Read the index metadata to get shard count
        String indexMetadataPath = String.format("indices/%s/snapshot-%s", indexName, snapshotName);
        
        try (InputStream stream = blobSource.getBlob(indexMetadataPath)) {
            ES1IndexMetadata indexMetadata = objectMapper.readValue(stream, ES1IndexMetadata.class);
            
            // ES1 format has the index wrapped in its name
            if (indexMetadata.simpleIndex != null && 
                indexMetadata.simpleIndex.settings != null) {
                return Integer.parseInt(indexMetadata.simpleIndex.settings.indexNumberOfShards);
            }
            
            // Fallback - assume 1 shard if we can't parse
            return 1;
        }
    }
    
    private Stream<SourceDocument> getShardDocuments(String snapshotName, String indexName, int shardId) 
            throws IOException {
        log.info("Processing shard {}/{}", indexName, shardId);
        
        // Read shard metadata
        ShardMetadata shardMetadata = readShardMetadata(snapshotName, indexName, shardId);
        
        // Extract shard files
        ShardExtractor extractor = new ShardExtractor(blobSource, tempDirectory);
        Path shardDir = extractor.extractShard(shardMetadata);
        
        try {
            // Read documents from the extracted Lucene index
            LuceneDocumentReader reader = new LuceneDocumentReader(shardDir, indexName, shardId);
            
            // Return a stream that will clean up when closed
            return reader.streamDocuments()
                .onClose(() -> {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        log.warn("Failed to close reader: {}", e.getMessage());
                    }
                    extractor.cleanup(shardDir);
                });
                
        } catch (IOException e) {
            // Clean up on error
            extractor.cleanup(shardDir);
            throw e;
        }
    }
    
    public ShardMetadata readShardMetadata(String snapshotName, String indexName, int shardId) 
            throws IOException {
        String shardMetadataPath = String.format("indices/%s/%d/snapshot-%s", 
                                               indexName, shardId, snapshotName);
        
        try (InputStream stream = blobSource.getBlob(shardMetadataPath)) {
            ES1ShardMetadata es1Metadata = objectMapper.readValue(stream, ES1ShardMetadata.class);
            
            // Convert ES1 format to our ShardMetadata format
            List<ShardMetadata.ShardFileInfo> files = new ArrayList<>();
            if (es1Metadata.files != null) {
                for (ES1FileInfo fileInfo : es1Metadata.files) {
                    files.add(ShardMetadata.ShardFileInfo.builder()
                        .name(fileInfo.name)
                        .physicalName(fileInfo.physicalName)
                        .length(fileInfo.length)
                        .checksum(fileInfo.checksum)
                        .build());
                }
            }
            
            return ShardMetadata.builder()
                .snapshotName(snapshotName)
                .indexName(indexName)
                .shardId(shardId)
                .files(files)
                .totalDocs(es1Metadata.totalDocs != null ? es1Metadata.totalDocs : 0L)
                .deletedDocs(es1Metadata.deletedDocs != null ? es1Metadata.deletedDocs : 0L)
                .build();
        }
    }
    
    /**
     * Get the underlying BlobSource for advanced operations.
     */
    public BlobSource getBlobSource() {
        return blobSource;
    }
    
    /**
     * Clean up temporary files created by this extractor.
     */
    public void cleanup() {
        try {
            if (Files.exists(tempDirectory)) {
                Files.walk(tempDirectory)
                     .sorted((a, b) -> b.compareTo(a))
                     .forEach(path -> {
                         try {
                             Files.delete(path);
                         } catch (IOException e) {
                             log.warn("Failed to delete {}: {}", path, e.getMessage());
                         }
                     });
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup temp directory: {}", e.getMessage());
        }
    }
    
    // DTOs for ES1 metadata formats
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ES1SnapshotMetadata {
        public String name;
        public List<String> indices;
        public String state;
        public String reason;
        public Long startTime;
        public Long endTime;
        public Integer totalShards;
        public Integer successfulShards;
        public List<Object> failures;
        
        // ES1 metadata files have a different structure
        public ES1MetaDataWrapper snapshot;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ES1MetaDataWrapper {
        public String name;
        public List<String> indices;
        public String state;
        @JsonProperty("start_time")
        public Long startTime;
        @JsonProperty("end_time")
        public Long endTime;
        @JsonProperty("total_shards")
        public Integer totalShards;
        @JsonProperty("successful_shards")
        public Integer successfulShards;
        public List<Object> failures;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ES1IndexMetadata {
        public String index;
        public Integer numberOfShards;
        public Integer numberOfReplicas;
        
        // ES1 index metadata is wrapped in the index name
        @JsonProperty("simple_index")
        public ES1IndexSettings simpleIndex;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ES1IndexSettings {
        public Integer version;
        public String state;
        public ES1Settings settings;
        public List<Object> mappings;
        public Object aliases;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ES1Settings {
        @JsonProperty("index.number_of_replicas")
        public String indexNumberOfReplicas;
        @JsonProperty("index.number_of_shards")
        public String indexNumberOfShards;
        @JsonProperty("index.version.created")
        public String indexVersionCreated;
        @JsonProperty("index.creation_date")
        public String indexCreationDate;
        @JsonProperty("index.uuid")
        public String indexUuid;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ES1ShardMetadata {
        public String name;
        public String indexName;
        public Integer indexVersion;
        @JsonProperty("index_version")
        public Integer indexVersionSnakeCase;
        public Long startTime;
        @JsonProperty("start_time")
        public Long startTimeSnakeCase;
        public Long time;
        public Integer numberOfFiles;
        @JsonProperty("number_of_files")
        public Integer numberOfFilesSnakeCase;
        public Long totalSize;
        @JsonProperty("total_size")
        public Long totalSizeSnakeCase;
        public List<ES1FileInfo> files;
        public Long totalDocs;
        public Long deletedDocs;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ES1FileInfo {
        public String name;
        @JsonProperty("physical_name")
        public String physicalName;
        public Long length;
        public String checksum;
    }
}
