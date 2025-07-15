package org.opensearch.migrations.snapshot;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Index information within a snapshot repository
 */
@Builder
public record IndexInfo(
    @JsonProperty("name") String name,
    @JsonProperty("id") String id,
    @JsonProperty("snapshotUuids") List<String> snapshotUuids,
    @JsonProperty("shardGenerations") List<String> shardGenerations
) {
    
    public int getShardCount() {
        return shardGenerations != null ? shardGenerations.size() : 0;
    }
    
    public int getSnapshotCount() {
        return snapshotUuids != null ? snapshotUuids.size() : 0;
    }
    
    public boolean isInSnapshot(String snapshotUuid) {
        return snapshotUuids != null && snapshotUuids.contains(snapshotUuid);
    }
    
    public String getShardGeneration(int shardId) {
        if (shardGenerations == null || shardId < 0 || shardId >= shardGenerations.size()) {
            return null;
        }
        return shardGenerations.get(shardId);
    }
}
