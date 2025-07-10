package org.opensearch.migrations.snapshot;

import java.util.List;

import lombok.Builder;
import lombok.Data;
import lombok.Setter;

/**
 * Represents information about an index within a snapshot repository
 */
@Data
@Builder
@Setter
public class IndexInfo {
    
    /**
     * The name of the index
     */
    private String name;
    
    /**
     * The unique ID of the index
     */
    private String id;
    
    /**
     * List of snapshot UUIDs that contain this index
     */
    private List<String> snapshotUuids;
    
    /**
     * List of shard generation identifiers for this index
     */
    private List<String> shardGenerations;
    
    /**
     * Get the number of shards for this index
     * @return the number of shards (based on shard generations)
     */
    public int getShardCount() {
        return shardGenerations != null ? shardGenerations.size() : 0;
    }
    
    /**
     * Get the number of snapshots that contain this index
     * @return the number of snapshots
     */
    public int getSnapshotCount() {
        return snapshotUuids != null ? snapshotUuids.size() : 0;
    }
    
    /**
     * Check if this index is included in a specific snapshot
     * @param snapshotUuid the UUID of the snapshot to check
     * @return true if the index is in the specified snapshot
     */
    public boolean isInSnapshot(String snapshotUuid) {
        return snapshotUuids != null && snapshotUuids.contains(snapshotUuid);
    }
    
    /**
     * Get the shard generation for a specific shard
     * @param shardId the shard ID (0-based index)
     * @return the shard generation string, or null if not found
     */
    public String getShardGeneration(int shardId) {
        if (shardGenerations == null || shardId < 0 || shardId >= shardGenerations.size()) {
            return null;
        }
        return shardGenerations.get(shardId);
    }
}
