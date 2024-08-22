package com.rfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.rfs.common.DocumentReindexer;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.OpenSearchClient;
import com.rfs.tracing.IRfsContexts;
import java.util.function.Supplier;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.IndexReader;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;

@Slf4j
public class PerformanceVerificationTest {

    @Test
    void testDocumentBuffering() throws Exception {
        // Create an in-memory directory for the test
        ByteBuffersDirectory inMemoryDir = new ByteBuffersDirectory();

        for (int segment = 0; segment < 5; segment++) {
            // Create and populate the in-memory index
            IndexWriterConfig config = new IndexWriterConfig();
            try (IndexWriter writer = new IndexWriter(inMemoryDir, config)) {
                for (int i = 0; i < 100_000; i++) {
                    Document doc = new Document();
                    String id = "doc" + i;
                    doc.add(new StoredField("_id", new BytesRef(id)));
                    doc.add(new StoredField("_source", new BytesRef("{\"field\":\"value\"}")));
                    writer.addDocument(doc);
                }
                writer.commit();
            }
        }

        // Create a real DirectoryReader using the in-memory index
        DirectoryReader realReader = DirectoryReader.open(inMemoryDir);

        // Create a custom LuceneDocumentsReader for testing
        AtomicInteger ingestedDocuments = new AtomicInteger(0);
        LuceneDocumentsReader reader = new LuceneDocumentsReader(Paths.get("dummy"), true, "dummy_field") {
            @Override
            protected DirectoryReader getReader() {
                return realReader;
            }

            @Override
            protected Document getDocument(IndexReader reader, int docId, boolean isLive) {
                try {
                    return super.getDocument(reader, docId, isLive);
                } finally {
                    ingestedDocuments.incrementAndGet();
                }
            }
        };

        // Create a mock OpenSearchClient with a pause
        AtomicInteger sentDocuments = new AtomicInteger(0);
        CountDownLatch pauseLatch = new CountDownLatch(1);
        OpenSearchClient mockClient = mock(OpenSearchClient.class);
        when(mockClient.sendBulkRequest(anyString(), anyList(), any())).thenAnswer(invocation -> {
            List<DocumentReindexer.BulkDocSection> docs = invocation.getArgument(1);
            return Mono.fromCallable(() -> {
                sentDocuments.addAndGet(docs.size());
                pauseLatch.await(); // Pause here
                return null;
            });
        });

        // Create DocumentReindexer
        int maxDocsPerBulkRequest = 1000;
        long maxBytesPerBulkRequest = Long.MAX_VALUE; // No Limit on Size
        int maxConcurrentWorkItems = 10;
        DocumentReindexer reindexer = new DocumentReindexer(mockClient, maxDocsPerBulkRequest, maxBytesPerBulkRequest, maxConcurrentWorkItems);

        // Create a mock IDocumentReindexContext
        IDocumentMigrationContexts.IDocumentReindexContext mockContext = mock(IDocumentMigrationContexts.IDocumentReindexContext.class);
        when(mockContext.createBulkRequest()).thenReturn(mock(IRfsContexts.IRequestContext.class));

        // Start reindexing in a separate thread
        Thread reindexThread = new Thread(() -> {
            reindexer.reindex("test-index", reader.readDocuments(), mockContext).block();
        });
        reindexThread.start();

        // Wait for some time to allow buffering to occur
        Thread.sleep(500);

        // Check the number of ingested documents
        int ingestedDocs = ingestedDocuments.get();
        int sentDocs = sentDocuments.get();

        // Release the pause and wait for the reindex to complete
        pauseLatch.countDown();
        reindexThread.join(10000);

        // Assert that we had buffered expected number of documents
        int bufferedDocs = ingestedDocs - sentDocs;

        log.info("In Flight Docs: {}, Buffered Docs: {}", sentDocs, bufferedDocs);
        int expectedSentDocs = maxDocsPerBulkRequest * maxConcurrentWorkItems;
        assertEquals(expectedSentDocs, sentDocs, "Expected sent docs to equal maxDocsPerBulkRequest * maxConcurrentWorkItems");
//        assertTrue(bufferedDocs >= inFlightDocuments * 5 && bufferedDocs <= inFlightDocuments * 10, "Expected buffer between 5x and 10x, was " + (double)bufferedDocs/inFlightDocuments);
        int strictExpectedBufferedDocs = (maxConcurrentWorkItems + 1) * maxDocsPerBulkRequest + 3; // 1 bulk for each sending thread plus 1 buffer + 3 docs in different steps' queues

        assertEquals(strictExpectedBufferedDocs, bufferedDocs);

        // Verify the total number of ingested documents
        assertEquals(500_000, ingestedDocuments.get(), "Not all documents were ingested");
        assertEquals(500_000, sentDocuments.get(), "Not all documents were sent");

    }
}
