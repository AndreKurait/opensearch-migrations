package org.opensearch.migrations.bulkload.common;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator.BulkItemResponseEntry;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts.ICheckedIdempotentPutRequestContext;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.opensearch.migrations.bulkload.http.BulkRequestGenerator.itemEntry;
import static org.opensearch.migrations.bulkload.http.BulkRequestGenerator.itemEntryFailure;

class OpenSearchClientTest {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .build();

    @Test
    void testCreateIndex() {
        // Setup
        var checkIfExistsResponse = new HttpResponse(404, "", null, "does not exist");
        var createdItemRawJson = "{\"created\":\"yup!\"}";
        var createItemResponse = new HttpResponse(200, "", null, createdItemRawJson);

        var restClient = Mockito.mock(RestClient.class);
        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(checkIfExistsResponse));
        when(restClient.putAsync(any(), any(), any())).thenReturn(Mono.just(createItemResponse));

        // Action
        var rawJson = "{ }";
        var result = createIndex(restClient, rawJson);

        // Assertions
        assertThat(result.get().toPrettyString(), containsString(rawJson));
        // The interface is to send back the passed json if on success
        assertThat(result.get().toPrettyString(), not(containsString(createdItemRawJson)));
    }

    @Test
    void testCreateIndex_alreadyExists() {
        var checkIfExistsResponse = new HttpResponse(200, "", null, "I exist!");

        var restClient = Mockito.mock(RestClient.class);
        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(checkIfExistsResponse));

        var rawJson = "{ }";
        var result = createIndex(restClient, rawJson);

        assertThat(result, equalTo(Optional.empty()));
    }

    @Test
    void testCreateIndex_errorOnCreation_retried() {
        // Setup
        var checkIfExistsResponse = new HttpResponse(404, "", null, "does not exist");
        var createdItemRawJson = "{\"error\":\"unauthorized\"}";
        var createItemResponse = new HttpResponse(403, "", null, createdItemRawJson);

        var restClient = Mockito.mock(RestClient.class);
        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(checkIfExistsResponse));
        when(restClient.putAsync(any(), any(), any())).thenReturn(Mono.just(createItemResponse));

        // Action
        var rawJson = "{ }";
        var exception = assertThrows(IllegalStateException.class, () -> createIndex(restClient, rawJson));

        // Assertions
        assertThat(exception.getClass().getName(), containsString("RetryExhaustedException"));
        assertThat(exception.getMessage(), containsString("Retries exhausted"));
        assertThat(exception.getCause().getMessage(), containsString("403"));
        assertThat(exception.getCause().getMessage(), containsString("unauthorized"));
        // The interface is to send back the passed json if on success
    }

    @Test
    void testCreateIndex_errorOnCreation_notRetriedOnBadRequest() {
        // Setup
        var checkIfExistsResponse = new HttpResponse(404, "", null, "does not exist");
        var createdItemRawJson = "{\"error\":\"illegal_argument_exception\"}";
        var createItemResponse = new HttpResponse(400, "", null, createdItemRawJson);

        var restClient = Mockito.mock(RestClient.class);
        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(checkIfExistsResponse));
        when(restClient.putAsync(any(), any(), any())).thenReturn(Mono.just(createItemResponse));

        // Action
        var rawJson = "{ }";
        var exception = assertThrows(InvalidResponse.class, () -> createIndex(restClient, rawJson));

        // Assertions
        assertThat(exception.getMessage(), containsString("illegal_argument_exception"));
    }

    @Test
    void testGetClusterVersion() {
        var restClient = mock(RestClient.class);
        var failedRequestLogger = mock(FailedRequestsLogger.class);
        var openSearchClient = new OpenSearchClient(restClient, failedRequestLogger);

        var versionJson = "{\"version\": {\"number\": \"7.10.2\"}}";
        var successResponse = new HttpResponse(200, "OK", Map.of(), versionJson);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);

        when(restClient.getAsync(pathCaptor.capture(), any()))
            .thenReturn(Mono.just(successResponse));

        Version version = openSearchClient.getClusterVersion();

        assertThat(version, equalTo(Version.fromString("ES 7.10.2")));

        String capturedPath = pathCaptor.getValue();
        assertThat(capturedPath, equalTo(""));

        verify(restClient, times(1)).getAsync(anyString(), any());
    }

    @Test
    void testBulkRequest_succeedAfterRetries() {
        var docId1 = "tt1979320";
        var docId2 = "tt0816711";

        var bothDocsFail = bulkItemResponse(
            true,
            List.of(itemEntryFailure(docId1), itemEntryFailure(docId2))
        );
        var oneFailure = bulkItemResponse(
            true,
            List.of(itemEntry(docId1), itemEntryFailure(docId2))
        );
        var finalDocSuccess = bulkItemResponse(false, List.of(itemEntry(docId2)));
        var server500 = new HttpResponse(500, "", null, "{\"error\":\"Cannot Process Error!\"}");

        var restClient = Mockito.mock(RestClient.class);
        when(restClient.postAsync(any(), any(), any(), any())).thenReturn(Mono.just(bothDocsFail))
            .thenReturn(Mono.just(oneFailure))
            .thenReturn(Mono.just(server500))
            .thenReturn(Mono.just(finalDocSuccess));

        var bulkDocs = List.of(createBulkDoc(docId1), createBulkDoc(docId2));

        var failedRequestLogger = mock(FailedRequestsLogger.class);
        var openSearchClient = spy(new OpenSearchClient(restClient, failedRequestLogger));
        doReturn(Retry.fixedDelay(6, Duration.ofMillis(10))).when(openSearchClient).getBulkRetryStrategy();

        // Action
        var responseMono = openSearchClient.sendBulkRequest(
            "myIndex",
            bulkDocs,
            mock(IRfsContexts.IRequestContext.class)
        );
        responseMono.block();

        // Assertions
        // StepVerifier.create(responseMono).expectComplete().verify();

        verify(restClient, times(4)).postAsync(any(), any(), any(), any());
        verifyNoInteractions(failedRequestLogger);
    }

    @Test
    void testBulkRequest_recordsTotalFailures() {
        var docId1 = "tt1979320";
        var docFails = bulkItemResponse(true, List.of(itemEntryFailure(docId1)));

        var restClient = Mockito.mock(RestClient.class);
        when(restClient.postAsync(any(), any(), any(), any())).thenReturn(Mono.just(docFails));

        var failedRequestLogger = mock(FailedRequestsLogger.class);
        var openSearchClient = spy(new OpenSearchClient(restClient, failedRequestLogger));

        var maxRetries = 6;
        doReturn(Retry.fixedDelay(maxRetries, Duration.ofMillis(10))).when(openSearchClient).getBulkRetryStrategy();

        var bulkDoc = createBulkDoc(docId1);
        var indexName = "alwaysFailingIndexName";

        // Action
        var responseMono = openSearchClient.sendBulkRequest(
            indexName,
            List.of(bulkDoc),
            mock(IRfsContexts.IRequestContext.class)
        );
        var exception = assertThrows(Exception.class, () -> responseMono.block());

        // Assertions
        assertThat(exception.getMessage(), containsString("Retries exhausted"));

        var maxAttempts = maxRetries + 1;
        verify(restClient, times(maxAttempts)).postAsync(any(), any(), any(), any());
        verify(failedRequestLogger).logBulkFailure(any(), any(), any(), any());
        verifyNoMoreInteractions(failedRequestLogger);
    }

    private HttpResponse bulkItemResponse(boolean hasErrors, List<BulkItemResponseEntry> entries) {
        var responseBody = BulkRequestGenerator.bulkItemResponse(hasErrors, entries);
        return new HttpResponse(200, "", null, responseBody);
    }

    private BulkDocSection createBulkDoc(String docId) {
        var bulkDoc = mock(BulkDocSection.class);
        when(bulkDoc.getDocId()).thenReturn(docId);
        when(bulkDoc.asString()).thenReturn("BULK-INDEX\nBULK_BODY");
        return bulkDoc;
    }

    @SneakyThrows
    private Optional<ObjectNode> createIndex(RestClient restClient, String rawJson) {
        var openSearchClient = new OpenSearchClient(restClient, mock(FailedRequestsLogger.class));

        var body = (ObjectNode) OBJECT_MAPPER.readTree(rawJson);
        return openSearchClient.createIndex("indexName", body, mock(ICheckedIdempotentPutRequestContext.class));
    }

    @Test
    void testBulkRequest_addsGzipHeaders_whenSupported() {
        var docId = "tt1979320";
        var bulkSuccess = bulkItemResponse(false, List.of(itemEntry(docId)));

        var restClient = Mockito.mock(RestClient.class);
        when(restClient.supportsGzipCompression()).thenReturn(true);
        when(restClient.postAsync(any(), any(), any(), any())).thenReturn(Mono.just(bulkSuccess));

        var failedRequestLogger = mock(FailedRequestsLogger.class);
        var openSearchClient = new OpenSearchClient(restClient, failedRequestLogger);

        var bulkDoc = createBulkDoc(docId);
        var indexName = "testIndex";

        // Action
        openSearchClient.sendBulkRequest(
            indexName,
            List.of(bulkDoc),
            mock(IRfsContexts.IRequestContext.class)
        ).block();

        // Assertions
        ArgumentCaptor<Map<String, List<String>>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(restClient).postAsync(eq(indexName + "/_bulk"), any(), headersCaptor.capture(), any());

        Map<String, List<String>> capturedHeaders = headersCaptor.getValue();
        assertThat(capturedHeaders.get("accept-encoding"), equalTo(List.of("gzip")));
        assertThat(capturedHeaders.get("content-encoding"), equalTo(List.of("gzip")));
    }

    @Test
    void testBulkRequest_doesNotAddGzipHeaders_whenNotSupported() {
        var docId = "tt1979320";
        var bulkSuccess = bulkItemResponse(false, List.of(itemEntry(docId)));

        var restClient = Mockito.mock(RestClient.class);
        when(restClient.supportsGzipCompression()).thenReturn(false);
        when(restClient.postAsync(any(), any(), any(), any())).thenReturn(Mono.just(bulkSuccess));

        var failedRequestLogger = mock(FailedRequestsLogger.class);
        var openSearchClient = new OpenSearchClient(restClient, failedRequestLogger);

        var bulkDoc = createBulkDoc(docId);
        var indexName = "testIndex";

        // Action
        openSearchClient.sendBulkRequest(
            indexName,
            List.of(bulkDoc),
            mock(IRfsContexts.IRequestContext.class)
        ).block();

        // Assertions
        ArgumentCaptor<Map<String, List<String>>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(restClient).postAsync(eq(indexName + "/_bulk"), any(), headersCaptor.capture(), any());

        Map<String, List<String>> capturedHeaders = headersCaptor.getValue();
        assertThat(capturedHeaders.get("accept-encoding"), equalTo(null));
        assertThat(capturedHeaders.get("content-encoding"), equalTo(null));
    }

    @Test
    void testNonBulkRequest_doesNotAddGzipHeaders() {
        var restClient = Mockito.mock(RestClient.class);
        when(restClient.supportsGzipCompression()).thenReturn(true);
        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(new HttpResponse(404, "", null, "does not exist")));
        when(restClient.putAsync(any(), any(), any())).thenReturn(Mono.just(new HttpResponse(200, "", null, "{\"created\":\"yup!\"}")));

        var failedRequestLogger = mock(FailedRequestsLogger.class);
        var openSearchClient = new OpenSearchClient(restClient, failedRequestLogger);

        // Action
        openSearchClient.createIndex("testIndex", OBJECT_MAPPER.createObjectNode(), mock(IRfsContexts.ICheckedIdempotentPutRequestContext.class));

        // Assertions
        verify(restClient).getAsync(any(), any());
        verify(restClient).putAsync(any(), any(), any());
        verifyNoMoreInteractions(restClient);
    }
}
