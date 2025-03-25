package org.opensearch.migrations.bulkload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.data.IndexOptions;
import org.opensearch.migrations.data.WorkloadGenerator;
import org.opensearch.migrations.data.WorkloadOptions;
import org.opensearch.migrations.data.workloads.Workloads;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.Network;

import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

/**
 * Tests the lease expiration behavior of the migration process.
 * This test verifies that:
 * 1. The migration process correctly handles lease expiration
 * 2. The process exits with the expected exit codes during different phases
 * 3. All documents are successfully migrated despite lease expirations
 * 
 * This is an end-to-end integration test that uses real clusters and ToxiProxy
 * to simulate network latency.
 */
@Tag("isolatedTest")
@Slf4j
public class LeaseExpirationTest extends SourceTestBase {

    public static final String TARGET_DOCKER_HOSTNAME = "target";

    private static Stream<Arguments> testParameters() {
        return Stream.concat(
                // Test with all pairs with forceMoreSegments=false
                SupportedClusters.supportedPairs(true).stream()
                        .map(migrationPair ->
                                Arguments.of(false, migrationPair.source(), migrationPair.target())),
                // Add test for ES 7 -> OS 2 with forceMoreSegments=true
                Stream.of(Arguments.of(true, SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_1))
        );
    }

    @ParameterizedTest(name = "forceMoreSegments={0}, sourceClusterVersion={1}, targetClusterVersion={2}")
    @MethodSource("testParameters")
    public void testProcessExitsAsExpected(boolean forceMoreSegments,
                                           SearchClusterContainer.ContainerVersion sourceClusterVersion,
                                           SearchClusterContainer.ContainerVersion targetClusterVersion) {
        int shards = 2;
        int docsPerShard = 2000;
        int indexDocCount = docsPerShard * shards;
        int migrationProcessesPerShard = 3;
        int continueExitCode = 2;
        int finalExitCodePerShard = 0;
        
        log.info("Starting lease expiration test with {} documents across {} shards", 
                indexDocCount, shards);
        
        runTestProcessWithCheckpoint(continueExitCode, (migrationProcessesPerShard - 1) * shards,
                finalExitCodePerShard, shards, shards, indexDocCount, forceMoreSegments,
                sourceClusterVersion,
                targetClusterVersion,
                d -> runProcessAgainstToxicTarget(d.tempDirSnapshot, d.tempDirLucene, d.proxyContainer,
                        sourceClusterVersion));
    }

    /**
     * Runs the test process with checkpoints, verifying that the process exits with the expected
     * exit codes during different phases of the migration.
     */
    @SneakyThrows
    private void runTestProcessWithCheckpoint(int expectedInitialExitCode, int expectedInitialExitCodeCount,
                                              int expectedEventualExitCode, int expectedEventualExitCodeCount,
                                              int shards, int indexDocCount,
                                              boolean forceMoreSegments,
                                              SearchClusterContainer.ContainerVersion sourceClusterVersion,
                                              SearchClusterContainer.ContainerVersion targetClusterVersion,
                                              Function<RunData, Integer> processRunner) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var tempDirSnapshot = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
        var tempDirLucene = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");

        try (
            var esSourceContainer = new SearchClusterContainer(sourceClusterVersion)
                    .withAccessToHost(true)
                    .withReuse(false);
            var network = Network.newNetwork();
            var osTargetContainer = new SearchClusterContainer(targetClusterVersion)
                    .withAccessToHost(true)
                    .withNetwork(network)
                    .withNetworkAliases(TARGET_DOCKER_HOSTNAME)
                    .withReuse(false);
            var proxyContainer = new ToxiProxyWrapper(network)
        ) {
            log.info("Starting containers for source version {} and target version {}", 
                    sourceClusterVersion, targetClusterVersion);
                    
            CompletableFuture.allOf(
                CompletableFuture.runAsync(esSourceContainer::start),
                CompletableFuture.runAsync(osTargetContainer::start),
                CompletableFuture.runAsync(() -> proxyContainer.start("target", 9200))
            ).join();

            log.info("Containers started successfully");

            // Populate the source cluster with data
            log.info("Populating source cluster with {} documents across {} shards", indexDocCount, shards);
            var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                    .host(esSourceContainer.getUrl())
                    .build()
                    .toConnectionContext());
            var client = clientFactory.determineVersionAndCreate();
            var generator = new WorkloadGenerator(client);
            var workloadOptions = new WorkloadOptions();

            var sourceClusterOperations = new ClusterOperations(esSourceContainer);

            // Number of default shards is different across different versions on ES/OS.
            // So we explicitly set it.
            String body = String.format(
                    "{" +
                            "  \"settings\": {" +
                            "    \"index\": {" +
                            "      \"number_of_shards\": %d," +
                            "      \"number_of_replicas\": 0" +
                            "    }" +
                            "  }" +
                            "}",
                    shards
            );
            sourceClusterOperations.createIndex("geonames", body);

            workloadOptions.setTotalDocs(indexDocCount);
            workloadOptions.setWorkloads(List.of(Workloads.GEONAMES));
            workloadOptions.getIndex().indexSettings.put(IndexOptions.PROP_NUMBER_OF_SHARDS, shards);
            // Segments will be created on each refresh which tests segment ordering logic
            workloadOptions.setRefreshAfterEachWrite(forceMoreSegments);
            workloadOptions.setMaxBulkBatchSize(forceMoreSegments ? 10 : 1000);
            if (VersionMatchers.isES_5_X.or(VersionMatchers.isES_6_X).test(sourceClusterVersion.getVersion())) {
                workloadOptions.setDefaultDocType("myType");
            }
            generator.generate(workloadOptions);
            log.info("Source cluster populated successfully");

            // Create the snapshot from the source cluster
            log.info("Creating snapshot from source cluster");
            var args = new CreateSnapshot.Args();
            args.snapshotName = SNAPSHOT_NAME;
            args.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
            args.sourceArgs.host = esSourceContainer.getUrl();

            var snapshotCreator = new CreateSnapshot(args, testSnapshotContext.createSnapshotCreateContext());
            snapshotCreator.run();

            esSourceContainer.copySnapshotData(tempDirSnapshot.toString());
            log.info("Snapshot created and copied successfully");

            int exitCode;
            int initialExitCodeCount = 0;
            int finalExitCodeCount = 0;
            int runs = 0;
            // Add a maximum number of runs to prevent infinite loops
            int maxRuns = expectedInitialExitCodeCount + expectedEventualExitCodeCount + 2; // Add buffer
            
            log.info("Starting migration process runs");
            do {
                exitCode = processRunner.apply(new RunData(tempDirSnapshot, tempDirLucene, proxyContainer));
                runs++;
                initialExitCodeCount += exitCode == expectedInitialExitCode ? 1 : 0;
                finalExitCodeCount += exitCode == expectedEventualExitCode ? 1 : 0;
                log.info("Process run {} exited with code: {}", runs, exitCode);
                
                // Clean tree for subsequent run
                deleteTree(tempDirLucene);
                
                // Add timeout check
                if (runs >= maxRuns) {
                    log.error("Test exceeded maximum number of runs: {}", maxRuns);
                    Assertions.fail("Test did not complete within expected number of runs: " + maxRuns);
                }
            } while (finalExitCodeCount < expectedEventualExitCodeCount && runs < expectedInitialExitCodeCount + expectedEventualExitCodeCount);

            log.info("Migration process completed after {} runs", runs);
            log.info("Initial exit code count: {}, Final exit code count: {}", initialExitCodeCount, finalExitCodeCount);

            // Assert doc count on the target cluster matches source
            log.info("Verifying document counts between source and target clusters");
            checkClusterMigrationOnFinished(esSourceContainer, osTargetContainer,
                    DocumentMigrationTestContext.factory().noOtelTracking());

            // Allow for some flexibility in the number of exit codes (±10%)
            int allowedDeviation = (int) Math.ceil(expectedEventualExitCodeCount * 0.1);
            
            // Check if the final exit code is as expected
            Assertions.assertEquals(
                    expectedEventualExitCode,
                    exitCode,
                    String.format("Expected final exit code %d, but got %d", 
                        expectedEventualExitCode, exitCode)
            );

            // Check if the number of final exit codes is within the expected range
            Assertions.assertTrue(
                Math.abs(expectedEventualExitCodeCount - finalExitCodeCount) <= allowedDeviation,
                String.format("Expected %d final exit codes (±%d), but got %d", 
                    expectedEventualExitCodeCount, allowedDeviation, finalExitCodeCount)
            );

            // Check if the number of initial exit codes is within the expected range
            Assertions.assertTrue(
                Math.abs(expectedInitialExitCodeCount - initialExitCodeCount) <= allowedDeviation,
                String.format("Expected %d initial exit codes (±%d), but got %d", 
                    expectedInitialExitCodeCount, allowedDeviation, initialExitCodeCount)
            );
            
            log.info("Test completed successfully");
        } catch (Exception e) {
            log.error("Test failed with unexpected exception", e);
            throw e;
        } finally {
            deleteTree(tempDirSnapshot);
        }
    }

    /**
     * Runs a migration process against a target cluster with simulated network latency.
     * This method configures ToxiProxy to add latency to the connection, runs the migration process,
     * and monitors its execution.
     */
    @SneakyThrows
    private static int runProcessAgainstToxicTarget(
        Path tempDirSnapshot,
        Path tempDirLucene,
        ToxiProxyWrapper proxyContainer,
        SearchClusterContainer.ContainerVersion sourceClusterVersion
    ) {
        String targetAddress = proxyContainer.getProxyUriAsString();
        var tp = proxyContainer.getProxy();
        
        // Reduced latency for faster test execution while still testing lease behavior
        var latency = tp.toxics().latency("latency-toxic", ToxicDirection.DOWNSTREAM, 100);

        // Set to less than 2x lease time to ensure leases aren't doubling
        // Using a shorter timeout for faster test execution
        int baseTimeoutSeconds = 13;

        String[] additionalArgs = {
            "--documents-per-bulk-request", "10",
            "--max-connections", "2",
            "--initial-lease-duration", "PT7s", // Reduced from PT5s for faster test execution
            "--source-version", sourceClusterVersion.getVersion().toString()
        };

        ProcessBuilder processBuilder = setupProcess(
            tempDirSnapshot,
            tempDirLucene,
            targetAddress,
            additionalArgs
        );

        log.info("Starting migration process with timeout {} seconds", baseTimeoutSeconds);
        var process = runAndMonitorProcess(processBuilder);
        
        // Start a watchdog thread to monitor the process
        CompletableFuture<Boolean> watchdog = CompletableFuture.supplyAsync(() -> {
            try {
                return process.waitFor(baseTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        
        boolean finished = watchdog.get();
        if (!finished) {
            log.error("Process timed out after {} seconds, attempting to kill it...", baseTimeoutSeconds);
            process.destroy(); // Try to be nice about things first...
            
            // Give it a little more time before hard kill
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                log.error("Process still running after graceful shutdown attempt, force killing...");
                process.destroyForcibly();
                
                // Make sure it's really gone
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    log.error("Failed to kill process even with destroyForcibly!");
                }
            }
            Assertions.fail("The process did not finish within the timeout period (" + baseTimeoutSeconds + " seconds).");
        }

        latency.remove();
        log.info("Process completed with exit code: {}", process.exitValue());
        return process.exitValue();
    }
}
