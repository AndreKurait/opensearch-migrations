package org.opensearch.migrations.bulkload.common.http;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import lombok.Getter;
import reactor.core.publisher.Mono;

/**
 * Abstract base class for RestClient implementations.
 * This class provides common functionality for different HTTP client implementations.
 */
public abstract class AbstractRestClient {
    @Getter
    protected final ConnectionContext connectionContext;
    protected final HttpClientAdapter httpClientAdapter;

    private static final String USER_AGENT_HEADER_NAME = "User-Agent";
    private static final String CONTENT_TYPE_HEADER_NAME = "Content-Type";
    private static final String HOST_HEADER_NAME = "Host";

    private static final String USER_AGENT = "RfsWorker-1.0";
    private static final String JSON_CONTENT_TYPE = "application/json";

    protected AbstractRestClient(ConnectionContext connectionContext, HttpClientAdapter httpClientAdapter) {
        this.connectionContext = connectionContext;
        this.httpClientAdapter = httpClientAdapter;
    }

    /**
     * Gets the host header value for the connection context.
     *
     * @param connectionContext The connection context
     * @return The host header value
     */
    public static String getHostHeaderValue(ConnectionContext connectionContext) {
        String host = connectionContext.getUri().getHost();
        int port = connectionContext.getUri().getPort();
        ConnectionContext.Protocol protocol = connectionContext.getProtocol();

        if (ConnectionContext.Protocol.HTTP.equals(protocol)) {
            if (port == -1 || port == 80) {
                return host;
            }
        } else if (ConnectionContext.Protocol.HTTPS.equals(protocol)) {
            if (port == -1 || port == 443) {
                return host;
            }
        } else {
            throw new IllegalArgumentException("Unexpected protocol" + protocol);
        }
        return host + ":" + port;
    }

    /**
     * Performs an HTTP request with flat header values.
     *
     * @param method The HTTP method
     * @param path The request path
     * @param body The request body
     * @param additionalHeaders Additional headers
     * @param context The request context
     * @return A Mono that emits the HTTP response
     */
    public Mono<HttpResponse> asyncRequestWithFlatHeaderValues(String method, String path, String body, Map<String, String> additionalHeaders,
                                                               IRfsContexts.IRequestContext context) {
        var convertedHeaders = additionalHeaders.entrySet().stream().collect(Collectors
            .toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        return asyncRequest(method, path, body, convertedHeaders, context);
    }

    /**
     * Performs an HTTP request.
     *
     * @param method The HTTP method
     * @param path The request path
     * @param body The request body
     * @param additionalHeaders Additional headers
     * @param context The request context
     * @return A Mono that emits the HTTP response
     */
    public Mono<HttpResponse> asyncRequest(String method, String path, String body, Map<String, List<String>> additionalHeaders,
                                           IRfsContexts.IRequestContext context) {
        Map<String, List<String>> headers = prepareHeaders(body, additionalHeaders);
        return httpClientAdapter.request(method, path, body, headers, context);
    }

    /**
     * Prepares the headers for an HTTP request.
     *
     * @param body The request body
     * @param additionalHeaders Additional headers
     * @return The prepared headers
     */
    protected Map<String, List<String>> prepareHeaders(String body, Map<String, List<String>> additionalHeaders) {
        Map<String, List<String>> headers = new java.util.HashMap<>();
        headers.put(USER_AGENT_HEADER_NAME, List.of(USER_AGENT));
        var hostHeaderValue = getHostHeaderValue(connectionContext);
        headers.put(HOST_HEADER_NAME, List.of(hostHeaderValue));
        if (body != null) {
            headers.put(CONTENT_TYPE_HEADER_NAME, List.of(JSON_CONTENT_TYPE));
        }
        if (additionalHeaders != null) {
            additionalHeaders.forEach((key, value) -> {
                if (headers.containsKey(key.toLowerCase())) {
                    headers.put(key.toLowerCase(), value);
                } else {
                    headers.put(key, value);
                }
            });
        }
        return headers;
    }

    /**
     * Checks if the client supports GZIP compression.
     *
     * @return true if GZIP compression is supported, false otherwise
     */
    public boolean supportsGzipCompression() {
        return httpClientAdapter.supportsGzipCompression();
    }

    /**
     * Performs a GET request.
     *
     * @param path The request path
     * @param context The request context
     * @return The HTTP response
     */
    public HttpResponse get(String path, IRfsContexts.IRequestContext context) {
        return getAsync(path, context).block();
    }

    /**
     * Performs an asynchronous GET request.
     *
     * @param path The request path
     * @param context The request context
     * @return A Mono that emits the HTTP response
     */
    public Mono<HttpResponse> getAsync(String path, IRfsContexts.IRequestContext context) {
        return asyncRequest("GET", path, null, null, context);
    }

    /**
     * Performs an asynchronous POST request.
     *
     * @param path The request path
     * @param body The request body
     * @param additionalHeaders Additional headers
     * @param context The request context
     * @return A Mono that emits the HTTP response
     */
    public Mono<HttpResponse> postAsync(
        String path,
        String body,
        Map<String, List<String>> additionalHeaders,
        IRfsContexts.IRequestContext context
    ) {
        return asyncRequest("POST", path, body, additionalHeaders, context);
    }

    /**
     * Performs an asynchronous POST request.
     *
     * @param path The request path
     * @param body The request body
     * @param context The request context
     * @return A Mono that emits the HTTP response
     */
    public Mono<HttpResponse> postAsync(String path, String body, IRfsContexts.IRequestContext context) {
        return asyncRequest("POST", path, body, null, context);
    }

    /**
     * Performs a POST request.
     *
     * @param path The request path
     * @param body The request body
     * @param context The request context
     * @return The HTTP response
     */
    public HttpResponse post(String path, String body, IRfsContexts.IRequestContext context) {
        return postAsync(path, body, context).block();
    }

    /**
     * Performs an asynchronous PUT request.
     *
     * @param path The request path
     * @param body The request body
     * @param context The request context
     * @return A Mono that emits the HTTP response
     */
    public Mono<HttpResponse> putAsync(String path, String body, IRfsContexts.IRequestContext context) {
        return asyncRequest("PUT", path, body, null, context);
    }

    /**
     * Performs a PUT request.
     *
     * @param path The request path
     * @param body The request body
     * @param context The request context
     * @return The HTTP response
     */
    public HttpResponse put(String path, String body, IRfsContexts.IRequestContext context) {
        return putAsync(path, body, context).block();
    }
}
