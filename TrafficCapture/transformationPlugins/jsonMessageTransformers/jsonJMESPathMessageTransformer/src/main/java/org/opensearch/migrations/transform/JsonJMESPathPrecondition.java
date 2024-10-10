package org.opensearch.migrations.transform;

import io.burt.jmespath.BaseRuntime;
import io.burt.jmespath.Expression;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonJMESPathPrecondition implements IJsonPrecondition {

    Expression<Object> expression;

    public JsonJMESPathPrecondition(BaseRuntime<Object> runtime, String script) {
        this.expression = runtime.compile(script);
    }

    @Override
    public boolean evaluatePrecondition(Map<String, Object> incomingJson) {
        var output = expression.search(incomingJson);
        log.atDebug().setMessage("output={}").addArgument(output).log();
        return (Boolean) output;
    }
}
