package org.opensearch.migrations.snapshot.es_v6;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.snapshot.IndexInfo;
import org.opensearch.migrations.snapshot.RepositoryMetadata;
import org.opensearch.migrations.snapshot.SnapshotInfo;
import org.opensearch.migrations.snapshot.SnapshotRepositoryParser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parser for Elasticsearch 6.x snapshot repository metadata format
 */
public class ES6SnapshotRepositoryParser implements SnapshotRepositoryParser {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Check if this parser can handle the given metadata format
     * ES 6.x format is identified by the absence of "min_version" field
     */
    @Override
    public boolean canParse(InputStream inputStream) throws IOException {
        try {
            JsonNode rootNode = objectMapper.readTree(inputStream);
            // ES 6.x doesn't have min_version field, ES 7.x does
            return !rootNode.has("min_version");
        } catch (Exception e) {
            // If we can't parse it, this parser can't handle it
            return false;
        }
    }
    
    /**
     * Parse ES 6.x snapshot repository metadata from an InputStream
     * @param inputStream the input stream containing the index-N file content
     * @return parsed RepositoryMetadata
     * @throws IOException if parsing fails
     */
    @Override
    public RepositoryMetadata parse(InputStream inputStream) throws IOException {
        ES6RepositoryMetadataDto dto = objectMapper.readValue(inputStream, ES6RepositoryMetadataDto.class);
        return convertToRepositoryMetadata(dto);
    }
    
    /**
     * Convert the ES6-specific DTO to the common RepositoryMetadata format
     */
    private RepositoryMetadata convertToRepositoryMetadata(ES6RepositoryMetadataDto dto) {
        // Convert snapshots
        List<SnapshotInfo> snapshots = null;
        if (dto.snapshots != null) {
            snapshots = dto.snapshots.stream()
                .map(this::convertSnapshot)
                .collect(Collectors.toList());
        }
        
        // Convert indices
        List<IndexInfo> indices = null;
        if (dto.indices != null) {
            indices = dto.indices.entrySet().stream()
                .map(entry -> convertIndex(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
        }
        
        // Create RepositoryMetadata using record constructor
        // ES 6.x doesn't have min_version field
        return new RepositoryMetadata(snapshots, indices, null);
    }
    
    private SnapshotInfo convertSnapshot(ES6SnapshotDto snapshotDto) {
        return SnapshotInfo.builder()
            .name(snapshotDto.name)
            .uuid(snapshotDto.uuid)
            .state(snapshotDto.state)
            .version(null) // ES 6.x doesn't include version in snapshot metadata
            .build();
    }
    
    private IndexInfo convertIndex(String indexName, ES6IndexDto indexDto) {
        IndexInfo indexInfo = IndexInfo.builder()
            .name(indexName)
            .id(indexDto.id)
            .snapshotUuids(indexDto.snapshots)
            .shardGenerations(null) // ES 6.x doesn't have shard_generations
            .build();
        
        return indexInfo;
    }
    
    /**
     * Root DTO for ES 6.x repository metadata
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ES6RepositoryMetadataDto(
        @JsonProperty("snapshots") List<ES6SnapshotDto> snapshots,
        @JsonProperty("indices") java.util.Map<String, ES6IndexDto> indices
    ) {}
    
    /**
     * DTO for individual snapshot metadata in ES 6.x format
     * Note: ES 6.x doesn't include version field in snapshot metadata
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ES6SnapshotDto(
        @JsonProperty("name") String name,
        @JsonProperty("uuid") String uuid,
        @JsonProperty("state") int state
    ) {}
    
    /**
     * DTO for individual index metadata in ES 6.x format
     * Note: ES 6.x doesn't include shard_generations field
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ES6IndexDto(
        @JsonProperty("id") String id,
        @JsonProperty("snapshots") List<String> snapshots
    ) {}
}
