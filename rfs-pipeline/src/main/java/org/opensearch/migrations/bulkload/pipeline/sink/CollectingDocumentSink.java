package org.opensearch.migrations.bulkload.pipeline.sink;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;

import reactor.core.publisher.Mono;

/**
 * A collecting DocumentSink for testing the reading side without any real target cluster.
 *
 * This is the other half of N+M testing: source-side tests use CollectingDocumentSink
 * to capture all documents produced by a real snapshot reader, then assert on the collected data.
 */
public class CollectingDocumentSink implements DocumentSink {

    private final List<IndexMetadataSnapshot> createdIndices = new CopyOnWriteArrayList<>();
    private final List<DocumentChange> collectedDocuments = new CopyOnWriteArrayList<>();
    private final List<ProgressCursor> cursors = new CopyOnWriteArrayList<>();

    @Override
    public Mono<Void> createIndex(IndexMetadataSnapshot metadata) {
        return Mono.fromRunnable(() -> createdIndices.add(metadata));
    }

    @Override
    public Mono<ProgressCursor> writeBatch(ShardId shardId, String indexName, List<DocumentChange> batch) {
        return Mono.fromCallable(() -> {
            collectedDocuments.addAll(batch);
            long bytes = batch.stream()
                .mapToLong(d -> d.source() != null ? d.source().length : 0)
                .sum();
            int lastDoc = batch.isEmpty() ? 0 : batch.size();
            var cursor = new ProgressCursor(shardId, lastDoc, batch.size(), bytes);
            cursors.add(cursor);
            return cursor;
        });
    }

    public List<IndexMetadataSnapshot> getCreatedIndices() {
        return Collections.unmodifiableList(createdIndices);
    }

    public List<DocumentChange> getCollectedDocuments() {
        return Collections.unmodifiableList(collectedDocuments);
    }

    public List<ProgressCursor> getCursors() {
        return Collections.unmodifiableList(cursors);
    }
}
