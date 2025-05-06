package org.opensearch.migrations.bulkload.common.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

/**
 * Implementation of HttpClientAdapter using ServiceTalk.
 * This is a temporary implementation that uses Reactor Netty under the hood
 * until the ServiceTalk dependencies are properly resolved.
 */
public class ServiceTalkAdapter implements HttpClientAdapter {
    private final ReactorNettyAdapter delegate;
    private final ConnectionContext connectionContext;

    public ServiceTalkAdapter(ConnectionContext connectionContext, int maxConnections) {
        this.connectionContext = connectionContext;
        
        // Create a ReactorNettyAdapter as a temporary delegate
        HttpClient httpClient;
        
        if (maxConnections <= 0) {
            httpClient = HttpClient.create();
        } else {
            httpClient = HttpClient.create(ConnectionProvider.create("ServiceTalkClient", maxConnections));
        }
        
        // Configure SSL if needed
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
        
        this.delegate = new ReactorNettyAdapter(connectionContext, httpClient);
    }

    @Override
    public Mono<org.opensearch.migrations.bulkload.common.http.HttpResponse> request(
            String method, String path, String body, Map<String, List<String>> headers,
            IRfsContexts.IRequestContext context) {
        
        // Delegate to ReactorNettyAdapter
        return delegate.request(method, path, body, headers, context);
    }

    @Override
    public boolean supportsGzipCompression() {
        return connectionContext.isCompressionSupported();
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
