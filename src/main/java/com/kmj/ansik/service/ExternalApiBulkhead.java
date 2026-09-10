package com.kmj.ansik.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class ExternalApiBulkhead {

    private final Semaphore openAi;
    private final Semaphore tourApi;
    private final Semaphore naver;
    private final long waitMillis;

    public ExternalApiBulkhead(
            @Value("${traffic.bulkhead.openai:2}") int openAiPermits,
            @Value("${traffic.bulkhead.tourapi:8}") int tourApiPermits,
            @Value("${traffic.bulkhead.naver:4}") int naverPermits,
            @Value("${traffic.bulkhead.wait-ms:1500}") long waitMillis
    ) {
        this.openAi = new Semaphore(Math.max(1, openAiPermits), true);
        this.tourApi = new Semaphore(Math.max(1, tourApiPermits), true);
        this.naver = new Semaphore(Math.max(1, naverPermits), true);
        this.waitMillis = Math.max(100, waitMillis);
    }

    public <T> T openAi(Supplier<T> action) {
        return execute("OpenAI", openAi, action);
    }

    public <T> T tourApi(Supplier<T> action) {
        return execute("TourAPI", tourApi, action);
    }

    public <T> T naver(Supplier<T> action) {
        return execute("Naver", naver, action);
    }

    private <T> T execute(String provider, Semaphore semaphore, Supplier<T> action) {
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
            if (!acquired) throw new IllegalStateException(provider + " request queue is full");
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(provider + " request was interrupted", e);
        } finally {
            if (acquired) semaphore.release();
        }
    }
}
