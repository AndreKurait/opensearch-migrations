package org.opensearch.migrations.bulkload.pipeline.ir;

/**
 * Progress cursor emitted by the pipeline after each batch is processed.
 * Tracks the last document processed for resumability.
 */
public record ProgressCursor(
    ShardId shardId,
    int lastDocProcessed,
    long docsInBatch,
    long bytesInBatch
) {}
