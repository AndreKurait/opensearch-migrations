package org.opensearch.migrations.bulkload.pipeline.ir;

/**
 * Identifies a shard within a snapshot. Clean IR — no Lucene or repo-access details.
 */
public record ShardId(
    String snapshotName,
    String indexName,
    int shardNumber
) {}
