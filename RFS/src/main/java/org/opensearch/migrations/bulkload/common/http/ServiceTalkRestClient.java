package org.opensearch.migrations.bulkload.common.http;

/**
 * Implementation of RestClient using ServiceTalk.
 */
public class ServiceTalkRestClient extends AbstractRestClient {

    /**
     * Creates a new ServiceTalkRestClient with default connection settings.
     *
     * @param connectionContext The connection context
     */
    public ServiceTalkRestClient(ConnectionContext connectionContext) {
        this(connectionContext, 0);
    }

    /**
     * Creates a new ServiceTalkRestClient with the specified maximum connections.
     *
     * @param connectionContext The connection context
     * @param maxConnections The maximum number of connections
     */
    public ServiceTalkRestClient(ConnectionContext connectionContext, int maxConnections) {
        super(connectionContext, new ServiceTalkAdapter(connectionContext, maxConnections));
    }
}
