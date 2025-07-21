package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import shadow.lucene5.org.apache.lucene.document.Document;
import shadow.lucene5.org.apache.lucene.index.DirectoryReader;
import shadow.lucene5.org.apache.lucene.index.IndexableField;
import shadow.lucene5.org.apache.lucene.index.LeafReaderContext;
import shadow.lucene5.org.apache.lucene.store.FSDirectory;
import shadow.lucene5.org.apache.lucene.util.Bits;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads documents from a Lucene index extracted from a snapshot.
 * Simplified version of RFS's LuceneReader for ES1 compatibility.
 */
@Slf4j
public class LuceneDocumentReader implements AutoCloseable {
    
    private final DirectoryReader reader;
    private final String indexName;
    private final int shardId;
    
    /**
     * Create a new document reader for the given Lucene index directory.
     * 
     * @param indexPath Path to the Lucene index directory
     * @param indexName Name of the index (for document metadata)
     * @param shardId Shard number (for document metadata)
     * @throws IOException if the index cannot be opened
     */
    public LuceneDocumentReader(Path indexPath, String indexName, int shardId) throws IOException {
        this.reader = DirectoryReader.open(FSDirectory.open(indexPath));
        this.indexName = indexName;
        this.shardId = shardId;
        
        log.info("Opened Lucene index at {} with {} documents", indexPath, reader.maxDoc());
    }
    
    /**
     * Stream all live documents from the index.
     * 
     * @return Stream of source documents
     */
    public Stream<SourceDocument> streamDocuments() {
        List<LeafReaderContext> leaves = reader.leaves();
        
        if (leaves.isEmpty()) {
            return Stream.empty();
        }
        
        // Create a spliterator that will iterate through all documents
        Spliterator<SourceDocument> spliterator = new Spliterators.AbstractSpliterator<>(
                Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            
            private int currentLeaf = 0;
            private int currentDoc = 0;
            private int docBase = 0;
            
            @Override
            public boolean tryAdvance(java.util.function.Consumer<? super SourceDocument> action) {
                try {
                    while (currentLeaf < leaves.size()) {
                        LeafReaderContext context = leaves.get(currentLeaf);
                        Bits liveDocs = context.reader().getLiveDocs();
                        
                        while (currentDoc < context.reader().maxDoc()) {
                            // Check if document is live (not deleted)
                            if (liveDocs == null || liveDocs.get(currentDoc)) {
                                Document doc = context.reader().document(currentDoc);
                                SourceDocument sourceDoc = extractSourceDocument(doc);
                                
                                if (sourceDoc != null) {
                                    currentDoc++;
                                    action.accept(sourceDoc);
                                    return true;
                                }
                            }
                            currentDoc++;
                        }
                        
                        // Move to next leaf
                        currentLeaf++;
                        if (currentLeaf < leaves.size()) {
                            docBase += context.reader().maxDoc();
                            currentDoc = 0;
                        }
                    }
                    
                    return false;
                } catch (IOException e) {
                    throw new RuntimeException("Error reading document", e);
                }
            }
        };
        
        return StreamSupport.stream(spliterator, false)
                          .onClose(this::closeQuietly);
    }
    
    private SourceDocument extractSourceDocument(Document doc) {
        String id = null;
        String type = null;
        String source = null;
        String routing = null;
        
        for (IndexableField field : doc.getFields()) {
            String fieldName = field.name();
            
            switch (fieldName) {
                case "_uid":
                    // ES1 format: type#id
                    String uid = field.stringValue();
                    if (uid != null) {
                        String[] parts = uid.split("#", 2);
                        if (parts.length == 2) {
                            type = parts[0];
                            id = parts[1];
                        }
                    }
                    break;
                    
                case "_id":
                    // Fallback for newer formats
                    id = field.stringValue();
                    break;
                    
                case "_type":
                    type = field.stringValue();
                    break;
                    
                case "_source":
                    source = field.binaryValue().utf8ToString();
                    break;
                    
                case "_routing":
                    routing = field.stringValue();
                    break;
            }
        }
        
        if (id == null || source == null) {
            log.warn("Skipping document with missing id or source.");
            log.atDebug()
                .setMessage("Skipping document with missing id or source. ID: {}. Source: {}")
                .addArgument(id)
                .addArgument(source)
                .log();
            return null;
        }
        
        return SourceDocument.builder()
                .id(id)
                .type(type)
                .source(source)
                .routing(routing)
                .index(indexName)
                .shard(shardId)
                .build();
    }
    
    @Override
    public void close() throws IOException {
        reader.close();
    }
    
    private void closeQuietly() {
        try {
            close();
        } catch (IOException e) {
            log.warn("Error closing reader: {}", e.getMessage());
        }
    }
    
    /**
     * Get the total number of documents in the index (including deleted).
     * 
     * @return Total document count
     */
    public int getMaxDoc() {
        return reader.maxDoc();
    }
    
    /**
     * Get the number of live (non-deleted) documents in the index.
     * 
     * @return Live document count
     */
    public int getNumDocs() {
        return reader.numDocs();
    }
}
