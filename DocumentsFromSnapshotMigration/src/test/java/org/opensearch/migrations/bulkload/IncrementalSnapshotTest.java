package org.opensearch.migrations.bulkload;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.testcontainers.lifecycle.Startables;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Tag("isolatedTest")
public class IncrementalSnapshotTest extends SourceTestBase {
    @TempDir
    private File localDirectory;

    @TempDir
    private File localDirectory2;


    @TempDir
    private File localDirectory3;


    private static Stream<Arguments> scenarios() {
        return SupportedClusters.supportedPairs(true).stream()
                .map(migrationPair -> Arguments.of(migrationPair.source(), migrationPair.target()));
    }

    @ParameterizedTest(name = "Source {0} to Target {1}")
    @MethodSource(value = "scenarios")
    public void migrationDocuments(
        final SearchClusterContainer.ContainerVersion sourceVersion,
        final SearchClusterContainer.ContainerVersion targetVersion) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            migrationDocumentsWithClusters(sourceCluster, targetCluster);
        }
    }

    @SneakyThrows
    private void migrationDocumentsWithClusters(
        final SearchClusterContainer sourceCluster,
        final SearchClusterContainer targetCluster
    ) {
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();

        try {
            // === ACTION: Set up the source/target clusters ===
            Startables.deepStart(sourceCluster, targetCluster).join();

            var indexName = "blog_2023";
            var numberOfShards = 1;
            var sourceClusterOperations = new ClusterOperations(sourceCluster);
            var targetClusterOperations = new ClusterOperations(targetCluster);

            // Number of default shards is different across different versions on ES/OS.
            // So we explicitly set it.
            var sourceVersion = sourceCluster.getContainerVersion().getVersion();
            String body = String.format(
                "{" +
                "  \"settings\": {" +
                "    \"number_of_shards\": %d," +
                "    \"number_of_replicas\": 0," +
                (VersionMatchers.isBelowES_6_X.test(sourceVersion)
                        ? ""
                        : "    \"index.soft_deletes.enabled\": true,") +
                "    \"refresh_interval\": -1" +
                "  }" +
                "}",
                numberOfShards
            );
            // Setup Metadata
            sourceClusterOperations.createIndex(indexName, body);
            targetClusterOperations.createIndex(indexName, body);

            // === ACTION: Create some searchable documents ===
            sourceClusterOperations.createDocument(indexName, "222", "{\"author\":\"ORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINALORIGINAL\"}");
            sourceClusterOperations.createDocument(indexName, "delete1", "{\"author\":\"DELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETED\"}");
            sourceClusterOperations.createDocument(indexName, "delete2", "{\"author\":\"DELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETEDDELETED\"}");


//            // To create deleted docs in a segment that persists on the snapshot, refresh, then create two docs on a shard, then after a refresh, delete one.
//            sourceClusterOperations.post("/" + indexName + "/_refresh", null);
//            sourceClusterOperations.createDocument(indexName, "toBeDeleted", "{\"author\":\"Tobias Funke\", \"category\": \"cooking\"}", "1", null);
//            sourceClusterOperations.createDocument(indexName, "remaining", "{\"author\":\"Tobias Funke\", \"category\": \"tech\"}", "1", null);
//            sourceClusterOperations.post("/" + indexName + "/_refresh", null);
//            sourceClusterOperations.deleteDocument(indexName, "toBeDeleted" , "1", null);

            sourceClusterOperations.post("/" + indexName + "/_refresh", null);

            // === ACTION: Take a snapshot ===
            var initialSnapshotName = "initial_snapshot";
            var snapshotRepoName = "my_snap_repo";
            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                    .host(sourceCluster.getUrl())
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();
            var snapshotCreator = new FileSystemSnapshotCreator(
                    initialSnapshotName,
                snapshotRepoName,
                sourceClient,
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            sourceCluster.copySnapshotData(localDirectory2.toString());
            var sourceRepo = new FileSystemRepo(localDirectory2.toPath());

            var transformationConfig = VersionMatchers.isES_5_X.or(VersionMatchers.isES_6_X)
                    .test(targetCluster.getContainerVersion().getVersion()) ?
                    "[{\"NoopTransformerProvider\":{}}]" // skip transformations including doc type removal
                    : null;


//            sourceClusterOperations.createDocument(indexName, "B", "{\"author\":\"Tobias Funke\"}");
            sourceClusterOperations.deleteDocument(indexName,  "delete1", null);
//            sourceClusterOperations.post("/" + indexName + "/_refresh", null);

            sourceClusterOperations.createDocument(indexName, "make1", "{\"author\":\"add\"}");


            var secondarySnapshotName = "initial_snapshot2";
            var snapshotCreator2 = new FileSystemSnapshotCreator(
                    secondarySnapshotName,
                    snapshotRepoName,
                    sourceClient,
                    SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                    List.of(),
                    snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator2);
            sourceCluster.copySnapshotData(localDirectory.toString());
            var sourceRepo2 = new FileSystemRepo(localDirectory.toPath());

            sourceClusterOperations.deleteDocument(indexName,  "delete2", null);

//            sourceClusterOperations.post("/" + indexName + "/_refresh", null);


            // === ACTION: Take a snapshot ===
            var snapshotCreator3 = new FileSystemSnapshotCreator(
                    "initial_snapshot3",
                    snapshotRepoName,
                    sourceClient,
                    SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                    List.of(),
                    snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator3);
            sourceCluster.copySnapshotData(localDirectory3.toString());
            var sourceRepo3 = new FileSystemRepo(localDirectory3.toPath());


            var firstTerminationException = waitForRfsCompletion(() -> migrateDocumentsSequentially(
                    sourceRepo3,
                    "initial_snapshot3",
                    List.of(),
                    targetCluster,
                    new AtomicInteger(),
                    new Random(1),
                    testDocMigrationContext,
                    sourceCluster.getContainerVersion().getVersion(),
                    targetCluster.getContainerVersion().getVersion(),
                    transformationConfig
            ));


            // === ACTION: Migrate the documents ===
            var runCounter = new AtomicInteger();
            var clockJitter = new Random(1);

            targetClusterOperations.delete("/.migrations_working_state");

            // ExpectedMigrationWorkTerminationException is thrown on completion.
            var expectedTerminationException = waitForRfsCompletion(() -> migrateDocumentsSequentially(
                    sourceRepo2,
                    secondarySnapshotName,
                    List.of(),
                    targetCluster,
                    runCounter,
                    clockJitter,
                    testDocMigrationContext,
                    sourceCluster.getContainerVersion().getVersion(),
                    targetCluster.getContainerVersion().getVersion(),
                    transformationConfig
            ));

            Assertions.assertEquals(numberOfShards + 1, expectedTerminationException.numRuns);

            // Check that the docs were migrated
            checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);

            // Check that that docs were migrated with routing
//            checkDocsWithRouting(sourceCluster, testDocMigrationContext);
//            checkDocsWithRouting(targetCluster, testDocMigrationContext);
        } finally {
            deleteTree(localDirectory.toPath());
        }
    }

    private String generateLargeDocJson(int sizeInMB) {
        // Calculate the number of characters needed (1 char = 1 byte)
        int numChars = sizeInMB * 1024 * 1024;
        Random random = new Random(1); // fixed seed for reproducibility
        StringBuilder sb = new StringBuilder(numChars);
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < numChars; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        String timestamp = java.time.Instant.now().toString();
        return "{\"timestamp\":\"" + timestamp + "\", \"large_field\":\"" + sb + "\"}";
    }

    private void checkDocsWithRouting(
        SearchClusterContainer clusterContainer,
        DocumentMigrationTestContext context) {
        var clusterClient = new RestClient(ConnectionContextTestParams.builder()
            .host(clusterContainer.getUrl())
            .build()
            .toConnectionContext()
        );

        // Check that search by routing works as expected.
        var requests = new SearchClusterRequests(context);
        var hits = requests.searchIndexByQueryString(clusterClient, "blog_2023", "category:cooking", "1");

        Assertions.assertTrue(hits.isArray() && hits.size() == 2);

        for (JsonNode hit : hits) {
            String routing = hit.path("_routing").asText();
            Assertions.assertEquals("1", routing);
        }
    }
    
}
