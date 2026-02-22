package org.opensearch.migrations.bulkload.pipeline.ir;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lucene-agnostic metadata snapshot for an index. Clean IR boundary.
 *
 * Unlike the existing IndexMetadata interface, this is a simple data carrier
 * with no factory methods or repo-access logic baked in.
 */
public record IndexMetadataSnapshot(
    String indexName,
    int numberOfShards,
    int numberOfReplicas,
    ObjectNode mappings,
    ObjectNode settings,
    ObjectNode aliases
) {}
