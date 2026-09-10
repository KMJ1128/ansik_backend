package com.kmj.ansik.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kmj.ansik.entity.ExternalApiCache;
import com.kmj.ansik.repository.ExternalApiCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class PersistentCacheService {

    private static final Logger log = LoggerFactory.getLogger(PersistentCacheService.class);

    private final ExternalApiCacheRepository repository;
    private final Cache<String, CacheValue> localCache;
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    public PersistentCacheService(
            ExternalApiCacheRepository repository,
            @Value("${cache.local.max-weight-bytes:33554432}") long maximumWeightBytes
    ) {
        this.repository = repository;
        this.localCache = Caffeine.newBuilder()
                .maximumWeight(Math.max(4_194_304L, maximumWeightBytes))
                .weigher((String key, CacheValue value) -> Math.min(
                        Integer.MAX_VALUE,
                        key.length() * 2 + value.payload().length() * 2 + 160
                ))
                .expireAfterAccess(Duration.ofDays(8))
                .recordStats()
                .build();
    }

    public String getOrLoad(
            String namespace,
            String cacheKey,
            Duration ttl,
            Duration staleTtl,
            Supplier<String> loader
    ) {
        String hash = hash(namespace, cacheKey);
        Optional<String> fresh = findLocal(hash, false);
        if (fresh.isPresent()) return fresh.get();

        CompletableFuture<String> ownRequest = new CompletableFuture<>();
        CompletableFuture<String> existingRequest = inFlight.putIfAbsent(hash, ownRequest);
        if (existingRequest != null) {
            log.info("[CACHE] 동일 요청 병합 - namespace={}, keyHash={}", namespace, shortHash(hash));
            return existingRequest.join();
        }

        try {
            // Only the request that owns this key checks MySQL. Other concurrent
            // requests wait for the same result instead of issuing duplicate SELECTs.
            Optional<String> rechecked = find(hash, false);
            if (rechecked.isPresent()) {
                ownRequest.complete(rechecked.get());
                return rechecked.get();
            }

            String loaded;
            try {
                loaded = loader.get();
            } catch (RuntimeException externalFailure) {
                Optional<String> stale = find(hash, true);
                if (stale.isPresent()) {
                    log.warn("[CACHE] 외부 API 실패로 만료 캐시 제공 - namespace={}, keyHash={}",
                            namespace, shortHash(hash));
                    ownRequest.complete(stale.get());
                    return stale.get();
                }
                throw externalFailure;
            }

            if (loaded != null && !loaded.isBlank()) {
                putHashed(hash, namespace, cacheKey, loaded, ttl, staleTtl);
            }
            ownRequest.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            ownRequest.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(hash, ownRequest);
        }
    }

    public Optional<String> get(String namespace, String cacheKey) {
        return find(hash(namespace, cacheKey), false);
    }

    public void put(
            String namespace,
            String cacheKey,
            String payload,
            Duration ttl,
            Duration staleTtl
    ) {
        if (payload == null || payload.isBlank()) return;
        putHashed(hash(namespace, cacheKey), namespace, cacheKey, payload, ttl, staleTtl);
    }

    private Optional<String> find(String hash, boolean allowStale) {
        Optional<String> local = findLocal(hash, allowStale);
        if (local.isPresent()) return local;

        return findDatabase(hash, allowStale);
    }

    private Optional<String> findLocal(String hash, boolean allowStale) {
        Instant now = Instant.now();
        CacheValue local = localCache.getIfPresent(hash);
        if (local != null && (local.expiresAt().isAfter(now)
                || allowStale && local.staleUntil().isAfter(now))) {
            return Optional.of(local.payload());
        }
        return Optional.empty();
    }

    private Optional<String> findDatabase(String hash, boolean allowStale) {
        Instant now = Instant.now();
        try {
            return repository.findById(hash)
                    .filter(entry -> entry.getStaleUntil().isAfter(now))
                    .map(entry -> {
                        localCache.put(hash, new CacheValue(
                                entry.getPayload(), entry.getExpiresAt(), entry.getStaleUntil()
                        ));
                        return entry.getExpiresAt().isAfter(now) || allowStale
                                ? entry.getPayload()
                                : null;
                    });
        } catch (RuntimeException databaseFailure) {
            log.warn("[CACHE] MySQL 캐시 조회 실패 - keyHash={}", shortHash(hash), databaseFailure);
            return Optional.empty();
        }
    }

    private void putHashed(
            String hash,
            String namespace,
            String cacheKey,
            String payload,
            Duration ttl,
            Duration staleTtl
    ) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        Instant staleUntil = expiresAt.plus(staleTtl);
        localCache.put(hash, new CacheValue(payload, expiresAt, staleUntil));
        try {
            repository.save(new ExternalApiCache(
                    hash, namespace, cacheKey, payload, expiresAt, staleUntil, now
            ));
        } catch (RuntimeException databaseFailure) {
            log.warn("[CACHE] MySQL 저장 실패, 메모리 캐시는 유지 - namespace={}, keyHash={}",
                    namespace, shortHash(hash), databaseFailure);
        }
    }

    @Scheduled(cron = "${cache.cleanup.cron:0 20 4 * * *}")
    @Transactional
    public void deleteExpiredEntries() {
        long deleted = repository.deleteByStaleUntilBefore(Instant.now());
        if (deleted > 0) log.info("[CACHE] 만료된 MySQL 캐시 정리 - deleted={}", deleted);
    }

    public String stats() {
        return localCache.stats().toString();
    }

    private String hash(String namespace, String cacheKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((namespace + "\u0000" + cacheKey)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("캐시 키 해시 생성 실패", e);
        }
    }

    private String shortHash(String hash) {
        return hash.substring(0, Math.min(10, hash.length()));
    }

    private record CacheValue(String payload, Instant expiresAt, Instant staleUntil) {
    }
}
