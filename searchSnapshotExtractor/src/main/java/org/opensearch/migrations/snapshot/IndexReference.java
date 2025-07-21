package org.opensearch.migrations.snapshot;

/**
 * Intermediate data format representing an index reference.
 * Contains only the minimal information needed for path generation and operations.
 */
public class IndexReference {
    private final String name;
    private final int shardCount;
    
    private IndexReference(String name, int shardCount) {
        this.name = name;
        this.shardCount = shardCount;
    }
    
    public String getName() {
        return name;
    }
    
    public int getShardCount() {
        return shardCount;
    }
    
    public static IndexReference of(String name, int shardCount) {
        return new IndexReference(name, shardCount);
    }
    
    @Override
    public String toString() {
        return String.format("IndexReference{name='%s', shardCount=%d}", name, shardCount);
    }
}
