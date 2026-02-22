package org.opensearch.migrations.bulkload.pipeline.ir;

/**
 * Lucene-agnostic document change record. This is the clean IR boundary between
 * reading (any source) and writing (any target).
 *
 * Unlike LuceneDocumentChange, this has no luceneDocNumber or other implementation-specific fields.
 * Progress tracking is handled separately via the cursor mechanism.
 */
public record DocumentChange(
    String id,
    String type,
    byte[] source,
    String routing,
    ChangeType operation
) {
    public enum ChangeType {
        INDEX,
        DELETE
    }
}
