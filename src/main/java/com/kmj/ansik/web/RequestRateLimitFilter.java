package com.kmj.ansik.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Component
public class RequestRateLimitFilter extends OncePerRequestFilter {

    private final Cache<String, WindowCounter> counters = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(3))
            .build();

    private final int generalLimit;
    private final int aiLimit;

    public RequestRateLimitFilter(
            @Value("${traffic.rate-limit.general-per-minute:120}") int generalLimit,
            @Value("${traffic.rate-limit.ai-per-minute:8}") int aiLimit
    ) {
        this.generalLimit = Math.max(10, generalLimit);
        this.aiLimit = Math.max(1, aiLimit);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean expensiveAiRequest = path.startsWith("/api/ai/")
                || path.equals("/api/restaurants/menu-guide")
                || path.equals("/api/menu/profile");
        int limit = expensiveAiRequest ? aiLimit : generalLimit;
        String key = request.getRemoteAddr() + "|" + (expensiveAiRequest ? "ai" : "general");
        long minute = System.currentTimeMillis() / 60_000L;
        WindowCounter counter = counters.asMap().compute(key, (ignored, existing) -> {
            if (existing == null || existing.minute() != minute) return new WindowCounter(minute, 1);
            return new WindowCounter(minute, existing.count() + 1);
        });

        if (counter != null && counter.count() > limit) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", "60");
            response.getWriter().write("{\"error\":\"TOO_MANY_REQUESTS\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private record WindowCounter(long minute, int count) {
    }
}
