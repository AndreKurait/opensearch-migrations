package org.opensearch.migrations.bulkload.common.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.opensearch.migrations.bulkload.netty.ReadMeteringHandler;
import org.opensearch.migrations.bulkload.netty.WriteMeteringHandler;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;

/**
 * Implementation of HttpClientAdapter using Reactor Netty.
 */
public class ReactorNettyAdapter implements HttpClientAdapter {
    private final HttpClient client;
    private final ConnectionContext connectionContext;
    
    public static final String READ_METERING_HANDLER_NAME = "REST_CLIENT_READ_METERING_HANDLER";
    public static final String WRITE_METERING_HANDLER_NAME = "REST_CLIENT_WRITE_METERING_HANDLER";

    public ReactorNettyAdapter(ConnectionContext connectionContext, HttpClient client) {
        this.connectionContext = connectionContext;
        this.client = client;
    }

    @Override
    public Mono<HttpResponse> request(String method, String path, String body, Map<String, List<String>> headers,
                                     IRfsContexts.IRequestContext context) {
        var contextCleanupRef = new AtomicReference<Runnable>(() -> {});
        
        // Support auto compressing payload if headers indicate support and payload is not compressed
        return new CompositeTransformer(
            new GzipPayloadRequestTransformer(),
            connectionContext.getRequestTransformer()
        ).transform(method, path, headers, Mono.justOrEmpty(body)
                .map(b -> ByteBuffer.wrap(b.getBytes(StandardCharsets.UTF_8)))
            )
            .<HttpResponse>flatMap(transformedRequest ->
                client.doOnRequest((r, conn) -> contextCleanupRef.set(addSizeMetricsHandlersAndGetCleanup(context).apply(r, conn)))
                .headers(h -> transformedRequest.getHeaders().forEach(h::add))
                .compress(HttpClientUtils.hasGzipResponseHeaders(transformedRequest.getHeaders()))
                .request(HttpMethod.valueOf(method))
                .uri("/" + path)
                .send(transformedRequest.getBody().map(Unpooled::wrappedBuffer))
                .responseSingle(
                    (response, bytes) -> bytes.asString()
                        .singleOptional()
                        .map(bodyOp -> new HttpResponse(
                            response.status().code(),
                            response.status().reasonPhrase(),
                            extractHeaders(response.responseHeaders()),
                            bodyOp.orElse(null)
                            ))
                )
            )
            .doOnError(t -> {
                if (context != null) {
                    context.addTraceException(t, true);
                }
            })
            .doOnTerminate(() -> contextCleanupRef.get().run());
    }

    @Override
    public boolean supportsGzipCompression() {
        return connectionContext.isCompressionSupported();
    }

    private Map<String, String> extractHeaders(io.netty.handler.codec.http.HttpHeaders headers) {
        return headers.entries().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (v1, v2) -> v1 + "," + v2
            ));
    }

    private static void removeIfPresent(ChannelPipeline p, String name) {
        var h = p.get(name);
        if (h != null) {
            p.remove(h);
        }
    }

    private static void addNewHandler(ChannelPipeline p, String name, ChannelHandler channelHandler) {
        removeIfPresent(p, name);
        p.addFirst(name, channelHandler);
    }

    private BiFunction<HttpClientRequest, Connection, Runnable>
    addSizeMetricsHandlersAndGetCleanup(final IRfsContexts.IRequestContext ctx) {
        if (ctx == null) {
            return (r, conn) -> () -> {};
        }
        return (r, conn) -> {
            var p = conn.channel().pipeline();
            addNewHandler(p, WRITE_METERING_HANDLER_NAME, new WriteMeteringHandler(ctx::addBytesSent));
            addNewHandler(p, READ_METERING_HANDLER_NAME, new ReadMeteringHandler(ctx::addBytesRead));
            return () -> {
                ctx.close();
                removeIfPresent(p, WRITE_METERING_HANDLER_NAME);
                removeIfPresent(p, READ_METERING_HANDLER_NAME);
            };
        };
    }
}
