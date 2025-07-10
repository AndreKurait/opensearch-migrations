package org.opensearch.migrations.snapshot;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents the metadata of a snapshot repository, parsed from the index-N files
 */
@Data
@Builder
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RepositoryMetadata {
    
    /**
     * List of snapshots in this repository
     */
    private List<SnapshotInfo> snapshots;
    
    /**
     * List of indices in this repository
     */
    private List<IndexInfo> indices;
    
    /**
     * Minimum version required to read this repository
     */
    private String minVersion;
    
    /**
     * Get the number of snapshots in this repository
     * @return the number of snapshots
     */
    public int getSnapshotCount() {
        return snapshots != null ? snapshots.size() : 0;
    }
    
    /**
     * Get the number of indices in this repository
     * @return the number of indices
     */
    public int getIndexCount() {
        return indices != null ? indices.size() : 0;
    }
    
    /**
     * Find a snapshot by UUID
     * @param uuid the snapshot UUID to search for
     * @return the SnapshotInfo if found, null otherwise
     */
    public SnapshotInfo findSnapshotByUuid(String uuid) {
        if (snapshots == null) {
            return null;
        }
        return snapshots.stream()
            .filter(snapshot -> uuid.equals(snapshot.getUuid()))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Find a snapshot by name
     * @param name the snapshot name to search for
     * @return the SnapshotInfo if found, null otherwise
     */
    public SnapshotInfo findSnapshotByName(String name) {
        if (snapshots == null) {
            return null;
        }
        return snapshots.stream()
            .filter(snapshot -> name.equals(snapshot.getName()))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Find an index by ID
     * @param id the index ID to search for
     * @return the IndexInfo if found, null otherwise
     */
    public IndexInfo findIndexById(String id) {
        if (indices == null) {
            return null;
        }
        return indices.stream()
            .filter(index -> id.equals(index.getId()))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Find an index by name
     * @param name the index name to search for
     * @return the IndexInfo if found, null otherwise
     */
    public IndexInfo findIndexByName(String name) {
        if (indices == null) {
            return null;
        }
        return indices.stream()
            .filter(index -> name.equals(index.getName()))
            .findFirst()
            .orElse(null);
    }
}
