package com.kmj.ansik.repository;

import com.kmj.ansik.entity.ExternalApiCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ExternalApiCacheRepositoryTest {

    @Autowired
    private ExternalApiCacheRepository repository;

    @Test
    void cacheTableCanStoreAndReadJsonWithoutImageBinary() {
        Instant now = Instant.now();
        ExternalApiCache entry = new ExternalApiCache(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "test",
                "gyeongbokgung|ko",
                "{\"title\":\"경복궁\",\"imageUrl\":\"https://example.com/image.jpg\"}",
                now.plusSeconds(60),
                now.plusSeconds(120),
                now
        );

        repository.saveAndFlush(entry);

        assertThat(repository.findById(entry.getKeyHash()))
                .isPresent()
                .get()
                .extracting(ExternalApiCache::getPayload)
                .asString()
                .contains("경복궁", "imageUrl");
    }
}
