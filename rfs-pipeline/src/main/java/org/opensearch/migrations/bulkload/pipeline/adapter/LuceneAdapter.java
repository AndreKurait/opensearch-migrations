package org.opensearch.migrations.bulkload.pipeline.adapter;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;

/**
 * Converts between existing Lucene-specific types and the clean pipeline IR.
 *
 * This adapter is the bridge between the existing codebase and the clean pipeline.
 * It lives in the adapter package — the pipeline core never imports Lucene types.
 */
public final class LuceneAdapter {

    private LuceneAdapter() {}

    /** Convert a LuceneDocumentChange to a clean DocumentChange. */
    public static DocumentChange fromLucene(LuceneDocumentChange luceneDoc) {
        return new DocumentChange(
            luceneDoc.getId(),
            luceneDoc.getType(),
            luceneDoc.getSource(),
            luceneDoc.getRouting(),
            mapChangeType(luceneDoc.getOperation())
        );
    }

    private static DocumentChange.ChangeType mapChangeType(DocumentChangeType luceneType) {
        return switch (luceneType) {
            case INDEX -> DocumentChange.ChangeType.INDEX;
            case DELETE -> DocumentChange.ChangeType.DELETE;
        };
    }
}
