package org.opensearch.migrations.bulkload.common.http;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;

/**
 * Implementation of RestClient using Reactor Netty.
 */
public class ReactorNettyRestClient extends AbstractRestClient {

    /**
     * Creates a new ReactorNettyRestClient with default connection settings.
     *
     * @param connectionContext The connection context
     */
    public ReactorNettyRestClient(ConnectionContext connectionContext) {
        this(connectionContext, 0);
    }

    /**
     * Creates a new ReactorNettyRestClient with the specified maximum connections.
     *
     * @param connectionContext The connection context
     * @param maxConnections The maximum number of connections
     */
    public ReactorNettyRestClient(ConnectionContext connectionContext, int maxConnections) {
        super(connectionContext, createAdapter(connectionContext, maxConnections));
    }

    private static ReactorNettyAdapter createAdapter(ConnectionContext connectionContext, int maxConnections) {
        HttpClient httpClient;
        
        if (maxConnections <= 0) {
            httpClient = HttpClient.create();
        } else {
            httpClient = HttpClient.create(ConnectionProvider.create("RestClient", maxConnections));
        }
        
        TlsCredentialsProvider tlsCredentialsProvider = connectionContext.getTlsCredentialsProvider();
        SslProvider sslProvider;

        if (tlsCredentialsProvider != null) {
            sslProvider = getSslProvider(tlsCredentialsProvider);
        } else if (connectionContext.isInsecure()) {
            sslProvider = getInsecureSslProvider();
        } else {
            sslProvider = SslProvider.defaultClientProvider();
        }

        httpClient = httpClient
            .secure(sslProvider)
            .baseUrl(connectionContext.getUri().toString())
            .disableRetry(false) // Enable one retry on connection reset with no delay
            .keepAlive(true);
            
        return new ReactorNettyAdapter(connectionContext, httpClient);
    }

    private static SslProvider getSslProvider(TlsCredentialsProvider tlsCredentialsProvider) {
        try {
            SslContextBuilder builder = SslContextBuilder.forClient();

            if (tlsCredentialsProvider.hasCACredentials()) {
                builder.trustManager(tlsCredentialsProvider.getCaCertInputStream());
            }

            if (tlsCredentialsProvider.hasClientCredentials()) {
                builder.keyManager(
                    tlsCredentialsProvider.getClientCertInputStream(),
                    tlsCredentialsProvider.getClientCertKeyInputStream()
                );
            }

            SslContext sslContext = builder.build();

            return SslProvider.builder()
                .sslContext(sslContext)
                .handlerConfigurator(sslHandler -> {
                    SSLEngine engine = sslHandler.engine();
                    SSLParameters sslParameters = engine.getSSLParameters();
                    engine.setSSLParameters(sslParameters);
                })
                .build();

        } catch (SSLException e) {
            throw new IllegalStateException("Unable to construct custom SslProvider", e);
        }
    }

    private static SslProvider getInsecureSslProvider() {
        try {
            SslContext sslContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();

            return SslProvider.builder()
                .sslContext(sslContext)
                .handlerConfigurator(sslHandler -> {
                    SSLEngine engine = sslHandler.engine();
                    SSLParameters sslParameters = engine.getSSLParameters();
                    sslParameters.setEndpointIdentificationAlgorithm(null);
                    engine.setSSLParameters(sslParameters);
                })
                .build();
        } catch (SSLException e) {
            throw new IllegalStateException("Unable to construct SslProvider", e);
        }
    }
}
