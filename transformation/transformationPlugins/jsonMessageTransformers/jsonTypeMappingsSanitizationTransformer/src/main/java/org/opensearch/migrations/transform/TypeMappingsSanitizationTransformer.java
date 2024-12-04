package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.google.common.io.Resources;

public class TypeMappingsSanitizationTransformer extends JinjavaTransformer {

    public static final String REPLAYER_VARIANT = "jinjava/typeMappings/replayer.j2";

    public TypeMappingsSanitizationTransformer()
        throws IOException {
        this(REPLAYER_VARIANT, null, null, null);
    }

    public TypeMappingsSanitizationTransformer(Map<String, Map<String, String>> indexMappings,
                                               List<List<String>> regexIndexMappings)
        throws IOException {
        this(REPLAYER_VARIANT, null, indexMappings, regexIndexMappings);
    }

    public TypeMappingsSanitizationTransformer(String variantName,
                                               Map<String, Object> featureFlags,
                                               Map<String, Map<String, String>> indexMappings,
                                               List<List<String>> regexIndexMappings) throws IOException {
        super(
            makeTemplate(variantName),
            makeSourceWrapperFunction(featureFlags, indexMappings, regexIndexMappings));
    }

    private static UnaryOperator<Map<String, Object>>
    makeSourceWrapperFunction(Map<String, Object> featureFlagsIncoming,
                              Map<String, Map<String, String>> indexMappingsIncoming,
                              List<List<String>> regexIndexMappingsIncoming)
    {
        var featureFlags = featureFlagsIncoming != null ? featureFlagsIncoming : Map.of();
        var indexMappings = indexMappingsIncoming != null ? indexMappingsIncoming : Map.of();
        var regexIndexMappings = Optional.ofNullable(regexIndexMappingsIncoming)
            .orElseGet(() -> (indexMappingsIncoming == null ? List.of(List.of("(.*)", ".*", "$1")) : List.of()));

        return incomingJson -> Map.of("request", incomingJson,
            "index_mappings", indexMappings,
            "regex_index_mappings", regexIndexMappings,
            "featureFlags", featureFlags);
    }

    private static String makeTemplate(String variantName) throws IOException {
        return Resources.toString(Resources.getResource(variantName), StandardCharsets.UTF_8);
    }
}
