package com.kmj.ansik.service;

import com.kmj.ansik.logging.ExternalApiLoggingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Service
public class KakaoService {

    private static final Logger log = LoggerFactory.getLogger(KakaoService.class);
    private static final String KEYWORD_URL = "https://dapi.kakao.com/v2/local/search/keyword.json";
    private static final String CATEGORY_URL = "https://dapi.kakao.com/v2/local/search/category.json";
    private static final String RESTAURANT_CATEGORY_CODE = "FD6";

    private final RestTemplate restTemplate;

    @Value("${api.kakao.key}")
    private String kakaoKey;

    public KakaoService() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.getInterceptors().add(new ExternalApiLoggingInterceptor("KAKAO LOCAL"));
    }

    public ResponseEntity<String> searchPlace(String query, Double mapX, Double mapY) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString(KEYWORD_URL)
                    .queryParam("query", query);

            if (mapX != null && mapY != null && mapX != 0.0 && mapY != 0.0) {
                builder.queryParam("x", mapX)
                        .queryParam("y", mapY)
                        .queryParam("radius", 2000)
                        .queryParam("sort", "distance");
            }

            return execute(builder.build().encode().toUri());
        } catch (Exception e) {
            log.error("[KAKAO PLACE] 장소 검색 실패 - query={}", query, e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    public ResponseEntity<String> searchNearbyRestaurants(
            double mapX,
            double mapY,
            int radius,
            int page
    ) {
        try {
            int safeRadius = Math.max(100, Math.min(radius, 20000));
            int safePage = Math.max(1, Math.min(page, 45));

            URI uri = UriComponentsBuilder
                    .fromUriString(CATEGORY_URL)
                    .queryParam("category_group_code", RESTAURANT_CATEGORY_CODE)
                    .queryParam("x", mapX)
                    .queryParam("y", mapY)
                    .queryParam("radius", safeRadius)
                    .queryParam("sort", "distance")
                    .queryParam("page", safePage)
                    .queryParam("size", 15)
                    .build()
                    .encode()
                    .toUri();

            return execute(uri);
        } catch (Exception e) {
            log.error(
                    "[KAKAO RESTAURANT] 주변 음식점 검색 실패 - x={}, y={}, radius={}, page={}",
                    mapX,
                    mapY,
                    radius,
                    page,
                    e
            );
            return ResponseEntity.status(500).body("{}");
        }
    }

    private ResponseEntity<String> execute(URI uri) {
        HttpHeaders headers = new HttpHeaders();
        String authorization = kakaoKey == null ? "" : kakaoKey.trim();
        if (!authorization.regionMatches(true, 0, "KakaoAK ", 0, 8)) {
            authorization = "KakaoAK " + authorization;
        }
        headers.set("Authorization", authorization);
        HttpEntity<String> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
    }
}
