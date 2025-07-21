package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import shadow.lucene5.org.apache.lucene.analysis.Analyzer;
import shadow.lucene5.org.apache.lucene.analysis.TokenStream;
import shadow.lucene5.org.apache.lucene.analysis.Tokenizer;
import shadow.lucene5.org.apache.lucene.document.BinaryDocValuesField;
import shadow.lucene5.org.apache.lucene.document.Document;
import shadow.lucene5.org.apache.lucene.document.Field;
import shadow.lucene5.org.apache.lucene.document.StoredField;
import shadow.lucene5.org.apache.lucene.document.StringField;
import shadow.lucene5.org.apache.lucene.index.IndexWriter;
import shadow.lucene5.org.apache.lucene.index.IndexWriterConfig;
import shadow.lucene5.org.apache.lucene.store.FSDirectory;
import shadow.lucene5.org.apache.lucene.util.BytesRef;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LuceneDocumentReader with actual Lucene indices.
 */
public class LuceneDocumentReaderTest {
    
    @TempDir
    Path tempDir;
    
    private Path indexPath;
    
    @BeforeEach
    void setUp() throws IOException {
        indexPath = tempDir.resolve("test-index");
        Files.createDirectories(indexPath);
    }
    
    @Test
    void testReadDocumentsFromES1Index() throws IOException {
        // Create a simple Lucene 5 index with ES1-style documents
        createES1StyleIndex();
        
        try (LuceneDocumentReader reader = new LuceneDocumentReader(indexPath, "test-index", 0)) {
            // Test document count
            assertEquals(3, reader.getNumDocs());
            assertEquals(3, reader.getMaxDoc());
            
            // Read all documents
            try (Stream<SourceDocument> documents = reader.streamDocuments()) {
                List<SourceDocument> docList = documents.collect(Collectors.toList());
                
                assertEquals(3, docList.size());
                
                // Verify first document
                SourceDocument doc1 = docList.get(0);
                assertEquals("1", doc1.getId());
                assertEquals("type1", doc1.getType());
                assertEquals("{\"field\":\"value1\"}", doc1.getSource());
                assertEquals("test-index", doc1.getIndex());
                assertEquals(0, doc1.getShard());
                
                // Verify second document
                SourceDocument doc2 = docList.get(1);
                assertEquals("2", doc2.getId());
                assertEquals("type2", doc2.getType());
                assertEquals("{\"field\":\"value2\"}", doc2.getSource());
                
                // Verify third document with routing
                SourceDocument doc3 = docList.get(2);
                assertEquals("3", doc3.getId());
                assertEquals("type1", doc3.getType());
                assertEquals("{\"field\":\"value3\"}", doc3.getSource());
                assertEquals("custom-routing", doc3.getRouting());
            }
        }
    }
    
    @Test
    void testStreamIsLazy() throws IOException {
        createES1StyleIndex();
        
        try (LuceneDocumentReader reader = new LuceneDocumentReader(indexPath, "test-index", 0)) {
            try (Stream<SourceDocument> documents = reader.streamDocuments()) {
                // Take only first document - stream should not read all
                List<SourceDocument> firstDoc = documents.limit(1).collect(Collectors.toList());
                
                assertEquals(1, firstDoc.size());
                assertEquals("1", firstDoc.get(0).getId());
            }
        }
    }
    
    @Test
    void testEmptyIndex() throws IOException {
        // Create empty index
        try (FSDirectory directory = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(createSimpleAnalyzer()))) {
            writer.commit(); // Create empty index
        }
        
        try (LuceneDocumentReader reader = new LuceneDocumentReader(indexPath, "test-index", 0)) {
            assertEquals(0, reader.getNumDocs());
            
            try (Stream<SourceDocument> documents = reader.streamDocuments()) {
                assertEquals(0, documents.count());
            }
        }
    }
    
    @Test
    void testDocumentWithMissingSource() throws IOException {
        // Create index with document missing _source field
        try (FSDirectory directory = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(createSimpleAnalyzer()))) {
            
            Document doc = new Document();
            doc.add(new StoredField("_uid", "type1#1"));
            // No _source field
            writer.addDocument(doc);
            writer.commit();
        }
        
        try (LuceneDocumentReader reader = new LuceneDocumentReader(indexPath, "test-index", 0)) {
            try (Stream<SourceDocument> documents = reader.streamDocuments()) {
                // Document should be skipped due to missing source
                assertEquals(0, documents.count());
            }
        }
    }
    
    @Test
    void testDocumentWithMissingId() throws IOException {
        // Create index with document missing _uid field
        try (FSDirectory directory = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(createSimpleAnalyzer()))) {
            
            Document doc = new Document();
            doc.add(new StoredField("_source", new BytesRef("{\"field\":\"value\"}")));
            // No _uid field
            writer.addDocument(doc);
            writer.commit();
        }
        
        try (LuceneDocumentReader reader = new LuceneDocumentReader(indexPath, "test-index", 0)) {
            try (Stream<SourceDocument> documents = reader.streamDocuments()) {
                // Document should be skipped due to missing ID
                assertEquals(0, documents.count());
            }
        }
    }
    
    private void createES1StyleIndex() throws IOException {
        try (FSDirectory directory = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(createSimpleAnalyzer()))) {
            
            // Document 1
            Document doc1 = new Document();
            doc1.add(new StoredField("_uid", "type1#1"));
            doc1.add(new StoredField("_source", new BytesRef("{\"field\":\"value1\"}")));
            writer.addDocument(doc1);
            
            // Document 2
            Document doc2 = new Document();
            doc2.add(new StoredField("_uid", "type2#2"));
            doc2.add(new StoredField("_source", new BytesRef("{\"field\":\"value2\"}")));
            writer.addDocument(doc2);
            
            // Document 3 with routing
            Document doc3 = new Document();
            doc3.add(new StoredField("_uid", "type1#3"));
            doc3.add(new StoredField("_source", new BytesRef("{\"field\":\"value3\"}")));
            doc3.add(new StoredField("_routing", "custom-routing"));
            writer.addDocument(doc3);
            
            writer.commit();
        }
    }
    
    /**
     * Create a simple analyzer for testing that doesn't require analysis dependencies.
     */
    private Analyzer createSimpleAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                // Create a simple tokenizer that just returns the input as a single token
                Tokenizer tokenizer = new Tokenizer() {
                    private boolean done = false;
                    private String input;
                    
                    @Override
                    public boolean incrementToken() throws IOException {
                        if (done) {
                            return false;
                        }
                        done = true;
                        // For testing, we don't actually need to tokenize properly
                        return true;
                    }
                    
                    @Override
                    public void reset() throws IOException {
                        super.reset();
                        done = false;
                    }
                };
                return new TokenStreamComponents(tokenizer);
            }
        };
    }
}
