package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Service
public class NaverService {
    private static final Logger log = LoggerFactory.getLogger(NaverService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${api.naver.client-id}")
    private String naverClientId;

    @Value("${api.naver.client-secret}")
    private String naverClientSecret;

    public ResponseEntity<String> searchImage(String query) {
        try {
            URI uri = UriComponentsBuilder.fromUriString("https://naverapihub.apigw.ntruss.com/search/v1/image")
                    .queryParam("display", 5) // 💡 5장 요청
                    .queryParam("sort", "sim")
                    .queryParam("query", query)
                    .build().encode().toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-NCP-APIGW-API-KEY-ID", naverClientId);
            headers.set("X-NCP-APIGW-API-KEY", naverClientSecret);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            return restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
        } catch (Exception e) {
            log.error("[NAVER IMAGE] 이미지 검색 실패 - query={}", query, e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    public int getBlogReviewCount(String placeName) {
        try {
            URI uri = UriComponentsBuilder.fromUriString("https://naverapihub.apigw.ntruss.com/search/v1/blog")
                    .queryParam("query", placeName)
                    .build().encode().toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-NCP-APIGW-API-KEY-ID", naverClientId);
            headers.set("X-NCP-APIGW-API-KEY", naverClientSecret);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);

            if (response.getBody() != null) {
                JsonNode root = mapper.readTree(response.getBody());
                return root.path("total").asInt(0);
            }
        } catch (Exception e) {
            log.error("[NAVER BLOG] 블로그 검색 실패 - place={}", placeName, e);
        }
        return 0;
    }
}