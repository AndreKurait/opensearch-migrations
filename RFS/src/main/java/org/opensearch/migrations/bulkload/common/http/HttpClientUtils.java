package org.opensearch.migrations.bulkload.common.http;

import java.util.List;
import java.util.Map;

/**
 * Utility methods for HTTP clients.
 */
public class HttpClientUtils {
    private static final String ACCEPT_ENCODING_HEADER_NAME = "Accept-Encoding";
    private static final String CONTENT_ENCODING_HEADER_NAME = "Content-Encoding";
    private static final String GZIP_TYPE = "gzip";
    private static final String GZIP_CONTENT_ENCODING_HEADER_VALUE = "gzip";

    private HttpClientUtils() {
        // Utility class, no instances
    }

    /**
     * Adds GZIP response headers to the given headers map.
     *
     * @param headers The headers map to add to
     */
    public static void addGzipResponseHeaders(Map<String, List<String>> headers) {
        headers.put(ACCEPT_ENCODING_HEADER_NAME, List.of(GZIP_TYPE));
    }

    /**
     * Checks if the given headers map has GZIP response headers.
     *
     * @param headers The headers map to check
     * @return true if the headers map has GZIP response headers, false otherwise
     */
    public static boolean hasGzipResponseHeaders(Map<String, List<String>> headers) {
        return headers.getOrDefault(ACCEPT_ENCODING_HEADER_NAME, List.of()).contains(GZIP_TYPE);
    }

    /**
     * Adds GZIP request headers to the given headers map.
     *
     * @param headers The headers map to add to
     */
    public static void addGzipRequestHeaders(Map<String, List<String>> headers) {
        headers.put(CONTENT_ENCODING_HEADER_NAME, List.of(GZIP_CONTENT_ENCODING_HEADER_VALUE));
    }

    /**
     * Checks if the given headers map has GZIP request headers.
     *
     * @param headers The headers map to check
     * @return true if the headers map has GZIP request headers, false otherwise
     */
    public static boolean hasGzipRequestHeaders(Map<String, List<String>> headers) {
        return headers.getOrDefault(CONTENT_ENCODING_HEADER_NAME, List.of())
            .contains(GZIP_CONTENT_ENCODING_HEADER_VALUE);
    }
}
