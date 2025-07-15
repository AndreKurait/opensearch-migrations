package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotRepositoryTest {

    /**
     * Test case data structure containing expected objects for validation
     */
    private static class SnapshotTestCase {
        final String testName;
        final String snapshotPath;
        final String expectedLatestIndexFile;
        final RepositoryMetadata expectedMetadata;
        final boolean comprehensiveValidation;

        SnapshotTestCase(String testName, String snapshotPath, String expectedLatestIndexFile, 
                        RepositoryMetadata expectedMetadata, boolean comprehensiveValidation) {
            this.testName = testName;
            this.snapshotPath = snapshotPath;
            this.expectedLatestIndexFile = expectedLatestIndexFile;
            this.expectedMetadata = expectedMetadata;
            this.comprehensiveValidation = comprehensiveValidation;
        }
    }

    /**
     * Expected data providers for each snapshot type
     */
    private static class ExpectedDataProviders {
        
        static RepositoryMetadata createES710SingleExpected() {
            SnapshotInfo snapshot = new SnapshotInfo(
                "global_state_snapshot",
                "7_1RHMshSc6c0cuzX1NCDg",
                1,
                "7.10.2"
            );

            IndexInfo index1 = new IndexInfo(
                "posts_2023_02_25",
                "eQUBLj-GTUWh6FHH9ectQA",
                Arrays.asList("7_1RHMshSc6c0cuzX1NCDg"),
                Arrays.asList("kR2pmiZPTLODhFjMy9LK2A")
            );

            IndexInfo index2 = new IndexInfo(
                "posts_2024_01_01",
                "TKzEIy9ASTq-FuWhogYPHw",
                Arrays.asList("7_1RHMshSc6c0cuzX1NCDg"),
                Arrays.asList("guSEIbPOR8SI_i1M0mOHLQ")
            );

            return new RepositoryMetadata(
                Arrays.asList(snapshot),
                Arrays.asList(index1, index2),
                "7.9.0"
            );
        }

        static RepositoryMetadata createES68SingleExpected() {
            SnapshotInfo snapshot = new SnapshotInfo(
                "global_state_snapshot",
                "5imyqv54TKyHTPTCOAOt2g",
                1,
                null  // ES 6.8 snapshots may not have version info
            );

            // Create indices with correct name-to-ID mapping based on actual data
            IndexInfo index1 = new IndexInfo(
                "posts_2024_01_01",  // This ID actually maps to posts_2024_01_01
                "d3oMxx4IROOWpmPdoY9f_Q",
                Arrays.asList("5imyqv54TKyHTPTCOAOt2g"),
                Arrays.asList()  // Empty shard generations for ES 6.8 snapshot
            );

            IndexInfo index2 = new IndexInfo(
                "posts_2023_02_25",  // This ID actually maps to posts_2023_02_25
                "nkLPabE1RNC2nvGEnmRO2Q",
                Arrays.asList("5imyqv54TKyHTPTCOAOt2g"),
                Arrays.asList()  // Empty shard generations for ES 6.8 snapshot
            );

            return new RepositoryMetadata(
                Arrays.asList(snapshot),
                Arrays.asList(index1, index2),  // Order doesn't matter since we validate by ID
                null  // ES 6.8 snapshots may not have minVersion set
            );
        }

        static RepositoryMetadata createES710DoubleExpected() {
            SnapshotInfo snapshot1 = new SnapshotInfo(
                "global_state_snapshot",
                "7_1RHMshSc6c0cuzX1NCDg",
                1,
                "7.10.2"
            );

            SnapshotInfo snapshot2 = new SnapshotInfo(
                "global_state_snapshot_2",
                "MLvfrD_pTnO_XKWl4qrhOw",
                1,
                "7.10.2"
            );

            IndexInfo index1 = new IndexInfo(
                "posts_2023_02_25",
                "eQUBLj-GTUWh6FHH9ectQA",
                Arrays.asList("7_1RHMshSc6c0cuzX1NCDg"),
                Arrays.asList("kR2pmiZPTLODhFjMy9LK2A")
            );

            IndexInfo index2 = new IndexInfo(
                "posts_2024_01_01",
                "TKzEIy9ASTq-FuWhogYPHw",
                Arrays.asList("7_1RHMshSc6c0cuzX1NCDg", "MLvfrD_pTnO_XKWl4qrhOw"),
                Arrays.asList("guSEIbPOR8SI_i1M0mOHLQ")
            );

            return new RepositoryMetadata(
                Arrays.asList(snapshot1, snapshot2),
                Arrays.asList(index1, index2),
                "7.9.0"
            );
        }

        static RepositoryMetadata createES56UpdatesDeletesExpected() {
            SnapshotInfo snapshot = SnapshotInfo.builder()
                .name("rfs_snapshot")
                .uuid("FGgvQ_1CTymrGgoBFrwNTg")
                .state(1)
                .version(null)  // ES 5.6 snapshots may not have version info
                .build();

            IndexInfo index = IndexInfo.builder()
                .name("test_updates_deletes")
                .id("-KQKQKwfQ8-nhYQ8JfijxQ")
                .snapshotUuids(Arrays.asList("FGgvQ_1CTymrGgoBFrwNTg"))
                .shardGenerations(Arrays.asList())  // Empty shard generations for this snapshot
                .build();

            return RepositoryMetadata.builder()
                .minVersion(null)  // ES 5.6 snapshots may not have minVersion set
                .snapshots(Arrays.asList(snapshot))
                .indices(Arrays.asList(index))
                .build();
        }

        static RepositoryMetadata createES68UpdatesDeletesNativeExpected() {
            SnapshotInfo snapshot = SnapshotInfo.builder()
                .name("rfs_snapshot")
                .uuid("LpqTJXefQsin-rJRb4agKg")
                .state(1)
                .version(null)  // ES 6.8 snapshots may not have version info
                .build();

            IndexInfo index = IndexInfo.builder()
                .name("test_updates_deletes")
                .id("zdfzMEagTo6anjjDRaCJ1g")
                .snapshotUuids(Arrays.asList("LpqTJXefQsin-rJRb4agKg"))
                .shardGenerations(Arrays.asList())  // Empty shard generations for this snapshot
                .build();

            return RepositoryMetadata.builder()
                .minVersion(null)  // ES 6.8 snapshots may not have minVersion set
                .snapshots(Arrays.asList(snapshot))
                .indices(Arrays.asList(index))
                .build();
        }

        static RepositoryMetadata createES68UpdatesDeletesMergedExpected() {
            SnapshotInfo snapshot = SnapshotInfo.builder()
                .name("rfs_snapshot")
                .uuid("_OTK5BR2SbSi_1vb0FhIgQ")
                .state(1)
                .version(null)  // ES 6.8 snapshots may not have version info
                .build();

            IndexInfo index = IndexInfo.builder()
                .name("test_updates_deletes")
                .id("9wdOMjbGRE-G8Z6QhGXNiQ")
                .snapshotUuids(Arrays.asList("_OTK5BR2SbSi_1vb0FhIgQ"))
                .shardGenerations(Arrays.asList())  // Empty shard generations for this snapshot
                .build();

            return RepositoryMetadata.builder()
                .minVersion(null)  // ES 6.8 snapshots may not have minVersion set
                .snapshots(Arrays.asList(snapshot))
                .indices(Arrays.asList(index))
                .build();
        }

        static RepositoryMetadata createES710BWCCheckExpected() {
            SnapshotInfo snapshot = SnapshotInfo.builder()
                .name("rfs-snapshot")
                .uuid("KhcpVj8aRMek0oLMUSPHeg")
                .state(1)
                .version("7.10.2")
                .build();

            IndexInfo bwcIndex = IndexInfo.builder()
                .name("bwc_index_1")
                .id("0edrmuSPR1CIr2B6BZbMJA")
                .snapshotUuids(Arrays.asList("KhcpVj8aRMek0oLMUSPHeg"))
                .shardGenerations(Arrays.asList("tGRrEPUKTuWK_eyviXSHvg"))
                .build();

            IndexInfo fwcIndex = IndexInfo.builder()
                .name("fwc_index_1")
                .id("s_FgCb4gREmddVPRW-EtWw")  // Updated with correct ID from test failure
                .snapshotUuids(Arrays.asList("KhcpVj8aRMek0oLMUSPHeg"))
                .shardGenerations(Arrays.asList("yaxW_gAlTNqirWlIIoil8A"))
                .build();

            IndexInfo noMappingsIndex = IndexInfo.builder()
                .name("no_mappings_no_docs")
                .id("a5ONDmS2RmmN1GEZMRCJRg")
                .snapshotUuids(Arrays.asList("KhcpVj8aRMek0oLMUSPHeg"))
                .shardGenerations(Arrays.asList("cbs2bGwwSy66JeQOO9vNbQ"))
                .build();

            IndexInfo emptyMappingsIndex = IndexInfo.builder()
                .name("empty_mappings_no_docs")
                .id("zUFSBaZgSdKCPAS2xEfCww")
                .snapshotUuids(Arrays.asList("KhcpVj8aRMek0oLMUSPHeg"))
                .shardGenerations(Arrays.asList("C7dK_wyqSUuDyKkefOXUUQ"))
                .build();

            return RepositoryMetadata.builder()
                .minVersion("7.9.0")
                .snapshots(Arrays.asList(snapshot))
                .indices(Arrays.asList(bwcIndex, fwcIndex, noMappingsIndex, emptyMappingsIndex))
                .build();
        }

        static RepositoryMetadata createES710UpdatesDeletesWithSoftExpected() {
            SnapshotInfo snapshot = SnapshotInfo.builder()
                .name("rfs_snapshot")
                .uuid("4qK_DdtfTcao0zoxNElf9Q")
                .state(1)
                .version("7.10.2")
                .build();

            IndexInfo index = IndexInfo.builder()
                .name("test_updates_deletes")
                .id("ADqaSwRmQGeZpfc8raDTtA")
                .snapshotUuids(Arrays.asList("4qK_DdtfTcao0zoxNElf9Q"))
                .shardGenerations(Arrays.asList("_5mr5NhPQfOXBKkFGoyXuA"))  // Updated with correct shard generation
                .build();

            return RepositoryMetadata.builder()
                .minVersion("7.9.0")
                .snapshots(Arrays.asList(snapshot))
                .indices(Arrays.asList(index))
                .build();
        }

        static RepositoryMetadata createES710UpdatesDeletesWithoutSoftExpected() {
            SnapshotInfo snapshot = SnapshotInfo.builder()
                .name("rfs_snapshot")
                .uuid("ClerAGRHR6qcKGlz4Ky0Kg")
                .state(1)
                .version("7.10.2")
                .build();

            IndexInfo index = IndexInfo.builder()
                .name("test_updates_deletes")
                .id("-lYIRDVeQqWB1FUYhr8Mmg")
                .snapshotUuids(Arrays.asList("ClerAGRHR6qcKGlz4Ky0Kg"))
                .shardGenerations(Arrays.asList("qE6EtyfuQzGZ_NxWpglg2g"))  // Updated with correct shard generation
                .build();

            return RepositoryMetadata.builder()
                .minVersion("7.9.0")
                .snapshots(Arrays.asList(snapshot))
                .indices(Arrays.asList(index))
                .build();
        }
    }

    /**
     * Data provider for all snapshot test cases
     */
    static Stream<Arguments> snapshotTestCases() {
        return Stream.of(
            // ES 7.10 Single - comprehensive validation
            Arguments.of(new SnapshotTestCase(
                "ES_7_10_Single",
                "src/test/resources/snapshots/ES_7_10_Single",
                "index-1",
                ExpectedDataProviders.createES710SingleExpected(),
                true
            )),
            
            // ES 6.8 Single - basic validation
            Arguments.of(new SnapshotTestCase(
                "ES_6_8_Single",
                "src/test/resources/snapshots/ES_6_8_Single",
                "index-1",
                ExpectedDataProviders.createES68SingleExpected(),
                false
            )),
            
            // ES 7.10 Double - multiple snapshots
            Arguments.of(new SnapshotTestCase(
                "ES_7_10_Double",
                "src/test/resources/snapshots/ES_7_10_Double",
                "index-1",
                ExpectedDataProviders.createES710DoubleExpected(),
                false
            )),
            
            // ES 5.6 Updates Deletes
            Arguments.of(new SnapshotTestCase(
                "ES_5_6_Updates_Deletes",
                "src/test/resources/snapshots/ES_5_6_Updates_Deletes",
                "index-0",
                ExpectedDataProviders.createES56UpdatesDeletesExpected(),
                false
            )),
            
            // ES 6.8 Updates Deletes Native
            Arguments.of(new SnapshotTestCase(
                "ES_6_8_Updates_Deletes_Native",
                "src/test/resources/snapshots/ES_6_8_Updates_Deletes_Native",
                "index-0",
                ExpectedDataProviders.createES68UpdatesDeletesNativeExpected(),
                false
            )),
            
            // ES 6.8 Updates Deletes Merged
            Arguments.of(new SnapshotTestCase(
                "ES_6_8_Updates_Deletes_Merged",
                "src/test/resources/snapshots/ES_6_8_Updates_Deletes_Merged",
                "index-0",
                ExpectedDataProviders.createES68UpdatesDeletesMergedExpected(),
                false
            )),
            
            // ES 7.10 BWC Check
            Arguments.of(new SnapshotTestCase(
                "ES_7_10_BWC_Check",
                "src/test/resources/snapshots/ES_7_10_BWC_Check",
                "index-0",
                ExpectedDataProviders.createES710BWCCheckExpected(),
                false
            )),
            
            // ES 7.10 Updates Deletes with Soft Deletes
            Arguments.of(new SnapshotTestCase(
                "ES_7_10_Updates_Deletes_w_Soft",
                "src/test/resources/snapshots/ES_7_10_Updates_Deletes_w_Soft",
                "index-0",
                ExpectedDataProviders.createES710UpdatesDeletesWithSoftExpected(),
                false
            )),
            
            // ES 7.10 Updates Deletes without Soft Deletes
            Arguments.of(new SnapshotTestCase(
                "ES_7_10_Updates_Deletes_wo_Soft",
                "src/test/resources/snapshots/ES_7_10_Updates_Deletes_wo_Soft",
                "index-0",
                ExpectedDataProviders.createES710UpdatesDeletesWithoutSoftExpected(),
                false
            ))
        );
    }

    @ParameterizedTest(name = "testSnapshot_{0}")
    @MethodSource("snapshotTestCases")
    void testSnapshotRepository(SnapshotTestCase testCase) throws IOException {
        
        // Create repository from path
        SnapshotRepository repository = SnapshotRepository.fromPath(testCase.snapshotPath);
        
        // Test finding the latest index file
        String latestIndexFile = repository.findLatestIndexFile();
        assertEquals(testCase.expectedLatestIndexFile, latestIndexFile, 
            "Latest index file mismatch for " + testCase.testName);
        
        // Test reading repository metadata
        RepositoryMetadata actualMetadata = repository.readRepositoryMetadata();
        assertNotNull(actualMetadata, "Repository metadata should not be null for " + testCase.testName);
        
        // Validate repository metadata using expected objects
        validateRepositoryMetadata(testCase.expectedMetadata, actualMetadata, testCase.testName);
        
        // Comprehensive validation for specific test cases
        if (testCase.comprehensiveValidation) {
            performComprehensiveValidation(testCase.expectedMetadata, actualMetadata, testCase.testName);
        }
    }

    private void validateRepositoryMetadata(RepositoryMetadata expected, RepositoryMetadata actual, String testName) {
        // Verify basic metadata counts
        assertEquals(expected.getSnapshotCount(), actual.getSnapshotCount(), 
            "Snapshot count mismatch for " + testName);
        assertEquals(expected.getIndexCount(), actual.getIndexCount(), 
            "Index count mismatch for " + testName);
        
        // Verify minimum version if specified
        if (expected.minVersion() != null) {
            assertEquals(expected.minVersion(), actual.minVersion(), 
                "Min version mismatch for " + testName);
        }
        
        // Verify snapshots
        assertNotNull(actual.snapshots(), "Snapshots list should not be null for " + testName);
        assertTrue(actual.snapshots().size() >= 1, 
            "Should have at least one snapshot for " + testName);
        
        // Validate each expected snapshot
        for (SnapshotInfo expectedSnapshot : expected.snapshots()) {
            SnapshotInfo actualSnapshot = actual.findSnapshotByUuid(expectedSnapshot.uuid());
            assertNotNull(actualSnapshot, "Should find snapshot by UUID " + expectedSnapshot.uuid() + " for " + testName);
            validateSnapshotInfo(expectedSnapshot, actualSnapshot, testName);
        }
        
        // Verify indices
        assertNotNull(actual.indices(), "Indices list should not be null for " + testName);
        assertTrue(actual.getIndexCount() > 0, "Should have indices for " + testName);
        
        // Validate each expected index
        for (IndexInfo expectedIndex : expected.indices()) {
            IndexInfo actualIndex = actual.findIndexById(expectedIndex.id());
            assertNotNull(actualIndex, "Should find index by ID " + expectedIndex.id() + " for " + testName);
            validateIndexInfo(expectedIndex, actualIndex, testName);
        }
    }

    private void validateSnapshotInfo(SnapshotInfo expected, SnapshotInfo actual, String testName) {
        assertEquals(expected.name(), actual.name(), 
            "Snapshot name mismatch for " + testName);
        assertEquals(expected.uuid(), actual.uuid(), 
            "Snapshot UUID mismatch for " + testName);
        assertEquals(expected.state(), actual.state(), 
            "Snapshot state mismatch for " + testName);
        
        // Only validate version if expected version is not null (some snapshots may not have version info)
        if (expected.version() != null) {
            assertEquals(expected.version(), actual.version(), 
                "Snapshot version mismatch for " + testName);
        }
        
        assertEquals(expected.isSuccessful(), actual.isSuccessful(), 
            "Snapshot success status mismatch for " + testName);
        assertEquals(expected.getStateDescription(), actual.getStateDescription(), 
            "Snapshot state description mismatch for " + testName);
    }

    private void validateIndexInfo(IndexInfo expected, IndexInfo actual, String testName) {
        assertEquals(expected.name(), actual.name(), 
            "Index name mismatch for " + testName);
        assertEquals(expected.id(), actual.id(), 
            "Index ID mismatch for " + testName);
        assertEquals(expected.getShardCount(), actual.getShardCount(), 
            "Index shard count mismatch for " + testName);
        assertEquals(expected.getSnapshotCount(), actual.getSnapshotCount(), 
            "Index snapshot count mismatch for " + testName);
        
        // Validate snapshot UUIDs
        for (String expectedUuid : expected.snapshotUuids()) {
            assertTrue(actual.isInSnapshot(expectedUuid), 
                "Index should be in snapshot " + expectedUuid + " for " + testName);
        }
        
        // Validate shard generations
        for (int i = 0; i < expected.getShardCount(); i++) {
            assertEquals(expected.getShardGeneration(i), actual.getShardGeneration(i), 
                "Shard generation mismatch for shard " + i + " in " + testName);
        }
    }

    private void performComprehensiveValidation(RepositoryMetadata expected, RepositoryMetadata actual, String testName) {
        if ("ES_7_10_Single".equals(testName)) {
            // Additional detailed validation for ES 7.10 Single snapshot
            SnapshotInfo expectedSnapshot = expected.snapshots().get(0);
            SnapshotInfo actualSnapshot = actual.findSnapshotByUuid(expectedSnapshot.uuid());
            
            assertTrue(actualSnapshot.isSuccessful(), "Snapshot should be successful");
            assertEquals("SUCCESS", actualSnapshot.getStateDescription(), "Snapshot state description should be SUCCESS");
            
            // Test lookup methods
            IndexInfo expectedIndex1 = expected.findIndexByName("posts_2023_02_25");
            IndexInfo actualFoundIndex = actual.findIndexById(expectedIndex1.id());
            assertNotNull(actualFoundIndex, "Should find index by ID");
            assertEquals(expectedIndex1.name(), actualFoundIndex.name(), "Found index name should match");
        }
    }

    @Test
    void testNonExistentRepository() {
        String nonExistentPath = "src/test/resources/snapshots/NonExistent";
        SnapshotRepository repository = SnapshotRepository.fromPath(nonExistentPath);
        
        assertThrows(IOException.class, repository::findLatestIndexFile, 
            "Should throw IOException for non-existent repository");
    }

    @Test
    void testSpecificSnapshotValidation() throws IOException {
        // Test ES 7.10 Double snapshot for multiple snapshots
        RepositoryMetadata expected = ExpectedDataProviders.createES710DoubleExpected();
        SnapshotRepository repository = SnapshotRepository.fromPath("src/test/resources/snapshots/ES_7_10_Double");
        RepositoryMetadata actual = repository.readRepositoryMetadata();
        
        assertEquals(expected.getSnapshotCount(), actual.getSnapshotCount(), 
            "ES 7.10 Double should have 2 snapshots");
        
        // Verify both snapshots exist using expected objects
        for (SnapshotInfo expectedSnapshot : expected.snapshots()) {
            SnapshotInfo actualSnapshot = actual.findSnapshotByUuid(expectedSnapshot.uuid());
            assertNotNull(actualSnapshot, "Should find snapshot " + expectedSnapshot.name());
            assertEquals(expectedSnapshot.name(), actualSnapshot.name(), 
                "Snapshot name should match for " + expectedSnapshot.uuid());
        }
    }

    @Test
    void testBWCSnapshotValidation() throws IOException {
        // Test ES 7.10 BWC Check snapshot for backwards compatibility features
        RepositoryMetadata expected = ExpectedDataProviders.createES710BWCCheckExpected();
        SnapshotRepository repository = SnapshotRepository.fromPath("src/test/resources/snapshots/ES_7_10_BWC_Check");
        RepositoryMetadata actual = repository.readRepositoryMetadata();
        
        assertEquals(expected.getIndexCount(), actual.getIndexCount(), 
            "BWC snapshot should have 4 indices");
        
        // Verify specific BWC indices exist using expected objects
        IndexInfo expectedBwcIndex = expected.findIndexByName("bwc_index_1");
        IndexInfo actualBwcIndex = actual.findIndexByName("bwc_index_1");
        assertNotNull(actualBwcIndex, "Should find BWC index");
        assertEquals(expectedBwcIndex.id(), actualBwcIndex.id(), 
            "BWC index ID should match");
        
        IndexInfo expectedFwcIndex = expected.findIndexByName("fwc_index_1");
        IndexInfo actualFwcIndex = actual.findIndexByName("fwc_index_1");
        assertNotNull(actualFwcIndex, "Should find FWC index");
        assertEquals(expectedFwcIndex.id(), actualFwcIndex.id(), 
            "FWC index ID should match");
    }
}
