package org.opensearch.migrations.bulkload.common.http;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import reactor.core.publisher.Mono;

/**
 * Interface for HTTP client adapters. This abstraction allows for different HTTP client implementations
 * to be used with the RestClient.
 */
public interface HttpClientAdapter {
    /**
     * Performs an HTTP request.
     *
     * @param method The HTTP method (GET, POST, PUT, etc.)
     * @param path The request path
     * @param body The request body, or null if no body
     * @param headers The request headers
     * @param context The request context for tracing and metrics
     * @return A Mono that emits the HTTP response
     */
    Mono<HttpResponse> request(String method, String path, String body, Map<String, List<String>> headers, 
                              IRfsContexts.IRequestContext context);

    /**
     * Checks if the client supports GZIP compression.
     *
     * @return true if GZIP compression is supported, false otherwise
     */
    boolean supportsGzipCompression();
}
