package org.opensearch.migrations.snapshot;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test suite for ES1 snapshot repository format (no compression)
 * Focuses only on the simplest ES1 format without compression
 */
class ES1SnapshotRepositoryTest {

    @Test
    void testES1NoCompressionSnapshot() throws IOException {
        // Test with ES1 no compression snapshot data
        SnapshotRepository repository = SnapshotRepository.fromPath("src/test/resources/snapshot_data/es1_nocomp");
        
        // Test finding the latest index file
        String latestIndexFile = repository.findLatestIndexFile();
        assertNotNull(latestIndexFile, "Latest index file should not be null for ES1 no compression");
        assertTrue(latestIndexFile.startsWith("index"), 
            "Latest index file should start with 'index' for ES1 no compression");
        
        // Test reading repository metadata
        RepositoryMetadata metadata = repository.readRepositoryMetadata();
        assertNotNull(metadata, "Repository metadata should not be null for ES1 no compression");
        
        // Validate ES1 specific characteristics
        validateES1Characteristics(metadata);
        
        // Validate basic structure
        validateBasicStructure(metadata);
        
        // Validate snapshots
        validateSnapshots(metadata);
        
        // Validate indices
        validateIndices(metadata);
    }

    private void validateES1Characteristics(RepositoryMetadata metadata) {
        // ES1 should NOT have min_version (that's ES7+)
        assertNull(metadata.minVersion(), "ES1 should not have min_version");
    }

    private void validateBasicStructure(RepositoryMetadata metadata) {
        // Should have at least one snapshot
        assertTrue(metadata.getSnapshotCount() > 0, 
            "Should have at least one snapshot for ES1");
        
        // Should have at least one index
        assertTrue(metadata.getIndexCount() > 0, 
            "Should have at least one index for ES1");
        
        // Snapshots list should not be null
        assertNotNull(metadata.snapshots(), 
            "Snapshots list should not be null for ES1");
        
        // Indices list should not be null
        assertNotNull(metadata.indices(), 
            "Indices list should not be null for ES1");
    }

    private void validateSnapshots(RepositoryMetadata metadata) {
        for (SnapshotInfo snapshot : metadata.snapshots()) {
            // Basic snapshot validation
            assertNotNull(snapshot.name(), "Snapshot name should not be null for ES1");
            assertNotNull(snapshot.uuid(), "Snapshot UUID should not be null for ES1");
            assertFalse(snapshot.name().isEmpty(), "Snapshot name should not be empty for ES1");
            assertFalse(snapshot.uuid().isEmpty(), "Snapshot UUID should not be empty for ES1");
            
            // For ES1, UUID should be same as name (no separate UUIDs)
            assertEquals(snapshot.name(), snapshot.uuid(), 
                "ES1 snapshot UUID should be same as name");
            
            // State should be valid (0-3)
            assertTrue(snapshot.state() >= 0 && snapshot.state() <= 3, 
                "Snapshot state should be between 0-3 for ES1");
            
            // Test state description
            assertNotNull(snapshot.getStateDescription(), 
                "Snapshot state description should not be null for ES1");
            
            // Test lookup functionality
            SnapshotInfo foundSnapshot = metadata.findSnapshotByUuid(snapshot.uuid());
            assertNotNull(foundSnapshot, "Should find snapshot by UUID for ES1");
            assertEquals(snapshot.name(), foundSnapshot.name(), 
                "Found snapshot name should match for ES1");
            
            SnapshotInfo foundByName = metadata.findSnapshotByName(snapshot.name());
            assertNotNull(foundByName, "Should find snapshot by name for ES1");
            assertEquals(snapshot.uuid(), foundByName.uuid(), 
                "Found snapshot UUID should match for ES1");
        }
    }

    private void validateIndices(RepositoryMetadata metadata) {
        for (IndexInfo index : metadata.indices()) {
            // Basic index validation
            assertNotNull(index.name(), "Index name should not be null for ES1");
            assertNotNull(index.id(), "Index ID should not be null for ES1");
            assertFalse(index.name().isEmpty(), "Index name should not be empty for ES1");
            assertFalse(index.id().isEmpty(), "Index ID should not be empty for ES1");
            
            // For ES1, ID should be same as name (no separate IDs)
            assertEquals(index.name(), index.id(), 
                "ES1 index ID should be same as name");
            
            // Should have snapshot UUIDs
            assertNotNull(index.snapshotUuids(), "Index snapshot UUIDs should not be null for ES1");
            assertTrue(index.getSnapshotCount() > 0, 
                "Index should be in at least one snapshot for ES1");
            
            // ES1 should NOT have shard generations (that's ES7+)
            assertNotNull(index.shardGenerations(), "Shard generations should not be null but can be empty");
            assertTrue(index.shardGenerations().isEmpty(), 
                "ES1 should have empty shard generations");
            
            // Test lookup functionality
            IndexInfo foundIndex = metadata.findIndexById(index.id());
            assertNotNull(foundIndex, "Should find index by ID for ES1");
            assertEquals(index.name(), foundIndex.name(), 
                "Found index name should match for ES1");
            
            IndexInfo foundByName = metadata.findIndexByName(index.name());
            assertNotNull(foundByName, "Should find index by name for ES1");
            assertEquals(index.id(), foundByName.id(), 
                "Found index ID should match for ES1");
            
            // Validate snapshot relationships
            for (String snapshotUuid : index.snapshotUuids()) {
                assertTrue(index.isInSnapshot(snapshotUuid), 
                    "Index should be in snapshot " + snapshotUuid + " for ES1");
                
                // Verify the snapshot exists in metadata
                SnapshotInfo relatedSnapshot = metadata.findSnapshotByUuid(snapshotUuid);
                assertNotNull(relatedSnapshot, 
                    "Related snapshot should exist in metadata for ES1");
            }
        }
    }

    @Test
    void testES1ParserDetection() throws IOException {
        // Test that ES1 parser is correctly detected and used
        SnapshotRepository repository = SnapshotRepository.fromPath("src/test/resources/snapshot_data/es1_nocomp");
        RepositoryMetadata metadata = repository.readRepositoryMetadata();
        
        // ES1 should not have min_version (this indicates ES1 parser was used)
        assertNull(metadata.minVersion(), "ES1 snapshot should be parsed without min_version");
        
        // Verify we have some basic data
        assertTrue(metadata.getSnapshotCount() > 0, "ES1 should have snapshots");
        assertTrue(metadata.getIndexCount() > 0, "ES1 should have indices");
    }

    @Test
    void testES1SpecificFeatures() throws IOException {
        SnapshotRepository repository = SnapshotRepository.fromPath("src/test/resources/snapshot_data/es1_nocomp");
        RepositoryMetadata metadata = repository.readRepositoryMetadata();
        
        // ES1 specific validations
        assertNull(metadata.minVersion(), "ES1 should not have min_version");
        
        // Verify snapshots have ES1 characteristics
        for (SnapshotInfo snapshot : metadata.snapshots()) {
            // ES1 snapshots use name as UUID
            assertEquals(snapshot.name(), snapshot.uuid(), 
                "ES1 snapshot UUID should equal name");
        }
        
        // Verify indices have ES1 characteristics
        for (IndexInfo index : metadata.indices()) {
            // ES1 indices use name as ID
            assertEquals(index.name(), index.id(), 
                "ES1 index ID should equal name");
            
            // ES1 should not have shard generations
            assertTrue(index.shardGenerations().isEmpty(), 
                "ES1 should have empty shard generations");
        }
    }
}
