package org.opensearch.migrations.snapshot;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Snapshot information within a repository
 */
@Builder
public record SnapshotInfo(
    @JsonProperty("name") String name,
    @JsonProperty("uuid") String uuid,
    @JsonProperty("state") int state,
    @JsonProperty("version") String version
) {
    
    public boolean isSuccessful() {
        return state == 1; // SUCCESS state
    }
    
    public boolean isInProgress() {
        return state == 0; // IN_PROGRESS state
    }
    
    public String getStateDescription() {
        return switch (state) {
            case 0 -> "IN_PROGRESS";
            case 1 -> "SUCCESS";
            case 2 -> "FAILED";
            case 3 -> "PARTIAL";
            default -> "UNKNOWN(" + state + ")";
        };
    }
}
