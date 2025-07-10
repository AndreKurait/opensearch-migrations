package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotRepositoryTest {

    @Test
    void testReadES710SingleSnapshot() throws IOException {
        // Use the real snapshot data in test resources
        String snapshotPath = "src/test/resources/snapshots/ES_7_10_Single";
        SnapshotRepository repository = SnapshotRepository.fromPath(snapshotPath);
        
        // Test finding the latest index file
        String latestIndexFile = repository.findLatestIndexFile();
        assertEquals("index-1", latestIndexFile);
        
        // Test reading repository metadata
        RepositoryMetadata metadata = repository.readRepositoryMetadata();
        assertNotNull(metadata);
        
        // Verify basic metadata
        assertEquals("7.9.0", metadata.getMinVersion());
        assertEquals(1, metadata.getSnapshotCount());
        assertEquals(2, metadata.getIndexCount());
        
        // Verify snapshot information
        SnapshotInfo snapshot = metadata.getSnapshots().get(0);
        assertEquals("global_state_snapshot", snapshot.getName());
        assertEquals("7_1RHMshSc6c0cuzX1NCDg", snapshot.getUuid());
        assertEquals(1, snapshot.getState());
        assertEquals("7.10.2", snapshot.getVersion());
        assertTrue(snapshot.isSuccessful());
        assertEquals("SUCCESS", snapshot.getStateDescription());
        
        // Verify index information
        IndexInfo index1 = metadata.findIndexByName("posts_2023_02_25");
        assertNotNull(index1);
        assertEquals("eQUBLj-GTUWh6FHH9ectQA", index1.getId());
        assertEquals(1, index1.getShardCount());
        assertEquals(1, index1.getSnapshotCount());
        assertTrue(index1.isInSnapshot("7_1RHMshSc6c0cuzX1NCDg"));
        assertEquals("kR2pmiZPTLODhFjMy9LK2A", index1.getShardGeneration(0));
        
        IndexInfo index2 = metadata.findIndexByName("posts_2024_01_01");
        assertNotNull(index2);
        assertEquals("TKzEIy9ASTq-FuWhogYPHw", index2.getId());
        assertEquals(1, index2.getShardCount());
        assertEquals(1, index2.getSnapshotCount());
        assertTrue(index2.isInSnapshot("7_1RHMshSc6c0cuzX1NCDg"));
        assertEquals("guSEIbPOR8SI_i1M0mOHLQ", index2.getShardGeneration(0));
        
        // Test lookup methods
        SnapshotInfo foundSnapshot = metadata.findSnapshotByUuid("7_1RHMshSc6c0cuzX1NCDg");
        assertNotNull(foundSnapshot);
        assertEquals("global_state_snapshot", foundSnapshot.getName());
        
        IndexInfo foundIndex = metadata.findIndexById("eQUBLj-GTUWh6FHH9ectQA");
        assertNotNull(foundIndex);
        assertEquals("posts_2023_02_25", foundIndex.getName());
    }
    
    @Test
    void testReadES68SingleSnapshot() throws IOException {
        // Test with a different snapshot version
        String snapshotPath = "src/test/resources/snapshots/ES_6_8_Single";
        SnapshotRepository repository = SnapshotRepository.fromPath(snapshotPath);
        
        // Test finding the latest index file
        String latestIndexFile = repository.findLatestIndexFile();
        assertEquals("index-1", latestIndexFile);
        
        // Test reading repository metadata
        RepositoryMetadata metadata = repository.readRepositoryMetadata();
        assertNotNull(metadata);
        
        // Should have at least one snapshot and some indices
        assertTrue(metadata.getSnapshotCount() > 0);
        assertTrue(metadata.getIndexCount() > 0);
        
        // Verify we can find snapshots and indices
        assertNotNull(metadata.getSnapshots());
        assertNotNull(metadata.getIndices());
    }
    
    @Test
    void testNonExistentRepository() {
        String nonExistentPath = "src/test/resources/snapshots/NonExistent";
        SnapshotRepository repository = SnapshotRepository.fromPath(nonExistentPath);
        
        assertThrows(IOException.class, () -> {
            repository.findLatestIndexFile();
        });
    }
}
