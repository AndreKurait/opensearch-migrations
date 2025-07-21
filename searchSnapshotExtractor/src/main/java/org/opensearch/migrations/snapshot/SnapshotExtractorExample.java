package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * Example demonstrating how to use the simplified SnapshotExtractor interface.
 * This shows the three main operations: listing snapshots, listing indices, and extracting documents.
 */
public class SnapshotExtractorExample {
    
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: SnapshotExtractorExample <repository-path>");
            System.exit(1);
        }
        
        Path repositoryPath = Paths.get(args[0]);
        
        // Create the extractor
        ES1SnapshotExtractor extractor = ES1SnapshotExtractor.fromPath(repositoryPath);
        
        try {
            // 1. List all snapshots
            System.out.println("=== Listing Snapshots ===");
            List<SnapshotInfo> snapshots = extractor.listSnapshots();
            
            for (SnapshotInfo snapshot : snapshots) {
                System.out.printf("Snapshot: %s (UUID: %s, State: %s)%n", 
                    snapshot.name(), 
                    snapshot.uuid(), 
                    snapshot.state());
            }
            
            if (snapshots.isEmpty()) {
                System.out.println("No snapshots found in repository");
                return;
            }
            
            // Use the first snapshot for demonstration
            String snapshotName = snapshots.get(0).name();
            System.out.printf("%nUsing snapshot: %s%n", snapshotName);
            
            // 2. List indices in the snapshot
            System.out.println("\n=== Listing Indices ===");
            List<IndexInfo> indices = extractor.listIndices(snapshotName);
            
            for (IndexInfo index : indices) {
                System.out.printf("Index: %s (ID: %s)%n", 
                    index.name(), 
                    index.id());
            }
            
            if (indices.isEmpty()) {
                System.out.println("No indices found in snapshot");
                return;
            }
            
            // Use the first index for demonstration
            String indexName = indices.get(0).name();
            System.out.printf("%nExtracting documents from index: %s%n", indexName);
            
            // 3. Stream documents from the index
            System.out.println("\n=== Extracting Documents ===");
            
            try (Stream<SourceDocument> documents = extractor.getDocuments(snapshotName, indexName)) {
                // Process documents - limit to first 10 for demo
                documents.limit(10).forEach(doc -> {
                    System.out.printf("Document ID: %s%n", doc.getId());
                    System.out.printf("  Type: %s%n", doc.getType());
                    System.out.printf("  Index: %s%n", doc.getIndex());
                    System.out.printf("  Shard: %d%n", doc.getShard());
                    if (doc.getRouting() != null) {
                        System.out.printf("  Routing: %s%n", doc.getRouting());
                    }
                    System.out.printf("  Source: %s%n", 
                        doc.getSource().length() > 100 
                            ? doc.getSource().substring(0, 100) + "..." 
                            : doc.getSource());
                    System.out.println();
                });
            }
            
            // Count total documents (creates a new stream)
            System.out.println("Counting total documents...");
            try (Stream<SourceDocument> countStream = extractor.getDocuments(snapshotName, indexName)) {
                long totalDocs = countStream.count();
                System.out.printf("Total documents in %s: %d%n", indexName, totalDocs);
            }
            
        } finally {
            // Clean up temporary files
            extractor.cleanup();
        }
    }
    
    /**
     * Example of processing documents in batches.
     */
    public static void processBatch(ES1SnapshotExtractor extractor, 
                                  String snapshotName, 
                                  String indexName,
                                  int batchSize) throws IOException {
        
        try (Stream<SourceDocument> documents = extractor.getDocuments(snapshotName, indexName)) {
            final int[] counter = {0};
            final int[] batchCounter = {0};
            
            documents.forEach(doc -> {
                // Process document
                counter[0]++;
                
                // Check if batch is complete
                if (counter[0] % batchSize == 0) {
                    batchCounter[0]++;
                    System.out.printf("Processed batch %d (%d documents)%n", 
                        batchCounter[0], counter[0]);
                    
                    // Here you could flush to a target system, commit a transaction, etc.
                }
            });
            
            System.out.printf("Total documents processed: %d%n", counter[0]);
        }
    }
    
    /**
     * Example of filtering documents by type.
     */
    public static void filterByType(ES1SnapshotExtractor extractor,
                                  String snapshotName,
                                  String indexName,
                                  String targetType) throws IOException {
        
        try (Stream<SourceDocument> documents = extractor.getDocuments(snapshotName, indexName)) {
            long count = documents
                .filter(doc -> targetType.equals(doc.getType()))
                .count();
            
            System.out.printf("Found %d documents of type '%s'%n", count, targetType);
        }
    }
}
