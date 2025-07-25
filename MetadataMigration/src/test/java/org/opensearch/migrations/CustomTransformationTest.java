package org.opensearch.migrations;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.transform.TransformerParams;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval.MultiTypeResolutionBehavior;

import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test class to verify custom transformations during metadata migrations.
 */
@Tag("isolatedTest")
@Slf4j
class CustomTransformationTest extends BaseMigrationTest {

    @TempDir
    protected File localDirectory;

    private static Stream<Arguments> scenarios() {
        // Transformations are differentiated only by source, so lock to a specific target.
        var target = SearchClusterContainer.OS_LATEST;
        return SupportedClusters.supportedSources(false)
                .stream()
                // The TypeMappingSanitizationTransformerProvider is not supported on 2.x source
                .filter(source -> !VersionMatchers.isOS_2_X.test(source.getVersion()))
                .map(sourceCluster -> Arguments.of(sourceCluster, target));
    }

    @ParameterizedTest(name = "Custom Transformation From {0} to {1}")
    @MethodSource(value = "scenarios")
    void customTransformationMetadataMigration(
            SearchClusterContainer.ContainerVersion sourceVersion,
            SearchClusterContainer.ContainerVersion targetVersion) {
        try (
                final var sourceCluster = new SearchClusterContainer(sourceVersion);
                final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            performCustomTransformationTest();
        }
    }

    @SneakyThrows
    private void performCustomTransformationTest() {
        startClusters();

        var newComponentCompatible = sourceCluster.getContainerVersion().getVersion().getMajor() >= 7;

        // Test data
        var originalIndexName = "test_index";
        var transformedIndexName = "transformed_index";
        var documentId = "1";
        var documentContent = "{\"field\":100}";

        // Create index and add a document on the source cluster
        sourceOperations.createIndex(originalIndexName);
        sourceOperations.createDocument(originalIndexName, documentId, documentContent);

        // Create legacy template
        var legacyTemplateName = "legacy_template";
        var legacyTemplatePattern = "legacy_*";
        sourceOperations.createLegacyTemplate(legacyTemplateName, legacyTemplatePattern);

        // Create index template and component template if compatible
        var indexTemplateName = "index_template";
        var indexTemplatePattern = "index*";
        var componentTemplateName = "component_template";
        if (newComponentCompatible) {
            sourceOperations.createIndexTemplate(indexTemplateName, "dummy", indexTemplatePattern);
            sourceOperations.createComponentTemplate(componentTemplateName, indexTemplateName, "additional_param", "index*");
        }

        // Create indices that match the templates
        var legacyIndexName = "legacy_index";
        var indexIndexName = "index_index";
        sourceOperations.createIndex(legacyIndexName);
        sourceOperations.createIndex(indexIndexName);

        // Define custom transformations
        String customTransformationJson = "[\n" +
            "  {\n" +
            "    \"TypeMappingSanitizationTransformerProvider\": {\n" +
            "      \"staticMappings\": {\n" +
            "        \"test_index\": {\n" +
            "            \"doc\": \"transformed_index\",\n" +
            "            \"_doc\": \"transformed_index\"\n" +
            "          },\n" +
            "        \"legacy_template\": {\n" +
            "            \"doc\": \"transformed_legacy_template\",\n" +
            "            \"_doc\": \"transformed_legacy_template\"\n" +
            "          },\n" +
            "        \"index_template\": {\n" +
            "            \"doc\": \"transformed_index_template\",\n" +
            "            \"_doc\": \"transformed_index_template\"\n" +
            "          },\n" +
            "        \"component_template\": {\n" +
            "            \"doc\": \"transformed_component_template\",\n" +
            "            \"_doc\": \"transformed_component_template\"\n" +
            "          }\n" +
            "      },\n" +
            "      \"sourceProperties\": {\n" +
            "        \"version\": {\n" +
            "          \"major\": " + sourceCluster.getContainerVersion().getVersion().getMajor() + ",\n" +
            "          \"minor\": " + sourceCluster.getContainerVersion().getVersion().getMinor() + "\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "]";

        var snapshotName = "custom_transformation_snap";
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());
        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());
        arguments.metadataTransformationParams.multiTypeResolutionBehavior = MultiTypeResolutionBehavior.UNION;

        // Set up data filters
        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexAllowlist = List.of(originalIndexName, legacyIndexName, indexIndexName, transformedIndexName);
        dataFilterArgs.indexTemplateAllowlist = List.of(indexTemplateName, legacyTemplateName, "transformed_legacy_template", "transformed_index_template");
        dataFilterArgs.componentTemplateAllowlist = List.of(componentTemplateName, "transformed_component_template");
        arguments.dataFilterArgs = dataFilterArgs;

        // Set up transformation parameters
        arguments.metadataCustomTransformationParams = TestCustomTransformationParams.builder()
                .transformerConfig(customTransformationJson)
                .build();

        // Execute migration
        MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);

        // Verify the migration result
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        // Verify that the transformed index exists on the target cluster
        var res = targetOperations.get("/" + transformedIndexName);
        assertThat(res.getKey(), equalTo(200));
        assertThat(res.getValue(), containsString(transformedIndexName));

        // Verify that the original index does not exist on the target cluster
        res = targetOperations.get("/" + originalIndexName);
        assertThat(res.getKey(), equalTo(404));

        // Verify templates
        res = targetOperations.get("/_template/transformed_legacy_template");
        assertThat(res.getKey(), equalTo(200));
        assertThat(res.getValue(), containsString("transformed_legacy_template"));

        res = targetOperations.get("/_template/" + "legacy_template");
        assertThat(res.getKey(), equalTo(404));

        if (newComponentCompatible) {
            res = targetOperations.get("/_index_template/transformed_index_template");
            assertThat(res.getKey(), equalTo(200));
            assertThat(res.getValue(), containsString("transformed_index_template"));

            res = targetOperations.get("/_index_template/" + indexTemplateName);
            assertThat(res.getKey(), equalTo(404));

            res = targetOperations.get("/_component_template/transformed_component_template");
            assertThat(res.getKey(), equalTo(200));
            assertThat(res.getValue(), containsString("transformed_component_template"));

            res = targetOperations.get("/_component_template/" + componentTemplateName);
            assertThat(res.getKey(), equalTo(404));
        }
    }

    @Data
    @Builder
    private static class TestCustomTransformationParams implements TransformerParams {
        @Builder.Default
        private String transformerConfigParameterArgPrefix = "";
        private String transformerConfigEncoded;
        private String transformerConfig;
        private String transformerConfigFile;
    }
}
