package org.opensearch.migrations.bulkload.pipeline;

import java.nio.charset.StandardCharsets;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.sink.CollectingDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.SyntheticDocumentSource;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates N+M testing with the clean pipeline IR.
 *
 * These tests use SyntheticDocumentSource + CollectingDocumentSink — no real
 * snapshot, no real cluster, no Lucene. This is the target-side test pattern:
 * feed synthetic IR → verify behavior.
 *
 * The source-side test pattern would be: real snapshot → CollectingDocumentSink → assert IR.
 */
class MigrationPipelineTest {

    @Test
    void pipelineMigratesAllDocumentsFromSourceToSink() {
        var source = new SyntheticDocumentSource("test-index", 2, 10);
        var sink = new CollectingDocumentSink();
        var pipeline = new MigrationPipeline(source, sink, 5, 1024 * 1024);

        StepVerifier.create(pipeline.migrateIndex("test-index"))
            .thenConsumeWhile(cursor -> cursor instanceof ProgressCursor)
            .verifyComplete();

        // 2 shards × 10 docs = 20 total documents
        assertEquals(20, sink.getCollectedDocuments().size());
        // Index was created
        assertEquals(1, sink.getCreatedIndices().size());
        assertEquals("test-index", sink.getCreatedIndices().get(0).indexName());
    }

    @Test
    void pipelineBatchesDocumentsByCount() {
        var source = new SyntheticDocumentSource("test-index", 1, 12);
        var sink = new CollectingDocumentSink();
        // Batch size of 5 → 12 docs should produce 3 batches (5, 5, 2)
        var pipeline = new MigrationPipeline(source, sink, 5, Long.MAX_VALUE);

        StepVerifier.create(pipeline.migrateShard(
                source.listShards("test-index").get(0), "test-index", 0))
            .thenConsumeWhile(cursor -> true)
            .verifyComplete();

        assertEquals(12, sink.getCollectedDocuments().size());
        // Should have 3 progress cursors (one per batch)
        assertEquals(3, sink.getCursors().size());
    }

    @Test
    void pipelineHandlesResumeFromOffset() {
        var source = new SyntheticDocumentSource("test-index", 1, 10);
        var sink = new CollectingDocumentSink();
        var pipeline = new MigrationPipeline(source, sink, 100, Long.MAX_VALUE);

        // Resume from doc 5 — should only get docs 5-9
        var shardId = source.listShards("test-index").get(0);
        StepVerifier.create(pipeline.migrateShard(shardId, "test-index", 5))
            .thenConsumeWhile(cursor -> true)
            .verifyComplete();

        assertEquals(5, sink.getCollectedDocuments().size());
    }

    @Test
    void syntheticSourceProducesCorrectDocumentStructure() {
        var source = new SyntheticDocumentSource("my-index", 1, 3);
        var shardId = source.listShards("my-index").get(0);

        StepVerifier.create(source.readDocuments(shardId, 0))
            .assertNext(doc -> {
                assertEquals("my-index-0-0", doc.id());
                assertEquals(DocumentChange.ChangeType.INDEX, doc.operation());
                assertNotNull(doc.source());
                String body = new String(doc.source(), StandardCharsets.UTF_8);
                assertTrue(body.contains("\"field\":\"value-0\""));
            })
            .expectNextCount(2)
            .verifyComplete();
    }

    @Test
    void collectingSinkCapturesAllData() {
        var sink = new CollectingDocumentSink();
        var shardId = new org.opensearch.migrations.bulkload.pipeline.ir.ShardId("snap", "idx", 0);

        var batch = java.util.List.of(
            new DocumentChange("doc-1", null, "{}".getBytes(), null, DocumentChange.ChangeType.INDEX),
            new DocumentChange("doc-2", null, "{}".getBytes(), null, DocumentChange.ChangeType.INDEX)
        );

        StepVerifier.create(sink.writeBatch(shardId, "idx", batch))
            .assertNext(cursor -> {
                assertEquals(2, cursor.docsInBatch());
                assertEquals(shardId, cursor.shardId());
            })
            .verifyComplete();

        assertEquals(2, sink.getCollectedDocuments().size());
        assertEquals("doc-1", sink.getCollectedDocuments().get(0).id());
    }
}
