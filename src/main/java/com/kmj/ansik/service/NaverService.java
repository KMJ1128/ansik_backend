package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
public class NaverService {

    private static final Logger log = LoggerFactory.getLogger(NaverService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${api.naver.client-id}")
    private String naverClientId;

    @Value("${api.naver.client-secret}")
    private String naverClientSecret;

    public NaverService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restTemplate = new RestTemplate(factory);
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
            log.error("[NAVER BLOG] 블로그 리뷰 수 검색 실패 - placeName={}", placeName, e);
        }
        return 0;
    }

    // 🔥 사용자님 맞춤형 아이디어 적용! "지역명 + 맛집 + 식당이름 + 후기"
    public ResponseEntity<String> getBlogReviewList(String placeName, String address, int start) {
        try {
            String region = "";
            if (address != null && !address.trim().isEmpty()) {
                String[] parts = address.split(" ");
                // 보통 주소의 두 번째 단어가 구/시/군 임 (예: "서울 강서구 공항대로" -> "강서구")
                if (parts.length >= 2) {
                    region = parts[1];
                    // "강서구" -> "강서", "원주시" -> "원주" 로 자름 (단, 중구, 서구 등 2글자는 예외)
                    if (region.endsWith("시") || region.endsWith("군") || region.endsWith("구")) {
                        if (region.length() > 2) {
                            region = region.substring(0, region.length() - 1);
                        }
                    }
                } else {
                    region = parts[0];
                }
            }

            // 검색어 최종 조립 (예: "용산 맛집 쌤쌤쌤 후기")
            String query = "";
            if (region.isEmpty()) {
                query = placeName + " 후기";
            } else {
                query = region + " 맛집 " + placeName + " 후기";
            }

            log.info("[NAVER BLOG] 최종 검색어 완성: {}", query); // 스프링부트 콘솔에서 검색어 조합 확인 가능!

            URI uri = UriComponentsBuilder.fromUriString("https://naverapihub.apigw.ntruss.com/search/v1/blog")
                    .queryParam("query", query)
                    .queryParam("display", 5) // 5개 단위로 무한 스크롤
                    .queryParam("start", start) // 페이징 번호
                    .queryParam("sort", "sim") // 정확도순
                    .build().encode().toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-NCP-APIGW-API-KEY-ID", naverClientId);
            headers.set("X-NCP-APIGW-API-KEY", naverClientSecret);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);

            if (response.getBody() != null) {
                JsonNode root = mapper.readTree(response.getBody());
                JsonNode items = root.path("items");
                ArrayNode cleanedItems = mapper.createArrayNode();

                if (items.isArray()) {
                    for (JsonNode item : items) {
                        ObjectNode cleanedItem = mapper.createObjectNode();
                        String title = item.path("title").asText().replaceAll("<[^>]*>", "").replace("&quot;", "\"").replace("&amp;", "&");
                        String description = item.path("description").asText().replaceAll("<[^>]*>", "").replace("&quot;", "\"").replace("&amp;", "&");
                        String link = item.path("link").asText();

                        cleanedItem.put("title", title);
                        cleanedItem.put("description", description);
                        cleanedItem.put("link", link);
                        cleanedItems.add(cleanedItem);
                    }
                }
                return ResponseEntity.ok(cleanedItems.toString());
            }
        } catch (Exception e) {
            log.error("[NAVER BLOG] 블로그 리뷰 리스트 검색 실패 - placeName={}", placeName, e);
        }
        return ResponseEntity.ok("[]");
    }
}