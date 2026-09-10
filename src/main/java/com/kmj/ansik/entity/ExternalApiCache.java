package com.kmj.ansik.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
        name = "external_api_cache",
        indexes = {
                @Index(name = "idx_external_cache_namespace", columnList = "namespace"),
                @Index(name = "idx_external_cache_stale_until", columnList = "stale_until")
        }
)
public class ExternalApiCache {

    @Id
    @Column(name = "key_hash", length = 64, nullable = false)
    private String keyHash;

    @Column(name = "namespace", length = 48, nullable = false)
    private String namespace;

    @Column(name = "cache_key", length = 768, nullable = false)
    private String cacheKey;

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "stale_until", nullable = false)
    private Instant staleUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExternalApiCache() {
    }

    public ExternalApiCache(
            String keyHash,
            String namespace,
            String cacheKey,
            String payload,
            Instant expiresAt,
            Instant staleUntil,
            Instant updatedAt
    ) {
        this.keyHash = keyHash;
        this.namespace = namespace;
        this.cacheKey = cacheKey;
        this.payload = payload;
        this.expiresAt = expiresAt;
        this.staleUntil = staleUntil;
        this.updatedAt = updatedAt;
    }

    public String getKeyHash() { return keyHash; }
    public String getNamespace() { return namespace; }
    public String getCacheKey() { return cacheKey; }
    public String getPayload() { return payload; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getStaleUntil() { return staleUntil; }
    public Instant getUpdatedAt() { return updatedAt; }
}
