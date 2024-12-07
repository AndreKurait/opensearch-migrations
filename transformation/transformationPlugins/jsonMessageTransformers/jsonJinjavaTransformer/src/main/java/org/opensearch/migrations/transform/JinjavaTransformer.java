package org.opensearch.migrations.transform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.opensearch.migrations.transform.jinjava.DynamicMacroFunction;
import org.opensearch.migrations.transform.jinjava.JavaRegexCaptureFilter;
import org.opensearch.migrations.transform.jinjava.JavaRegexReplaceFilter;
import org.opensearch.migrations.transform.jinjava.JinjavaConfig;
import org.opensearch.migrations.transform.jinjava.LogFunction;
import org.opensearch.migrations.transform.jinjava.NameMappingClasspathResourceLocator;
import org.opensearch.migrations.transform.jinjava.ThrowTag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.lib.fn.ELFunctionDefinition;
import com.hubspot.jinjava.loader.ResourceLocator;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JinjavaTransformer implements IJsonTransformer {

    protected static final ObjectMapper objectMapper = new ObjectMapper();
    public static final String REGEX_REPLACEMENT_CONVERSION_PATTERNS = "regex_replacement_conversion_patterns";

    protected final Jinjava jinjava;
    protected final Function<Map<String, Object>, Map<String, Object>> createContextWithSourceFunction;
    private final String templateStr;

    public JinjavaTransformer(String templateString,
                              UnaryOperator<Map<String, Object>> contextProviderFromSource) {
        this(templateString, contextProviderFromSource, new JinjavaConfig());
    }

    public JinjavaTransformer(String templateString,
                              UnaryOperator<Map<String, Object>> contextProviderFromSource,
                              @NonNull JinjavaConfig jinjavaConfig) {
        this(templateString,
            contextProviderFromSource,
            new NameMappingClasspathResourceLocator(jinjavaConfig.getNamedScripts()),
            jinjavaConfig.getRegexReplacementConversionPatterns());
    }

    public JinjavaTransformer(String templateString,
                              UnaryOperator<Map<String, Object>> createContextWithSource,
                              ResourceLocator resourceLocator,
                              List<Map.Entry<String, String>> regexReplacementConversionPatterns)
    {
        jinjava = new Jinjava();
        this.createContextWithSourceFunction = createContextWithSource;
        jinjava.setResourceLocator(resourceLocator);
        jinjava.getGlobalContext().registerFilter(new JavaRegexCaptureFilter());
        jinjava.getGlobalContext().registerFilter(new JavaRegexReplaceFilter());

        jinjava.getGlobalContext().registerFunction(
            new ELFunctionDefinition("", "invoke_macro", DynamicMacroFunction.class, "invokeMacro",
                String.class, Object[].class));
        jinjava.getGlobalContext().registerFunction(
            new ELFunctionDefinition("", "log_value_and_return", LogFunction.class, "logValueAndReturn",
                String.class, Object.class, Object.class));
        jinjava.getGlobalContext().registerFunction(
            new ELFunctionDefinition("", "log_value", LogFunction.class, "logValue",
                String.class, Object.class));

        jinjava.getGlobalContext().registerTag(new ThrowTag());
        jinjava.getGlobalContext().put(REGEX_REPLACEMENT_CONVERSION_PATTERNS,
            Optional.ofNullable(regexReplacementConversionPatterns)
                .orElse(JavaRegexReplaceFilter.DEFAULT_REGEX_REPLACE_FILTER));
        this.templateStr = templateString;
    }

    @SneakyThrows
    @Override
    public Map<String, Object> transformJson(Map<String, Object> incomingJson) {
        var resultStr = jinjava.render(templateStr, createContextWithSourceFunction.apply(incomingJson));
        log.atInfo().setMessage("output from jinjava... {}").addArgument(resultStr).log();
        var parsedObj = (Map<String,Object>) objectMapper.readValue(resultStr, LinkedHashMap.class);
        return doFinalSubstitutions(incomingJson, parsedObj);
    }

    private Map<String, Object> doFinalSubstitutions(Map<String, Object> incomingJson, Map<String, Object> parsedObj) {
        return Optional.ofNullable(parsedObj.get(JsonKeysForHttpMessage.PRESERVE_KEY)).filter(v->v.equals("*"))
            .map(star -> incomingJson)
            .orElseGet(() -> {
                findAndReplacePreserves(incomingJson, parsedObj);
                findAndReplacePreserves((Map<String, Object>) incomingJson.get(JsonKeysForHttpMessage.PAYLOAD_KEY),
                    (Map<String, Object>) parsedObj.get(JsonKeysForHttpMessage.PAYLOAD_KEY));
                return parsedObj;
            });
    }

    private void findAndReplacePreserves(Map<String, Object> incomingRoot, Map<String, Object> parsedRoot) {
        if (parsedRoot == null || incomingRoot == null) {
            return;
        }
        var preserveKeys = (List<String>) parsedRoot.get(JsonKeysForHttpMessage.PRESERVE_KEY);
        if (preserveKeys != null) {
            preserveKeys.forEach(preservedKey ->
                Optional.ofNullable(incomingRoot.get(preservedKey)).ifPresent(v->parsedRoot.put(preservedKey, v)));
            parsedRoot.remove(JsonKeysForHttpMessage.PRESERVE_KEY);
        }
    }
}
