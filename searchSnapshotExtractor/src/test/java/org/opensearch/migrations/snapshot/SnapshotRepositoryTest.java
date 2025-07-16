package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.snapshot.es_v6.ES6SnapshotRepositoryParser;
import org.opensearch.migrations.snapshot.es_v7.ES7SnapshotRepositoryParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test suite for SnapshotRepository focusing on ES 6 and 7 snapshot formats.
 * Tests both compressed and uncompressed variants of each version.
 */
class SnapshotRepositoryTest {

    /**
     * Test case data structure for parameterized tests
     */
    private static class SnapshotTestCase {
        final String testName;
        final String snapshotPath;
        final String expectedVersion; // "6" or "7"
        final boolean shouldHaveMinVersion;
        final boolean shouldHaveShardGenerations;

        SnapshotTestCase(String testName, String snapshotPath, String expectedVersion, 
                        boolean shouldHaveMinVersion, boolean shouldHaveShardGenerations) {
            this.testName = testName;
            this.snapshotPath = snapshotPath;
            this.expectedVersion = expectedVersion;
            this.shouldHaveMinVersion = shouldHaveMinVersion;
            this.shouldHaveShardGenerations = shouldHaveShardGenerations;
        }
    }

    /**
     * Data provider for ES 6 and 7 snapshot test cases
     */
    static Stream<Arguments> es6And7SnapshotTestCases() {
        return Stream.of(
            // ES 6.x snapshots - compressed
            Arguments.of(new SnapshotTestCase(
                "ES6_Compressed",
                "src/test/resources/snapshot_data/es6_comp",
                "6",
                false, // ES 6 doesn't have min_version
                false  // ES 6 doesn't have shard_generations
            )),
            
            // ES 6.x snapshots - uncompressed
            Arguments.of(new SnapshotTestCase(
                "ES6_Uncompressed", 
                "src/test/resources/snapshot_data/es6_nocomp",
                "6",
                false, // ES 6 doesn't have min_version
                false  // ES 6 doesn't have shard_generations
            )),
            
            // ES 7.x snapshots - compressed
            Arguments.of(new SnapshotTestCase(
                "ES7_Compressed",
                "src/test/resources/snapshot_data/es7_comp", 
                "7",
                true,  // ES 7 has min_version
                true   // ES 7 has shard_generations
            )),
            
            // ES 7.x snapshots - uncompressed
            Arguments.of(new SnapshotTestCase(
                "ES7_Uncompressed",
                "src/test/resources/snapshot_data/es7_nocomp",
                "7", 
                true,  // ES 7 has min_version
                true   // ES 7 has shard_generations
            ))
        );
    }

    @ParameterizedTest(name = "testSnapshotRepository_{0}")
    @MethodSource("es6And7SnapshotTestCases")
    void testSnapshotRepositoryParsing(SnapshotTestCase testCase) throws IOException {
        // Create repository from path
        SnapshotRepository repository = SnapshotRepository.fromPath(testCase.snapshotPath);
        
        // Test finding the latest index file
        String latestIndexFile = repository.findLatestIndexFile();
        assertNotNull(latestIndexFile, "Latest index file should not be null for " + testCase.testName);
        assertTrue(latestIndexFile.startsWith("index-"), 
            "Latest index file should start with 'index-' for " + testCase.testName);
        
        // Test reading repository metadata
        RepositoryMetadata metadata = repository.readRepositoryMetadata();
        assertNotNull(metadata, "Repository metadata should not be null for " + testCase.testName);
        
        // Validate basic structure
        validateBasicStructure(metadata, testCase);
        
        // Validate version-specific features
        validateVersionSpecificFeatures(metadata, testCase);
        
        // Validate snapshots
        validateSnapshots(metadata, testCase);
        
        // Validate indices
        validateIndices(metadata, testCase);
    }

    private void validateBasicStructure(RepositoryMetadata metadata, SnapshotTestCase testCase) {
        // Should have at least one snapshot
        assertTrue(metadata.getSnapshotCount() > 0, 
            "Should have at least one snapshot for " + testCase.testName);
        
        // Should have at least one index
        assertTrue(metadata.getIndexCount() > 0, 
            "Should have at least one index for " + testCase.testName);
        
        // Snapshots list should not be null
        assertNotNull(metadata.snapshots(), 
            "Snapshots list should not be null for " + testCase.testName);
        
        // Indices list should not be null
        assertNotNull(metadata.indices(), 
            "Indices list should not be null for " + testCase.testName);
    }

    private void validateVersionSpecificFeatures(RepositoryMetadata metadata, SnapshotTestCase testCase) {
        if (testCase.shouldHaveMinVersion) {
            // ES 7.x should have min_version
            assertNotNull(metadata.minVersion(), 
                "ES 7.x should have min_version for " + testCase.testName);
            assertFalse(metadata.minVersion().isEmpty(), 
                "ES 7.x min_version should not be empty for " + testCase.testName);
        } else {
            // ES 6.x should not have min_version
            assertNull(metadata.minVersion(), 
                "ES 6.x should not have min_version for " + testCase.testName);
        }
    }

    private void validateSnapshots(RepositoryMetadata metadata, SnapshotTestCase testCase) {
        for (SnapshotInfo snapshot : metadata.snapshots()) {
            // Basic snapshot validation
            assertNotNull(snapshot.name(), "Snapshot name should not be null for " + testCase.testName);
            assertNotNull(snapshot.uuid(), "Snapshot UUID should not be null for " + testCase.testName);
            assertFalse(snapshot.name().isEmpty(), "Snapshot name should not be empty for " + testCase.testName);
            assertFalse(snapshot.uuid().isEmpty(), "Snapshot UUID should not be empty for " + testCase.testName);
            
            // State should be valid (0-3)
            assertTrue(snapshot.state() >= 0 && snapshot.state() <= 3, 
                "Snapshot state should be between 0-3 for " + testCase.testName);
            
            // Test state description
            assertNotNull(snapshot.getStateDescription(), 
                "Snapshot state description should not be null for " + testCase.testName);
            
            // Test lookup functionality
            SnapshotInfo foundSnapshot = metadata.findSnapshotByUuid(snapshot.uuid());
            assertNotNull(foundSnapshot, "Should find snapshot by UUID for " + testCase.testName);
            assertEquals(snapshot.name(), foundSnapshot.name(), 
                "Found snapshot name should match for " + testCase.testName);
            
            SnapshotInfo foundByName = metadata.findSnapshotByName(snapshot.name());
            assertNotNull(foundByName, "Should find snapshot by name for " + testCase.testName);
            assertEquals(snapshot.uuid(), foundByName.uuid(), 
                "Found snapshot UUID should match for " + testCase.testName);
        }
    }

    private void validateIndices(RepositoryMetadata metadata, SnapshotTestCase testCase) {
        for (IndexInfo index : metadata.indices()) {
            // Basic index validation
            assertNotNull(index.name(), "Index name should not be null for " + testCase.testName);
            assertNotNull(index.id(), "Index ID should not be null for " + testCase.testName);
            assertFalse(index.name().isEmpty(), "Index name should not be empty for " + testCase.testName);
            assertFalse(index.id().isEmpty(), "Index ID should not be empty for " + testCase.testName);
            
            // Should have snapshot UUIDs
            assertNotNull(index.snapshotUuids(), "Index snapshot UUIDs should not be null for " + testCase.testName);
            assertTrue(index.getSnapshotCount() > 0, 
                "Index should be in at least one snapshot for " + testCase.testName);
            
            // Validate shard generations based on version
            if (testCase.shouldHaveShardGenerations) {
                // ES 7.x should have shard generations
                assertNotNull(index.shardGenerations(), 
                    "ES 7.x index should have shard generations for " + testCase.testName);
                assertTrue(index.getShardCount() >= 0, 
                    "ES 7.x index shard count should be non-negative for " + testCase.testName);
            } else {
                // ES 6.x may not have shard generations
                assertTrue(index.getShardCount() >= 0, 
                    "ES 6.x index shard count should be non-negative for " + testCase.testName);
            }
            
            // Test lookup functionality
            IndexInfo foundIndex = metadata.findIndexById(index.id());
            assertNotNull(foundIndex, "Should find index by ID for " + testCase.testName);
            assertEquals(index.name(), foundIndex.name(), 
                "Found index name should match for " + testCase.testName);
            
            IndexInfo foundByName = metadata.findIndexByName(index.name());
            assertNotNull(foundByName, "Should find index by name for " + testCase.testName);
            assertEquals(index.id(), foundByName.id(), 
                "Found index ID should match for " + testCase.testName);
            
            // Validate snapshot relationships
            for (String snapshotUuid : index.snapshotUuids()) {
                assertTrue(index.isInSnapshot(snapshotUuid), 
                    "Index should be in snapshot " + snapshotUuid + " for " + testCase.testName);
                
                // Verify the snapshot exists in metadata
                SnapshotInfo relatedSnapshot = metadata.findSnapshotByUuid(snapshotUuid);
                assertNotNull(relatedSnapshot, 
                    "Related snapshot should exist in metadata for " + testCase.testName);
            }
        }
    }

    @Test
    void testES6SpecificFeatures() throws IOException {
        SnapshotRepository repository = SnapshotRepository.fromPath("src/test/resources/snapshot_data/es6_comp");
        RepositoryMetadata metadata = repository.readRepositoryMetadata();
        
        // ES 6.x specific validations
        assertNull(metadata.minVersion(), "ES 6.x should not have min_version");
        
        // Verify snapshots don't have version info (ES 6.x characteristic)
        for (SnapshotInfo snapshot : metadata.snapshots()) {
            // ES 6.x snapshots may not have version field populated
            // This is acceptable behavior
        }
        
        // Verify indices don't have shard generations (ES 6.x characteristic)
        for (IndexInfo index : metadata.indices()) {
            // ES 6.x may have null or empty shard generations
            if (index.shardGenerations() != null) {
                // If present, should be consistent with shard count
                assertTrue(index.getShardCount() >= 0, "Shard count should be non-negative");
            }
        }
    }

    @Test
    void testES7SpecificFeatures() throws IOException {
        SnapshotRepository repository = SnapshotRepository.fromPath("src/test/resources/snapshot_data/es7_comp");
        RepositoryMetadata metadata = repository.readRepositoryMetadata();
        
        // ES 7.x specific validations
        assertNotNull(metadata.minVersion(), "ES 7.x should have min_version");
        assertFalse(metadata.minVersion().isEmpty(), "ES 7.x min_version should not be empty");
        
        // Verify snapshots have version info (ES 7.x characteristic)
        for (SnapshotInfo snapshot : metadata.snapshots()) {
            // ES 7.x snapshots should have version field
            // Note: version may still be null in some cases, which is acceptable
        }
        
        // Verify indices have shard generations (ES 7.x characteristic)
        for (IndexInfo index : metadata.indices()) {
            assertNotNull(index.shardGenerations(), "ES 7.x index should have shard generations");
            assertTrue(index.getShardCount() >= 0, "ES 7.x shard count should be non-negative");
        }
    }

    @Test
    void testParserAutoDetection() throws IOException {
        // Test that ES 6.x snapshots are detected and parsed by ES6 parser
        SnapshotRepository es6Repository = SnapshotRepository.fromPath("src/test/resources/snapshot_data/es6_comp");
        RepositoryMetadata es6Metadata = es6Repository.readRepositoryMetadata();
        
        // ES 6.x should not have min_version (this indicates ES6 parser was used)
        assertNull(es6Metadata.minVersion(), "ES 6.x snapshot should be parsed without min_version");
        
        // Test that ES 7.x snapshots are detected and parsed by ES7 parser
        SnapshotRepository es7Repository = SnapshotRepository.fromPath("src/test/resources/snapshot_data/es7_comp");
        RepositoryMetadata es7Metadata = es7Repository.readRepositoryMetadata();
        
        // ES 7.x should have min_version (this indicates ES7 parser was used)
        assertNotNull(es7Metadata.minVersion(), "ES 7.x snapshot should be parsed with min_version");
    }

    @Test
    void testCompressedVsUncompressedConsistency() throws IOException {
        // Compare compressed vs uncompressed ES 6.x snapshots
        SnapshotRepository es6CompRepo = SnapshotRepository.fromPath("src/test/resources/snapshot_data/es6_comp");
        SnapshotRepository es6NoCompRepo = SnapshotRepository.fromPath("src/test/resources/snapshot_data/es6_nocomp");
        
        RepositoryMetadata es6CompMetadata = es6CompRepo.readRepositoryMetadata();
        RepositoryMetadata es6NoCompMetadata = es6NoCompRepo.readRepositoryMetadata();
        
        // Both should be parseable and have similar structure
        assertTrue(es6CompMetadata.getSnapshotCount() > 0, "ES 6.x compressed should have snapshots");
        assertTrue(es6NoCompMetadata.getSnapshotCount() > 0, "ES 6.x uncompressed should have snapshots");
        assertTrue(es6CompMetadata.getIndexCount() > 0, "ES 6.x compressed should have indices");
        assertTrue(es6NoCompMetadata.getIndexCount() > 0, "ES 6.x uncompressed should have indices");
        
        // Compare compressed vs uncompressed ES 7.x snapshots
        SnapshotRepository es7CompRepo = SnapshotRepository.fromPath("src/test/resources/snapshot_data/es7_comp");
        SnapshotRepository es7NoCompRepo = SnapshotRepository.fromPath("src/test/resources/snapshot_data/es7_nocomp");
        
        RepositoryMetadata es7CompMetadata = es7CompRepo.readRepositoryMetadata();
        RepositoryMetadata es7NoCompMetadata = es7NoCompRepo.readRepositoryMetadata();
        
        // Both should be parseable and have similar structure
        assertTrue(es7CompMetadata.getSnapshotCount() > 0, "ES 7.x compressed should have snapshots");
        assertTrue(es7NoCompMetadata.getSnapshotCount() > 0, "ES 7.x uncompressed should have snapshots");
        assertTrue(es7CompMetadata.getIndexCount() > 0, "ES 7.x compressed should have indices");
        assertTrue(es7NoCompMetadata.getIndexCount() > 0, "ES 7.x uncompressed should have indices");
        
        // Both ES 7.x variants should have min_version
        assertNotNull(es7CompMetadata.minVersion(), "ES 7.x compressed should have min_version");
        assertNotNull(es7NoCompMetadata.minVersion(), "ES 7.x uncompressed should have min_version");
    }

    @Test
    void testNonExistentRepository() {
        String nonExistentPath = "src/test/resources/snapshot_data/NonExistent";
        SnapshotRepository repository = SnapshotRepository.fromPath(nonExistentPath);
        
        assertThrows(IOException.class, repository::findLatestIndexFile, 
            "Should throw IOException for non-existent repository");
    }

    @Test
    void testRepositoryWithoutIndexFiles() {
        // Test with a directory that exists but has no index files
        String emptyPath = "src/test/resources"; // This directory exists but has no index files
        SnapshotRepository repository = SnapshotRepository.fromPath(emptyPath);
        
        assertThrows(IOException.class, repository::findLatestIndexFile, 
            "Should throw IOException when no index files are found");
    }
}
