package org.opensearch.migrations.snapshot;

import lombok.Builder;
import lombok.Data;

/**
 * Represents information about a snapshot within a repository
 */
@Data
@Builder
public class SnapshotInfo {
    
    /**
     * The name of the snapshot
     */
    private String name;
    
    /**
     * The UUID of the snapshot
     */
    private String uuid;
    
    /**
     * The state of the snapshot (1 = SUCCESS, 0 = IN_PROGRESS, etc.)
     */
    private int state;
    
    /**
     * The Elasticsearch/OpenSearch version that created this snapshot
     */
    private String version;
    
    /**
     * Check if this snapshot is in a successful state
     * @return true if the snapshot state indicates success
     */
    public boolean isSuccessful() {
        return state == 1; // SUCCESS state
    }
    
    /**
     * Check if this snapshot is in progress
     * @return true if the snapshot is still in progress
     */
    public boolean isInProgress() {
        return state == 0; // IN_PROGRESS state
    }
    
    /**
     * Get a human-readable description of the snapshot state
     * @return string representation of the state
     */
    public String getStateDescription() {
        switch (state) {
            case 0:
                return "IN_PROGRESS";
            case 1:
                return "SUCCESS";
            case 2:
                return "FAILED";
            case 3:
                return "PARTIAL";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }
}
