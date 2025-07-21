package org.opensearch.migrations.snapshot;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata about a shard in a snapshot.
 * Contains information needed to extract the shard's Lucene files.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShardMetadata {
    
    /**
     * The snapshot name/id this shard belongs to
     */
    private String snapshotName;
    
    /**
     * The index name this shard belongs to
     */
    private String indexName;
    
    /**
     * The shard number (0-based)
     */
    private int shardId;
    
    /**
     * List of files that make up this shard
     */
    private List<ShardFileInfo> files;
    
    /**
     * Total number of documents in this shard
     */
    private long totalDocs;
    
    /**
     * Number of deleted documents in this shard
     */
    private long deletedDocs;
    
    /**
     * Information about a file in the shard
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShardFileInfo {
        /**
         * The blob name in the snapshot repository
         */
        private String name;
        
        /**
         * The physical Lucene file name
         */
        private String physicalName;
        
        /**
         * The file length in bytes
         */
        private long length;
        
        /**
         * The checksum of the file
         */
        private String checksum;
        
        /**
         * Part number if this is a multi-part file
         */
        private Integer partNumber;
        
        /**
         * Total size across all parts if this is a multi-part file
         */
        private Long totalSize;
    }
}
