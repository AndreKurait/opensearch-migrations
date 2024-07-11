package com.rfs.common;

import java.util.Base64;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.SneakyThrows;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

public class RestClient {
    public static class Response {
        public final int code;
        public final String body;
        public final String message;

        public Response(int responseCode, String responseBody, String responseMessage) {
            this.code = responseCode;
            this.body = responseBody;
            this.message = responseMessage;
        }
    }

    public final ConnectionDetails connectionDetails;
    private final WebClient webClient;

    @SneakyThrows
    public RestClient(ConnectionDetails connectionDetails) {
        this.connectionDetails = connectionDetails;

        WebClient.Builder webClientBuilder = WebClient.builder()
                .baseUrl(connectionDetails.url)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "RfsWorker-1.0");

        if (connectionDetails.insecure) {
            SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();

            webClientBuilder.clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                    .secure(t -> t.sslContext(sslContext)
                            .handlerConfigurator(sslHandler -> {
                                SSLEngine engine = sslHandler.engine();
                                SSLParameters sslParameters = engine.getSSLParameters();
                                sslParameters.setEndpointIdentificationAlgorithm(null);
                                engine.setSSLParameters(sslParameters);
                            }))));
        }

        if (connectionDetails.authConfig instanceof AuthConfig.BasicAuth) {
            AuthConfig.BasicAuth basicAuth = (AuthConfig.BasicAuth) connectionDetails.authConfig;
            String credentials = basicAuth.username + ":" + basicAuth.password;
            String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
            webClientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedCredentials);
        } else if (connectionDetails.authConfig instanceof AuthConfig.SigV4Auth) {
            AuthConfig.SigV4Auth sigV4Auth = (AuthConfig.SigV4Auth) connectionDetails.authConfig;
            AwsV4HttpSigner signer = new AwsV4HttpSigner(
                sigV4Auth.awsCredentialsProvider,
                sigV4Auth.awsRegion,
                sigV4Auth.awsServiceName
            );

            webClientBuilder.filter((request, next) -> {
                try {
                    ClientRequest signedRequest = signer.sign(request);
                    return next.exchange(signedRequest);
                } catch (Exception e) {
                    return Mono.error(new RuntimeException("Failed to sign request", e));
                }
            });
        } else if (connectionDetails.authConfig instanceof AuthConfig.NoAuth) {
            // No authentication
        } else {
            throw new IllegalArgumentException("Unsupported authentication type: " + connectionDetails.authConfig.getClass().getSimpleName());
        }

        this.webClient = webClientBuilder.build();
    }

    public Mono<Response> getAsync(String path) {
        return webClient.get()
            .uri("/" + path)
            .retrieve()
            .toEntity(String.class)
            .map(responseEntity -> new Response(responseEntity.getStatusCodeValue(), responseEntity.getBody(), responseEntity.getStatusCode().getReasonPhrase()));
    }

    public Response get(String path) {
        return getAsync(path).block();
    }

    public Mono<Response> postAsync(String path, String body) {
        return webClient.post()
            .uri("/" + path)
            .body(BodyInserters.fromValue(body))
            .retrieve()
            .toEntity(String.class)
            .map(responseEntity -> new Response(responseEntity.getStatusCodeValue(), responseEntity.getBody(), responseEntity.getStatusCode().getReasonPhrase()));
    }

    public Mono<Response> putAsync(String path, String body) {
        return webClient.put()
            .uri("/" + path)
            .body(BodyInserters.fromValue(body))
            .retrieve()
            .toEntity(String.class)
            .map(responseEntity -> new Response(responseEntity.getStatusCodeValue(), responseEntity.getBody(), responseEntity.getStatusCode().getReasonPhrase()));
    }

    public Response put(String path, String body) {
        return putAsync(path, body).block();
    }
}
