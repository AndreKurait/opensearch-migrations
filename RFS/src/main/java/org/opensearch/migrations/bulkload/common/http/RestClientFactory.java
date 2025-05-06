package org.opensearch.migrations.bulkload.common.http;

/**
 * Factory for creating RestClient instances.
 */
public class RestClientFactory {

    /**
     * The HTTP client implementation to use.
     */
    public enum HttpClientImplementation {
        /**
         * Use Reactor Netty for HTTP client implementation.
         */
        REACTOR_NETTY,
        
        /**
         * Use ServiceTalk for HTTP client implementation.
         */
        SERVICE_TALK
    }

    private RestClientFactory() {
        // Utility class, no instances
    }

    /**
     * Creates a new RestClient with the specified implementation.
     *
     * @param connectionContext The connection context
     * @param implementation The HTTP client implementation to use
     * @return A new RestClient instance
     */
    public static AbstractRestClient createRestClient(ConnectionContext connectionContext, HttpClientImplementation implementation) {
        return createRestClient(connectionContext, implementation, 0);
    }

    /**
     * Creates a new RestClient with the specified implementation and maximum connections.
     *
     * @param connectionContext The connection context
     * @param implementation The HTTP client implementation to use
     * @param maxConnections The maximum number of connections
     * @return A new RestClient instance
     */
    public static AbstractRestClient createRestClient(ConnectionContext connectionContext, 
                                                     HttpClientImplementation implementation,
                                                     int maxConnections) {
        switch (implementation) {
            case REACTOR_NETTY:
                return new ReactorNettyRestClient(connectionContext, maxConnections);
            case SERVICE_TALK:
                return new ServiceTalkRestClient(connectionContext, maxConnections);
            default:
                throw new IllegalArgumentException("Unknown HTTP client implementation: " + implementation);
        }
    }

    /**
     * Creates a new RestClient with the default implementation (Reactor Netty).
     *
     * @param connectionContext The connection context
     * @return A new RestClient instance
     */
    public static AbstractRestClient createRestClient(ConnectionContext connectionContext) {
        return createRestClient(connectionContext, HttpClientImplementation.REACTOR_NETTY);
    }

    /**
     * Creates a new RestClient with the default implementation (Reactor Netty) and the specified maximum connections.
     *
     * @param connectionContext The connection context
     * @param maxConnections The maximum number of connections
     * @return A new RestClient instance
     */
    public static AbstractRestClient createRestClient(ConnectionContext connectionContext, int maxConnections) {
        return createRestClient(connectionContext, HttpClientImplementation.REACTOR_NETTY, maxConnections);
    }
}
