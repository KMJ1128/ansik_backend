package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Service
public class KakaoService {
    private static final Logger log = LoggerFactory.getLogger(KakaoService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${api.kakao.key}")
    private String kakaoKey;

    public KakaoService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restTemplate = new RestTemplate(factory);
    }

    // 🔥 좌표가 있을 경우 반경 2km 이내 '거리순'으로 먼저 검색하여 동명이인 가게 오류 해결
    public ResponseEntity<String> searchPlace(String query, Double mapX, Double mapY) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/local/search/keyword.json")
                    .queryParam("query", query);

            if (mapX != null && mapY != null && mapX != 0.0 && mapY != 0.0) {
                builder.queryParam("x", mapX)
                        .queryParam("y", mapY)
                        .queryParam("radius", 2000)
                        .queryParam("sort", "distance");
            }

            URI uri = builder.build().encode().toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", kakaoKey);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            return restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
        } catch (Exception e) {
            log.error("[KAKAO PLACE] 장소 검색 실패 - query={}", query, e);
            return ResponseEntity.status(500).body("{}");
        }
    }
}