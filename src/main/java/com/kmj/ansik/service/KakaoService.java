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
public class KakaoService {
    private static final Logger log = LoggerFactory.getLogger(KakaoService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${api.kakao.key}")
    private String kakaoKey;

    public ResponseEntity<String> searchPlace(String query) {
        try {
            URI uri = UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/local/search/keyword.json")
                    .queryParam("query", query)
                    .build().encode().toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", kakaoKey);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            return restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
        } catch (Exception e) {
            log.error("[KAKAO PLACE] 장소 검색 실패 - query={}", query, e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    public JsonNode searchKeywordNode(String query, int size, double x, double y, int radius) {
        try {
            URI uri = UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/local/search/keyword.json")
                    .queryParam("query", query)
                    .queryParam("size", size)
                    .queryParam("x", x)
                    .queryParam("y", y)
                    .queryParam("radius", radius)
                    .queryParam("sort", "accuracy")
                    .build().encode().toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", kakaoKey);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
            if (response.getBody() != null) {
                return mapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.error("[KAKAO PLACE] 노드 검색 실패 - query={}", query, e);
        }
        return null;
    }
}