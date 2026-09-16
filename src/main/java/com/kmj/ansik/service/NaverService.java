package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kmj.ansik.logging.ExternalApiLoggingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Service
public class NaverService {

    private static final Logger log = LoggerFactory.getLogger(NaverService.class);
    private final RestTemplate restTemplate;
    private final PersistentCacheService persistentCacheService;
    private final ExternalApiBulkhead bulkhead;
    private final ObjectMapper mapper = new ObjectMapper();
    @Value("${api.naver.client-id}")
    private String naverClientId;

    @Value("${api.naver.client-secret}")
    private String naverClientSecret;

    @Autowired
    public NaverService(
            PersistentCacheService persistentCacheService,
            ExternalApiBulkhead bulkhead
    ) {
        this.persistentCacheService = persistentCacheService;
        this.bulkhead = bulkhead;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getInterceptors().add(new ExternalApiLoggingInterceptor("NAVER SEARCH"));
    }

    public NaverService() {
        this(null, null);
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

            ResponseEntity<String> response = naverRequest(
                    () -> restTemplate.exchange(uri, HttpMethod.GET, entity, String.class)
            );
            if (response.getBody() != null) {
                JsonNode root = mapper.readTree(response.getBody());
                return root.path("total").asInt(0);
            }
        } catch (Exception e) {
            log.error("[NAVER BLOG] 블로그 리뷰 수 검색 실패 - placeName={}", placeName, e);
        }
        return 0;
    }

    private String normalizeForMatch(String value) {
        return cleanSearchText(value)
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^0-9a-z가-힣ぁ-んァ-ン一-龥]", "");
    }

    private String cleanSearchText(String value) {
        return value
                .replaceAll("<[^>]*>", " ")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public ResponseEntity<String> getBlogReviewList(String placeName, String address, int start) {
        try {
            String cleanPlaceName = cleanSearchText(placeName);
            List<String> addressSignals = addressSignals(address);
            String query = "\"" + cleanPlaceName + "\" " + String.join(" ", addressSignals) + " 후기";
            int rawStart = Math.max(1, ((Math.max(1, start) - 1) / 5) * 20 + 1);
            log.info("[NAVER BLOG] 식당 일치 검색 시작 - place='{}', addressSignals={}, rawStart={}",
                    cleanPlaceName, addressSignals, rawStart);

            URI uri = UriComponentsBuilder.fromUriString("https://naverapihub.apigw.ntruss.com/search/v1/blog")
                    .queryParam("query", query)
                    .queryParam("display", 20)
                    .queryParam("start", rawStart)
                    .queryParam("sort", "sim")
                    .build().encode().toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-NCP-APIGW-API-KEY-ID", naverClientId);
            headers.set("X-NCP-APIGW-API-KEY", naverClientSecret);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = naverRequest(
                    () -> restTemplate.exchange(uri, HttpMethod.GET, entity, String.class)
            );

            if (response.getBody() != null) {
                JsonNode root = mapper.readTree(response.getBody());
                JsonNode items = root.path("items");
                ArrayNode cleanedItems = mapper.createArrayNode();

                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String title = cleanSearchText(item.path("title").asText(""));
                        String description = cleanSearchText(item.path("description").asText(""));
                        String link = item.path("link").asText();

                        int matchScore = calculateReviewMatch(cleanPlaceName, addressSignals, title, description);
                        if (matchScore < 120) continue;

                        ObjectNode cleanedItem = mapper.createObjectNode();
                        cleanedItem.put("title", title);
                        cleanedItem.put("description", description);
                        cleanedItem.put("link", link);
                        cleanedItem.put("matchConfidence", matchScore >= 160 ? "high" : "medium");
                        cleanedItems.add(cleanedItem);
                        if (cleanedItems.size() >= 5) break;
                    }
                }
                log.info("[NAVER BLOG] 식당 일치 검색 완료 - place='{}', rawCount={}, matchedCount={}",
                        cleanPlaceName, items.isArray() ? items.size() : 0, cleanedItems.size());
                return ResponseEntity.ok(cleanedItems.toString());
            }
        } catch (Exception e) {
            log.error("[NAVER BLOG] 블로그 리뷰 리스트 검색 실패 - placeName={}", placeName, e);
        }
        return ResponseEntity.ok("[]");
    }

    private <T> T naverRequest(java.util.function.Supplier<T> action) {
        return bulkhead == null ? action.get() : bulkhead.naver(action);
    }

    private List<String> addressSignals(String address) {
        if (address == null || address.isBlank()) return List.of();
        return List.of(cleanSearchText(address).replace("(", " ").replace(")", " ").split("\\s+"))
                .stream()
                .map(token -> token.replaceAll("[,0-9-]", "").trim())
                .filter(token -> token.length() >= 2)
                .filter(token -> token.endsWith("구") || token.endsWith("군") || token.endsWith("동")
                        || token.endsWith("로") || token.endsWith("길"))
                .distinct()
                .limit(3)
                .toList();
    }

    private int calculateReviewMatch(
            String placeName,
            List<String> addressSignals,
            String title,
            String description
    ) {
        String placeKey = normalizeForMatch(placeName);
        String titleKey = normalizeForMatch(title);
        String combinedKey = normalizeForMatch(title + " " + description);
        if (placeKey.length() < 2 || !combinedKey.contains(placeKey)) return 0;

        if (!titleKey.contains(placeKey)) return 0;
        List<String> localSignals = addressSignals.stream()
                .filter(signal -> signal.endsWith("동") || signal.endsWith("로") || signal.endsWith("길"))
                .toList();
        if (localSignals.isEmpty() || localSignals.stream()
                .noneMatch(signal -> combinedKey.contains(normalizeForMatch(signal)))) return 0;
        int score = 130;
        int addressMatches = 0;
        for (String signal : addressSignals) {
            if (combinedKey.contains(normalizeForMatch(signal))) {
                addressMatches++;
                score += 30;
            }
        }
        if (!addressSignals.isEmpty() && addressMatches == 0) return 0;
        return score;
    }
}
