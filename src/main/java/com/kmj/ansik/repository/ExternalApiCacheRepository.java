package com.kmj.ansik.repository;

import com.kmj.ansik.entity.ExternalApiCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface ExternalApiCacheRepository extends JpaRepository<ExternalApiCache, String> {
    long deleteByStaleUntilBefore(Instant cutoff);
}
