package com.rfs.cms;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpOptions;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

public class ApacheHttpClient implements AbstractedHttpClient {
    private final CloseableHttpClient client = HttpClients.createDefault();
    private final URI baseUri;

    public ApacheHttpClient(URI baseUri) {
        this.baseUri = baseUri;
    }

    private static HttpUriRequestBase makeRequestBase(URI baseUri, String method, String path) {
        switch (method.toUpperCase()) {
            case "GET":
                return new HttpGet(baseUri + "/" + OpenSearchWorkCoordinator.INDEX_NAME + path);
            case OpenSearchWorkCoordinator.POST_METHOD:
                return new HttpPost(baseUri + "/" + OpenSearchWorkCoordinator.INDEX_NAME + path);
            case OpenSearchWorkCoordinator.PUT_METHOD:
                return new HttpPut(baseUri + "/" + OpenSearchWorkCoordinator.INDEX_NAME + path);
            case "PATCH":
                return new HttpPatch(baseUri + "/" + OpenSearchWorkCoordinator.INDEX_NAME + path);
            case "HEAD":
                return new HttpHead(baseUri + "/" + OpenSearchWorkCoordinator.INDEX_NAME + path);
            case "OPTIONS":
                return new HttpOptions(baseUri + "/" + OpenSearchWorkCoordinator.INDEX_NAME + path);
            case "DELETE":
                return new HttpDelete(baseUri + "/" + OpenSearchWorkCoordinator.INDEX_NAME + path);
            default:
                throw new IllegalArgumentException("Cannot map method to an Apache Http Client request: " + method);
        }
    }

    @Override
    public AbstractHttpResponse makeRequest(String method, String path,
                                            Map<String, String> headers, String payload) throws IOException {
        var request = makeRequestBase(baseUri, method, path);
        request.setHeaders(request.getHeaders());
        request.setEntity(new StringEntity(payload));
        return client.execute(request, fr -> new AbstractHttpResponse() {
            @Override
            public InputStream getPayloadStream() throws IOException {
                return fr.getEntity().getContent();
            }

            @Override
            public String getStatusText() {
                return fr.getReasonPhrase();
            }

            @Override
            public int getStatusCode() {
                return fr.getCode();
            }

            @Override
            public Stream<Map.Entry<String, String>> getHeaders() {
                return Arrays.stream(fr.getHeaders())
                        .map(h -> new AbstractMap.SimpleEntry<>(h.getName(), h.getValue()));
            }
        });
    }

    @Override
    public void close() throws Exception {
        client.close();
    }
}
