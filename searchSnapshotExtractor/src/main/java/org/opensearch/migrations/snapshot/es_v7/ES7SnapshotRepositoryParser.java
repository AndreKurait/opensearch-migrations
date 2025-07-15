package org.opensearch.migrations.snapshot.es_v7;

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
 * Parser for Elasticsearch 7.x snapshot repository metadata format
 */
public class ES7SnapshotRepositoryParser implements SnapshotRepositoryParser {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Check if this parser can handle the given metadata format
     * ES 7.x format is identified by the presence of "min_version" field
     */
    @Override
    public boolean canParse(InputStream inputStream) throws IOException {
        try {
            JsonNode rootNode = objectMapper.readTree(inputStream);
            // ES 7.x has min_version field, ES 6.x doesn't
            return rootNode.has("min_version");
        } catch (Exception e) {
            // If we can't parse it, this parser can't handle it
            return false;
        }
    }
    
    /**
     * Parse ES 7.x snapshot repository metadata from an InputStream
     * @param inputStream the input stream containing the index-N file content
     * @return parsed RepositoryMetadata
     * @throws IOException if parsing fails
     */
    @Override
    public RepositoryMetadata parse(InputStream inputStream) throws IOException {
        ES7RepositoryMetadataDto dto = objectMapper.readValue(inputStream, ES7RepositoryMetadataDto.class);
        return convertToRepositoryMetadata(dto);
    }
    
    /**
     * Convert the ES7-specific DTO to the common RepositoryMetadata format
     */
    private RepositoryMetadata convertToRepositoryMetadata(ES7RepositoryMetadataDto dto) {
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
        return new RepositoryMetadata(snapshots, indices, dto.minVersion);
    }
    
    private SnapshotInfo convertSnapshot(ES7SnapshotDto snapshotDto) {
        return SnapshotInfo.builder()
            .name(snapshotDto.name)
            .uuid(snapshotDto.uuid)
            .state(snapshotDto.state)
            .version(snapshotDto.version)
            .build();
    }
    
    private IndexInfo convertIndex(String indexName, ES7IndexDto indexDto) {
        IndexInfo indexInfo = IndexInfo.builder()
            .name(indexName)
            .id(indexDto.id)
            .snapshotUuids(indexDto.snapshots)
            .shardGenerations(indexDto.shardGenerations)
            .build();
        
        return indexInfo;
    }
    
    /**
     * Root DTO for ES 7.x repository metadata
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ES7RepositoryMetadataDto(
        @JsonProperty("snapshots") List<ES7SnapshotDto> snapshots,
        @JsonProperty("indices") java.util.Map<String, ES7IndexDto> indices,
        @JsonProperty("min_version") String minVersion,
        @JsonProperty("index_metadata_identifiers") java.util.Map<String, String> indexMetadataIdentifiers
    ) {}
    
    /**
     * DTO for individual snapshot metadata in ES 7.x format
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ES7SnapshotDto(
        @JsonProperty("name") String name,
        @JsonProperty("uuid") String uuid,
        @JsonProperty("state") int state,
        @JsonProperty("version") String version,
        @JsonProperty("index_metadata_lookup") java.util.Map<String, String> indexMetadataLookup
    ) {}
    
    /**
     * DTO for individual index metadata in ES 7.x format
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ES7IndexDto(
        @JsonProperty("id") String id,
        @JsonProperty("snapshots") List<String> snapshots,
        @JsonProperty("shard_generations") List<String> shardGenerations
    ) {}
}
