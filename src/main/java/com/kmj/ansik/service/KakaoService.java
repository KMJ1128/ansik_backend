package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.List;

@Service
public class KakaoService {
    private static final Logger log = LoggerFactory.getLogger(KakaoService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${api.kakao.key}")
    private String kakaoKey;

    public KakaoService() {
        // 💡 의존성 추가 없이 타임아웃 설정하는 방법
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); // 3초
        factory.setReadTimeout(3000);    // 3초
        this.restTemplate = new RestTemplate(factory);
    }

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

    public List<String> getKakaoImageSearch(String title, String address) {
        List<String> images = new ArrayList<>();
        try {
            String cleanTitle = title.replaceAll("\\s*\\(.*?\\)\\s*", "").replaceAll("\\s*\\[.*?\\]\\s*", "").trim();

            String region = "";
            if (address != null && !address.isBlank()) {
                String[] parts = address.split(" ");
                if (parts.length >= 2) region = parts[1].replace("시", "").replace("군", "").replace("구", "");
            }

            String query = region.isBlank() ? cleanTitle : region + " " + cleanTitle;

            URI uri = UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/search/image")
                    .queryParam("query", query)
                    .queryParam("size", 15)
                    .build().encode().toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", kakaoKey);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
            JsonNode root = mapper.readTree(response.getBody());
            JsonNode docs = root.path("documents");

            if (docs.isArray()) {
                for (JsonNode doc : docs) {
                    String collection = doc.path("collection").asText("");
                    if ("blog".equals(collection) || "cafe".equals(collection) || "etc".equals(collection)) {
                        String imageUrl = doc.path("image_url").asText("");
                        if (!imageUrl.isBlank()) {
                            images.add(imageUrl.replace("http://", "https://"));
                            if (images.size() >= 8) break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[KAKAO IMAGE SEARCH] 이미지 검색 실패: {}", title);
        }
        return images;
    }
}