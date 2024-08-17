package com.rfs.common;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import com.rfs.common.http.ConnectionContext;
import com.rfs.common.http.HttpResponse;
import com.rfs.common.http.SigV4AuthTransformer;
import com.rfs.netty.ReadMeteringHandler;
import com.rfs.netty.WriteMeteringHandler;
import com.rfs.tracing.IRfsContexts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuples;

@Slf4j
public class RestClient {
    private final ConnectionContext connectionContext;
    private final HttpClient client;

    public static final String READ_METERING_HANDLER_NAME = "REST_CLIENT_READ_METERING_HANDLER";
    public static final String WRITE_METERING_HANDLER_NAME = "REST_CLIENT_WRITE_METERING_HANDLER";

    private static final String USER_AGENT_HEADER_NAME = HttpHeaderNames.USER_AGENT.toString();
    private static final String CONTENT_TYPE_HEADER_NAME = HttpHeaderNames.CONTENT_TYPE.toString();
    private static final String HOST_HEADER_NAME = HttpHeaderNames.HOST.toString();

    private static final String USER_AGENT = "RfsWorker-1.0";
    private static final String JSON_CONTENT_TYPE = "application/json";

    public RestClient(ConnectionContext connectionContext) {
        this(connectionContext, 0);
    }

    /**
     * @param maxConnections If &gt; 0, an HttpClient will be created with a provider
     *                       that uses this value for maxConnections.  Otherwise, a client
     *                       will be created with default values provided by Reactor.
     */
    public RestClient(ConnectionContext connectionContext, int maxConnections) {
        this(connectionContext, maxConnections <= 0
            ? HttpClient.create()
            : HttpClient.create(ConnectionProvider.create("RestClient", maxConnections)));
    }

    protected RestClient(ConnectionContext connectionContext, HttpClient httpClient) {
        this.connectionContext = connectionContext;

        SslProvider sslProvider;
        if (connectionContext.isInsecure()) {
            try {
                SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
                sslProvider = SslProvider.builder().sslContext(sslContext).handlerConfigurator(sslHandler -> {
                    SSLEngine engine = sslHandler.engine();
                    SSLParameters sslParameters = engine.getSSLParameters();
                    sslParameters.setEndpointIdentificationAlgorithm(null);
                    engine.setSSLParameters(sslParameters);
                }).build();
            } catch (SSLException e) {
                throw new IllegalStateException("Unable to construct SslProvider", e);
            }
        } else {
            sslProvider = SslProvider.defaultClientProvider();
        }

        this.client = httpClient
            .secure(sslProvider)
            .baseUrl(connectionContext.getUri().toString())
            .keepAlive(true);
    }

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

    public Mono<HttpResponse> asyncRequestWithFlatHeaderValues(HttpMethod method, String path, String body, Map<String, String> additionalHeaders,
                                                               IRfsContexts.IRequestContext context) {
        var convertedHeaders = additionalHeaders.entrySet().stream().collect(Collectors
            .toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        return asyncRequest(method, path, body, convertedHeaders, context);
    }

    public Mono<HttpResponse> asyncRequest(HttpMethod method, String path, String body, Map<String, List<String>> additionalHeaders,
                                           @Nullable IRfsContexts.IRequestContext context) {
        return asyncRequest(method, path, body, additionalHeaders, context, false);
    }
    public Mono<HttpResponse> asyncRequest(HttpMethod method, String path, String body, Map<String, List<String>> additionalHeaders,
                                           @Nullable IRfsContexts.IRequestContext context, boolean compressRequestIfAble) {
        assert connectionContext.getUri() != null;

        var compressRequest = compressRequestIfAble && !(connectionContext.getRequestTransformer() instanceof SigV4AuthTransformer);
        if (compressRequest != compressRequestIfAble) {
            log.warn("Request compression requested, but will not be performed due to no support for sigv4");
        }

        Map<String, List<String>> headers = new HashMap<>();
        headers.put(USER_AGENT_HEADER_NAME, List.of(USER_AGENT));
        var hostHeaderValue = getHostHeaderValue(connectionContext);
        headers.put(HOST_HEADER_NAME, List.of(hostHeaderValue));
        if (body != null) {
            headers.put(CONTENT_TYPE_HEADER_NAME, List.of(JSON_CONTENT_TYPE));
            if (compressRequest) {
                headers.put("Content-Encoding", List.of("gzip"));
            }
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
        var contextCleanupRef = new AtomicReference<Runnable>(() -> {});
        return connectionContext.getRequestTransformer().transform(method.name(), path, headers, Mono.justOrEmpty(body)
                .map(b -> ByteBuffer.wrap(b.getBytes(StandardCharsets.UTF_8)))
            )
            .map(request -> Tuples.of(request, request.getBody().map(
                  byteBuffer ->  (compressRequest ? deflateByteBuf(byteBuffer) : Unpooled.wrappedBuffer(byteBuffer)))
                )
            )
            .flatMap(transformedRequest ->
                client.doOnRequest((r, conn) -> contextCleanupRef.set(addSizeMetricsHandlersAndGetCleanup(context).apply(r, conn)))
                .headers(h -> transformedRequest.getT1().getHeaders().forEach(h::add))
                .request(method)
                .uri("/" + path)
                .send(transformedRequest.getT2())
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

    @SneakyThrows
    public static ByteBuf deflateByteBuf(ByteBuffer inputBuffer) {
        // Convert ByteBuffer to byte array
        var originalSize = inputBuffer.remaining();
        byte[] inputBytes = new byte[inputBuffer.remaining()];
        inputBuffer.get(inputBytes);

        // Initialize the ByteBuf to write the compressed data
        ByteBuf byteBuf = Unpooled.buffer(originalSize / 5);

        // Write GZIP header
        byteBuf.writeByte(0x1f); // ID1
        byteBuf.writeByte(0x8b); // ID2
        byteBuf.writeByte(Deflater.DEFLATED); // Compression method
        byteBuf.writeByte(0); // Flags
        byteBuf.writeInt(0); // MTIME (Modification Time)
        byteBuf.writeByte(0); // Extra flags
        byteBuf.writeByte(0xff); // Operating system (255 = unknown)

        // Set up the Deflater
        Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        deflater.setInput(inputBytes);
        deflater.finish();

        // Compress the data
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            byteBuf.writeBytes(buffer, 0, count);
        }

        // Calculate CRC32 and write it as the trailer
        CRC32 crc32 = new CRC32();
        crc32.update(inputBytes);
        byteBuf.writeIntLE((int) crc32.getValue()); // CRC32
        byteBuf.writeIntLE(inputBytes.length); // ISIZE (input size modulo 2^32)

        deflater.end();

        byteBuf.capacity(byteBuf.writerIndex());

        log.info("Compression Ratio: {}", originalSize / byteBuf.readableBytes());

        return byteBuf;
    }

    private Map<String, String> extractHeaders(HttpHeaders headers) {
        return headers.entries().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (v1, v2) -> v1 + "," + v2
            ));
    }

    public HttpResponse get(String path, IRfsContexts.IRequestContext context) {
        return getAsync(path, context).block();
    }

    public Mono<HttpResponse> getAsync(String path, IRfsContexts.IRequestContext context) {
        return asyncRequest(HttpMethod.GET, path, null, null, context);
    }

    public Mono<HttpResponse> postAsync(String path, String body, IRfsContexts.IRequestContext context) {
        return asyncRequest(HttpMethod.POST, path, body, null, context);
    }

    public Mono<HttpResponse> postAsync(String path, String body, IRfsContexts.IRequestContext context, boolean compress) {
        return asyncRequest(HttpMethod.POST, path, body, null, context, compress);
    }

    public HttpResponse post(String path, String body, IRfsContexts.IRequestContext context) {
        return postAsync(path, body, context).block();
    }

    public Mono<HttpResponse> putAsync(String path, String body, IRfsContexts.IRequestContext context) {
        return asyncRequest(HttpMethod.PUT, path, body, null, context);
    }

    public Mono<HttpResponse> putAsync(String path, String body, IRfsContexts.IRequestContext context, boolean compress) {
        return asyncRequest(HttpMethod.PUT, path, body, null, context, compress);
    }

    public HttpResponse put(String path, String body, IRfsContexts.IRequestContext context) {
        return putAsync(path, body, context).block();
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
