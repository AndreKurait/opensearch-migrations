package org.opensearch.migrations.bulkload.pipeline;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneAdapter;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the adapter that bridges existing Lucene types to the clean pipeline IR.
 */
class LuceneAdapterTest {

    @Test
    void convertsIndexOperation() {
        var luceneDoc = new LuceneDocumentChange(
            42,                          // luceneDocNumber — dropped in clean IR
            "doc-1",
            "_doc",
            "{\"field\":\"value\"}".getBytes(),
            "custom-routing",
            DocumentChangeType.INDEX
        );

        DocumentChange clean = LuceneAdapter.fromLucene(luceneDoc);

        assertEquals("doc-1", clean.id());
        assertEquals("_doc", clean.type());
        assertArrayEquals("{\"field\":\"value\"}".getBytes(), clean.source());
        assertEquals("custom-routing", clean.routing());
        assertEquals(DocumentChange.ChangeType.INDEX, clean.operation());
        // Note: luceneDocNumber is intentionally NOT in the clean IR
    }

    @Test
    void convertsDeleteOperation() {
        var luceneDoc = new LuceneDocumentChange(
            99, "doc-2", null, null, null, DocumentChangeType.DELETE
        );

        DocumentChange clean = LuceneAdapter.fromLucene(luceneDoc);

        assertEquals("doc-2", clean.id());
        assertNull(clean.source());
        assertEquals(DocumentChange.ChangeType.DELETE, clean.operation());
    }
}
