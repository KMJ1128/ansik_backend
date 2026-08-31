package com.kmj.ansik.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final Set<String> REDACTED_PARAMETER_NAMES = Set.of(
            "key", "apikey", "api_key", "servicekey", "token", "authorization",
            "password", "secret", "clientsecret", "client_secret"
    );

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        long startedAt = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        String parameters = safeParameters(request);
        log.info(
                "[HTTP REQUEST] 시작 - method={}, path={}, params={}, client={}",
                request.getMethod(),
                request.getRequestURI(),
                parameters,
                resolveClientAddress(request)
        );

        try {
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.error(
                    "[HTTP REQUEST] 처리 예외 - method={}, path={}, elapsedMs={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    elapsedMillis(startedAt),
                    e
            );
            throw e;
        } finally {
            long elapsedMs = elapsedMillis(startedAt);
            int status = response.getStatus();
            if (status >= 500) {
                log.error(
                        "[HTTP REQUEST] 종료 - method={}, path={}, status={}, elapsedMs={}, contentType={}",
                        request.getMethod(), request.getRequestURI(), status, elapsedMs, response.getContentType()
                );
            } else if (status >= 400) {
                log.warn(
                        "[HTTP REQUEST] 종료 - method={}, path={}, status={}, elapsedMs={}, contentType={}",
                        request.getMethod(), request.getRequestURI(), status, elapsedMs, response.getContentType()
                );
            } else {
                log.info(
                        "[HTTP REQUEST] 종료 - method={}, path={}, status={}, elapsedMs={}, contentType={}",
                        request.getMethod(), request.getRequestURI(), status, elapsedMs, response.getContentType()
                );
            }
            MDC.remove("requestId");
        }
    }

    private String resolveRequestId(String suppliedRequestId) {
        if (suppliedRequestId != null && suppliedRequestId.matches("[A-Za-z0-9._-]{8,64}")) {
            return suppliedRequestId;
        }
        return UUID.randomUUID().toString().substring(0, 12);
    }

    private String safeParameters(HttpServletRequest request) {
        List<String> values = new ArrayList<>();
        request.getParameterMap().entrySet().stream()
                .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                .forEach(entry -> {
                    String name = entry.getKey();
                    String normalizedName = name.toLowerCase(Locale.ROOT).replace("-", "");
                    String value = REDACTED_PARAMETER_NAMES.contains(normalizedName)
                            ? "***"
                            : abbreviate(String.join(",", entry.getValue()), 160);
                    values.add(name + "=" + value);
                });
        return values.isEmpty() ? "-" : String.join("&", values);
    }

    private String resolveClientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return abbreviate(forwarded.split(",")[0].trim(), 64);
        }
        return request.getRemoteAddr();
    }

    private String abbreviate(String value, int limit) {
        String singleLine = value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ");
        return singleLine.length() <= limit ? singleLine : singleLine.substring(0, limit) + "...";
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
