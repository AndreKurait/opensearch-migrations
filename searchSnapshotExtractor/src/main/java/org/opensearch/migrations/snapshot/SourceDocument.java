package org.opensearch.migrations.snapshot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a source document extracted from a snapshot.
 * Contains the document ID and the source JSON content.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceDocument {
    
    /**
     * The document ID (_id field in Elasticsearch/OpenSearch)
     */
    private String id;
    
    /**
     * The document type (_type field in Elasticsearch/OpenSearch)
     * This is mainly relevant for ES versions < 7.0
     */
    private String type;
    
    /**
     * The source JSON content of the document
     */
    private String source;
    
    /**
     * The routing value if custom routing was used
     */
    private String routing;
    
    /**
     * The index name this document belongs to
     */
    private String index;
    
    /**
     * The shard number this document was stored in
     */
    private int shard;
}
