package org.opensearch.migrations.snapshot;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Repository metadata parsed from index-N files
 */
@Builder
public record RepositoryMetadata(
    @JsonProperty("snapshots") List<SnapshotInfo> snapshots,
    @JsonProperty("indices") List<IndexInfo> indices,
    @JsonProperty("minVersion") String minVersion
) {
    
    public int getSnapshotCount() {
        return snapshots != null ? snapshots.size() : 0;
    }
    
    public int getIndexCount() {
        return indices != null ? indices.size() : 0;
    }
    
    public SnapshotInfo findSnapshotByUuid(String uuid) {
        if (snapshots == null) {
            return null;
        }
        return snapshots.stream()
            .filter(snapshot -> uuid.equals(snapshot.uuid()))
            .findFirst()
            .orElse(null);
    }
    
    public SnapshotInfo findSnapshotByName(String name) {
        if (snapshots == null) {
            return null;
        }
        return snapshots.stream()
            .filter(snapshot -> name.equals(snapshot.name()))
            .findFirst()
            .orElse(null);
    }
    
    public IndexInfo findIndexById(String id) {
        if (indices == null) {
            return null;
        }
        return indices.stream()
            .filter(index -> id.equals(index.id()))
            .findFirst()
            .orElse(null);
    }
    
    public IndexInfo findIndexByName(String name) {
        if (indices == null) {
            return null;
        }
        return indices.stream()
            .filter(index -> name.equals(index.name()))
            .findFirst()
            .orElse(null);
    }
}
