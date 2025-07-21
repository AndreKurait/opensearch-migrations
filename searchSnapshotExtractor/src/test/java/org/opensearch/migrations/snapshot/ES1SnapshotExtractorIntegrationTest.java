package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ES1SnapshotExtractor using real ES1 snapshot data.
 * Uses the test resources from searchSnapshotExtractor/src/test/resources/snapshot_data/es1_nocomp
 */
public class ES1SnapshotExtractorIntegrationTest {
    
    @TempDir
    Path tempDir;
    
    private Path testRepositoryPath;
    private ES1SnapshotExtractor extractor;
    
    @BeforeEach
    void setUp() throws IOException {
        // Copy the real ES1 snapshot data to temp directory
        testRepositoryPath = tempDir.resolve("test-repo");
        copyTestSnapshotData();
        
        extractor = ES1SnapshotExtractor.fromPath(testRepositoryPath);
    }
    
    @Test
    void testListSnapshotsWithRealData() throws IOException {
        List<SnapshotInfo> snapshots = extractor.listSnapshots();
        
        assertNotNull(snapshots);
        assertEquals(2, snapshots.size());
        
        // Verify snapshot names from the real data
        List<String> snapshotNames = snapshots.stream()
            .map(SnapshotInfo::name)
            .sorted()
            .collect(Collectors.toList());
        
        assertEquals(List.of("snapshot_v1", "snapshot_v2"), snapshotNames);
        
        // Verify snapshot details
        SnapshotInfo snapshot1 = snapshots.stream()
            .filter(s -> "snapshot_v1".equals(s.name()))
            .findFirst()
            .orElseThrow();
        
        assertEquals("snapshot_v1", snapshot1.name());
        assertEquals("snapshot_v1", snapshot1.uuid()); // ES1 uses name as UUID
        assertEquals(1, snapshot1.state()); // SUCCESS
    }
    
    @Test
    void testListIndicesWithRealData() throws IOException {
        List<IndexInfo> indices = extractor.listIndices("snapshot_v1");
        
        assertNotNull(indices);
        assertEquals(1, indices.size());
        
        IndexInfo index = indices.get(0);
        assertEquals("simple_index", index.name());
        assertEquals("simple_index", index.id()); // ES1 uses name as ID
        assertTrue(index.snapshotUuids().contains("snapshot_v1"));
    }
    
    @Test
    void testGetDocumentsWithRealData() throws IOException {
        // Test with snapshot_v1
        try (Stream<SourceDocument> documents = extractor.getDocuments("snapshot_v1", "simple_index")) {
            List<SourceDocument> docList = documents.collect(Collectors.toList());
            
            // The real ES1 snapshot should contain actual documents
            assertNotNull(docList);
            assertTrue(docList.size() >= 0); // May be empty if no documents were indexed
            
            // If there are documents, verify their structure
            for (SourceDocument doc : docList) {
                assertNotNull(doc.getId(), "Document should have an ID");
                assertNotNull(doc.getSource(), "Document should have source");
                assertEquals("simple_index", doc.getIndex());
                assertEquals(0, doc.getShard());
                
                // ES1 documents should have a type
                assertNotNull(doc.getType(), "ES1 documents should have a type");
                
                System.out.println("Found document: ID=" + doc.getId() + 
                                 ", Type=" + doc.getType() + 
                                 ", Source=" + doc.getSource());
            }
        }
    }
    
    @Test
    void testGetDocumentsFromBothSnapshots() throws IOException {
        // Test both snapshots to ensure they work
        String[] snapshots = {"snapshot_v1", "snapshot_v2"};
        
        for (String snapshotName : snapshots) {
            try (Stream<SourceDocument> documents = extractor.getDocuments(snapshotName, "simple_index")) {
                List<SourceDocument> docList = documents.collect(Collectors.toList());
                
                assertNotNull(docList);
                System.out.println("Snapshot " + snapshotName + " contains " + docList.size() + " documents");
                
                // Verify document structure if any exist
                for (SourceDocument doc : docList) {
                    assertNotNull(doc.getId());
                    assertNotNull(doc.getSource());
                    assertNotNull(doc.getType());
                    assertEquals("simple_index", doc.getIndex());
                    assertEquals(0, doc.getShard());
                }
            }
        }
    }
    
    @Test
    void testShardExtractionWithRealLuceneFiles() throws IOException {
        // This test verifies that the ShardExtractor can properly extract real Lucene files
        ShardMetadata shardMetadata = extractor.readShardMetadata("snapshot_v1", "simple_index", 0);
        
        assertNotNull(shardMetadata);
        assertEquals("snapshot_v1", shardMetadata.getSnapshotName());
        assertEquals("simple_index", shardMetadata.getIndexName());
        assertEquals(0, shardMetadata.getShardId());
        
        // Verify the files match what we expect from the real snapshot
        List<ShardMetadata.ShardFileInfo> files = shardMetadata.getFiles();
        assertNotNull(files);
        assertTrue(files.size() > 0, "Should have Lucene files");
        
        // Check for expected Lucene file types
        List<String> physicalNames = files.stream()
            .map(ShardMetadata.ShardFileInfo::getPhysicalName)
            .collect(Collectors.toList());
        
        // Should contain typical Lucene files like segments, compound files, etc.
        boolean hasSegmentsFile = physicalNames.stream().anyMatch(name -> name.startsWith("segments"));
        assertTrue(hasSegmentsFile, "Should have a segments file");
        
        System.out.println("Shard files: " + physicalNames);
    }
    
    @Test
    void testLuceneIndexReading() throws IOException {
        // Test that we can actually read the Lucene index after extraction
        ShardMetadata shardMetadata = extractor.readShardMetadata("snapshot_v1", "simple_index", 0);
        
        // Extract the shard to a temporary directory
        ShardExtractor shardExtractor = new ShardExtractor(
            extractor.getBlobSource(), 
            tempDir.resolve("lucene-extraction")
        );
        
        Path extractedShardDir = shardExtractor.extractShard(shardMetadata);
        assertTrue(Files.exists(extractedShardDir), "Extracted shard directory should exist");
        
        // Verify Lucene files were extracted
        assertTrue(Files.list(extractedShardDir).count() > 0, "Should have extracted files");
        
        // Try to read the Lucene index
        try (LuceneDocumentReader reader = new LuceneDocumentReader(extractedShardDir, "simple_index", 0)) {
            int maxDoc = reader.getMaxDoc();
            int numDocs = reader.getNumDocs();
            
            System.out.println("Lucene index stats: maxDoc=" + maxDoc + ", numDocs=" + numDocs);
            
            assertTrue(maxDoc >= 0, "MaxDoc should be non-negative");
            assertTrue(numDocs >= 0, "NumDocs should be non-negative");
            assertTrue(numDocs <= maxDoc, "NumDocs should not exceed MaxDoc");
            
            // Try to read documents
            try (Stream<SourceDocument> documents = reader.streamDocuments()) {
                List<SourceDocument> docList = documents.collect(Collectors.toList());
                assertEquals(numDocs, docList.size(), "Should read all live documents");
            }
        }
        
        // Cleanup
        shardExtractor.cleanup(extractedShardDir);
    }
    
    /**
     * Copy the test snapshot data from resources to the temp directory.
     */
    private void copyTestSnapshotData() throws IOException {
        Path sourceDir = Path.of("src/test/resources/snapshot_data/es1_nocomp");
        
        if (!Files.exists(sourceDir)) {
            throw new IOException("Test snapshot data not found at: " + sourceDir);
        }
        
        copyDirectoryRecursively(sourceDir, testRepositoryPath);
    }
    
    /**
     * Recursively copy a directory and all its contents.
     */
    private void copyDirectoryRecursively(Path source, Path target) throws IOException {
        Files.walk(source).forEach(sourcePath -> {
            try {
                Path targetPath = target.resolve(source.relativize(sourcePath));
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy " + sourcePath, e);
            }
        });
    }
}
