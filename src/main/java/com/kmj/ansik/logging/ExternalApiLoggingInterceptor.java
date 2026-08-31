package com.kmj.ansik.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;

public final class ExternalApiLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiLoggingInterceptor.class);
    private final String provider;

    public ExternalApiLoggingInterceptor(String provider) {
        this.provider = provider;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        long startedAt = System.nanoTime();
        String endpoint = safeEndpoint(request.getURI());
        log.info(
                "[EXTERNAL API][{}] 요청 시작 - method={}, endpoint={}, requestBytes={}",
                provider, request.getMethod(), endpoint, body.length
        );
        try {
            ClientHttpResponse response = execution.execute(request, body);
            log.info(
                    "[EXTERNAL API][{}] 요청 완료 - method={}, endpoint={}, status={}, elapsedMs={}",
                    provider,
                    request.getMethod(),
                    endpoint,
                    response.getStatusCode().value(),
                    elapsedMillis(startedAt)
            );
            return response;
        } catch (IOException | RuntimeException e) {
            log.warn(
                    "[EXTERNAL API][{}] 요청 실패 - method={}, endpoint={}, elapsedMs={}, errorType={}, message={}",
                    provider,
                    request.getMethod(),
                    endpoint,
                    elapsedMillis(startedAt),
                    e.getClass().getSimpleName(),
                    singleLine(e.getMessage()),
                    e
            );
            throw e;
        }
    }

    private String safeEndpoint(URI uri) {
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + port + uri.getPath();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String singleLine(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[\\r\\n\\t]", " ");
        return cleaned.length() <= 240 ? cleaned : cleaned.substring(0, 240) + "...";
    }
}
