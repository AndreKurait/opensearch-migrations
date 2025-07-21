package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.io.BlobSource;

/**
 * Simplified version adapter for Elasticsearch 1.x snapshots.
 * Only parses the minimal fields needed for extraction.
 */
public class ES1VersionAdapter extends BaseVersionAdapter {
    
    public ES1VersionAdapter(BlobSource blobSource) {
        super(blobSource);
    }
    
    @Override
    public String getVersionIdentifier() {
        return "ES1";
    }
    
    @Override
    public String getSnapshotMetadataPath(SnapshotReference snapshotRef) {
        // ES1: snapshot-{name}
        return "snapshot-" + snapshotRef.getName();
    }
    
    @Override
    public String getIndexMetadataPath(SnapshotReference snapshotRef, IndexReference indexRef) {
        // ES1: indices/{indexName}/snapshot-{snapshotName}
        return String.format("indices/%s/snapshot-%s", indexRef.getName(), snapshotRef.getName());
    }
    
    @Override
    public String getShardMetadataPath(SnapshotReference snapshotRef, IndexReference indexRef, int shardId) {
        // ES1: indices/{indexName}/{shardId}/snapshot-{snapshotName}
        return String.format("indices/%s/%d/snapshot-%s", indexRef.getName(), shardId, snapshotRef.getName());
    }
    
    @Override
    public List<IndexInfo> parseSnapshotIndices(SnapshotReference snapshotRef, InputStream metadataStream) throws IOException {
        // Parse only the indices field from ES1 snapshot metadata
        @SuppressWarnings("unchecked")
        Map<String, Object> root = objectMapper.readValue(metadataStream, Map.class);
        
        List<IndexInfo> indices = new ArrayList<>();
        
        // ES1 format: {"snapshot": {"indices": ["index1", "index2"]}}
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) root.get("snapshot");
        if (snapshot != null) {
            @SuppressWarnings("unchecked")
            List<String> indexNames = (List<String>) snapshot.get("indices");
            if (indexNames != null) {
                for (String indexName : indexNames) {
                    indices.add(IndexInfo.builder()
                        .name(indexName)
                        .id(indexName) // ES1 uses name as ID
                        .snapshotUuids(List.of(snapshotRef.getName()))
                        .build());
                }
            }
        }
        
        return indices;
    }
    
    @Override
    public IndexReference parseIndexMetadata(SnapshotReference snapshotRef, String indexName) throws IOException {
        String path = String.format("indices/%s/snapshot-%s", indexName, snapshotRef.getName());
        
        // Parse only the shard count from ES1 index metadata
        @SuppressWarnings("unchecked")
        Map<String, Object> root = readJsonMetadata(path, Map.class);
        
        // ES1 format: {"{indexName}": {"settings": {"index.number_of_shards": "1"}}}
        @SuppressWarnings("unchecked")
        Map<String, Object> indexData = (Map<String, Object>) root.get(indexName);
        if (indexData != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> settings = (Map<String, Object>) indexData.get("settings");
            if (settings != null) {
                Object shardCountObj = settings.get("index.number_of_shards");
                int shardCount = parseIntegerValue(shardCountObj, 1);
                return IndexReference.of(indexName, shardCount);
            }
        }
        
        // Fallback
        return IndexReference.of(indexName, 1);
    }
    
    @Override
    public ShardMetadata readShardMetadata(SnapshotReference snapshotRef, IndexReference indexRef, int shardId) throws IOException {
        String path = getShardMetadataPath(snapshotRef, indexRef, shardId);
        
        // Parse only the files array from ES1 shard metadata
        @SuppressWarnings("unchecked")
        Map<String, Object> root = readJsonMetadata(path, Map.class);
        
        List<ShardMetadata.ShardFileInfo> files = new ArrayList<>();
        
        // ES1 format: {"files": [{"name": "__0", "physical_name": "_0.cfe", "length": 350, "checksum": "abc"}]}
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fileList = (List<Map<String, Object>>) root.get("files");
        if (fileList != null) {
            for (Map<String, Object> fileData : fileList) {
                String name = (String) fileData.get("name");
                String physicalName = (String) fileData.get("physical_name");
                Long length = parseLongValue(fileData.get("length"), 0L);
                String checksum = (String) fileData.get("checksum");
                
                files.add(ShardMetadata.ShardFileInfo.builder()
                    .name(name)
                    .physicalName(physicalName)
                    .length(length)
                    .checksum(checksum)
                    .build());
            }
        }
        
        return ShardMetadata.builder()
            .snapshotName(snapshotRef.getName())
            .indexName(indexRef.getName())
            .shardId(shardId)
            .files(files)
            .totalDocs(parseLongValue(root.get("total_docs"), 0L))
            .deletedDocs(parseLongValue(root.get("deleted_docs"), 0L))
            .build();
    }
    
    @Override
    public LuceneDocumentReader createDocumentReader(Path shardDir, IndexReference indexRef, int shardId) throws IOException {
        // ES1 uses Lucene 5
        return new LuceneDocumentReader(shardDir, indexRef.getName(), shardId);
    }
}
