package org.opensearch.migrations.snapshot;

/**
 * Intermediate data format representing a snapshot reference.
 * Contains only the minimal information needed for path generation and operations.
 */
public class SnapshotReference {
    private final String name;
    private final String uuid;
    
    private SnapshotReference(String name, String uuid) {
        this.name = name;
        this.uuid = uuid;
    }
    
    public String getName() {
        return name;
    }
    
    public String getUuid() {
        return uuid;
    }
    
    public static SnapshotReference of(String name) {
        return new SnapshotReference(name, name); // ES1 uses name as UUID
    }
    
    public static SnapshotReference of(String name, String uuid) {
        return new SnapshotReference(name, uuid);
    }
    
    @Override
    public String toString() {
        return String.format("SnapshotReference{name='%s', uuid='%s'}", name, uuid);
    }
}
