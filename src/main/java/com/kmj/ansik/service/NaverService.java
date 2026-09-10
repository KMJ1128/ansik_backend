package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NaverService {

    private static final Logger log = LoggerFactory.getLogger(NaverService.class);
    private static final Set<String> FOOD_CONTEXT_WORDS = Set.of(
            "음식", "요리", "메뉴", "재료", "맛", "커피", "음료", "차", "고기", "국", "탕", "찌개",
            "밥", "면", "구이", "볶음", "조림", "튀김", "샐러드", "디저트", "빵", "레시피",
            "맛집", "먹방", "분식", "한식", "중식", "일식", "양식", "카페"
    );
    private static final Set<String> IRRELEVANT_CONTEXT_WORDS = Set.of(
            "주택", "사무실", "창고", "임차", "임대", "부동산", "대출", "법률", "보험", "채용",
            "게임", "공략", "캐릭터", "웹툰", "만화", "일러스트", "스킨", "퀘스트", "아이템",
            "로고", "포스터", "배경화면", "장난감", "피규어"
    );
    private static final Set<String> LOW_QUALITY_IMAGE_WORDS = Set.of(
            "쇼핑", "상품", "판매", "가격", "쿠폰", "광고", "배너", "전단지", "메뉴판",
            "포장", "밀키트", "간편식", "냉동", "배달", "리뷰 이벤트", "스티커", "아이콘"
    );
    private final RestTemplate restTemplate;
    private final PersistentCacheService persistentCacheService;
    private final ExternalApiBulkhead bulkhead;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<String>> imageCache = Collections.synchronizedMap(
            new LinkedHashMap<String, List<String>>(200, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > 200;
                }
            }
    );
    private final Map<String, CompletableFuture<List<String>>> imageInFlight = new ConcurrentHashMap<>();
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

    public List<String> getMenuImages(String menuName) {
        if (menuName == null || menuName.isBlank()) {
            return Collections.emptyList();
        }

        String cleanMenuName = normalizeImageQuery(menuName);
        if (cleanMenuName.isBlank()) {
            return Collections.emptyList();
        }
        String cacheKey = cleanMenuName.toLowerCase();
        List<String> cached = imageCache.get(cacheKey);
        if (cached != null) {
            log.info("[NAVER IMAGE] 캐시 사용 - query='{}', imageCount={}", cleanMenuName, cached.size());
            return cached;
        }
        if (persistentCacheService != null) {
            try {
                var persistent = persistentCacheService.get("naver-menu-images", cacheKey);
                if (persistent.isPresent()) {
                    List<String> images = mapper.readValue(
                            persistent.get(), new TypeReference<List<String>>() { }
                    );
                    imageCache.put(cacheKey, images);
                    log.info("[NAVER IMAGE] MySQL 캐시 사용 - query='{}', imageCount={}",
                            cleanMenuName, images.size());
                    return images;
                }
            } catch (Exception cacheReadFailure) {
                log.warn("[NAVER IMAGE] MySQL 캐시 역직렬화 실패 - query='{}'",
                        cleanMenuName, cacheReadFailure);
            }
        }

        CompletableFuture<List<String>> ownRequest = new CompletableFuture<>();
        CompletableFuture<List<String>> existingRequest = imageInFlight.putIfAbsent(cacheKey, ownRequest);
        if (existingRequest != null) {
            log.info("[NAVER IMAGE] 동일 음식 이미지 검색 중 - 기존 결과 대기, query='{}'", cleanMenuName);
            return existingRequest.join();
        }

        try {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://naverapihub.apigw.ntruss.com/search/v1/image")
                    .queryParam("query", cleanMenuName + " 음식 요리 레시피 맛집 -게임 -공략 -캐릭터 -웹툰 -일러스트")
                    .queryParam("display", 20)
                    .queryParam("start", 1)
                    .queryParam("sort", "sim")
                    .queryParam("filter", "large")
                    .queryParam("format", "json")
                    .build()
                    .encode()
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-NCP-APIGW-API-KEY-ID", naverClientId);
            headers.set("X-NCP-APIGW-API-KEY", naverClientSecret);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<String> response = naverRequest(() -> restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class
            ));

            JsonNode items = mapper.readTree(response.getBody()).path("items");
            List<ImageCandidate> images = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String title = cleanSearchText(item.path("title").asText(""));
                    String sourceLink = item.path("link").asText("");
                    int width = item.path("sizewidth").asInt(0);
                    int height = item.path("sizeheight").asInt(0);
                    int relevance = calculateImageRelevance(
                            cleanMenuName, title, sourceLink, width, height
                    );
                    if (relevance <= 0) {
                        continue;
                    }
                    // Naver's thumbnail proxy is more stable than hotlinking the publisher's original image.
                    String image = item.path("thumbnail").asText("");
                    if (image.isBlank()) {
                        image = item.path("link").asText("");
                    }
                    if (!image.isBlank()) {
                        images.add(new ImageCandidate(image, relevance));
                    }
                }
            }

            List<String> result = images.stream()
                    .sorted((left, right) -> Integer.compare(right.relevance(), left.relevance()))
                    .filter(candidate -> candidate.relevance() >= 130)
                    .map(ImageCandidate::url)
                    .distinct()
                    .limit(3)
                    .toList();
            if (!result.isEmpty()) {
                imageCache.put(cacheKey, result);
                if (persistentCacheService != null) {
                    try {
                        persistentCacheService.put(
                                "naver-menu-images", cacheKey, mapper.writeValueAsString(result),
                                Duration.ofDays(7), Duration.ofDays(7)
                        );
                    } catch (Exception cacheWriteFailure) {
                        log.warn("[NAVER IMAGE] MySQL 캐시 직렬화 실패 - query='{}'",
                                cleanMenuName, cacheWriteFailure);
                    }
                }
            }
            ownRequest.complete(result);
            log.info("[NAVER IMAGE] 정확 음식명 검색 완료 - query='{}', reviewed={}, imageCount={}",
                    cleanMenuName, items.isArray() ? items.size() : 0, result.size());
            return result;
        } catch (Exception e) {
            log.warn("[NAVER IMAGE] 메뉴 이미지 검색 실패 - menuName={}", cleanMenuName, e);
            ownRequest.complete(Collections.emptyList());
            return Collections.emptyList();
        } finally {
            imageInFlight.remove(cacheKey, ownRequest);
        }
    }

    private String normalizeImageQuery(String value) {
        return cleanSearchText(value)
                .replaceAll("\\([^)]*(가격|원|대표|세트|코스)[^)]*\\)", " ")
                .replaceAll("[0-9][0-9,.]*\\s*원", " ")
                .replaceAll("(?i)\\b(대표|메뉴|특선|세트|코스|정식|etc)\\b", " ")
                .replaceAll("\\s+등(?:\\s|$)", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int calculateImageRelevance(
            String menuName,
            String title,
            String sourceLink,
            int width,
            int height
    ) {
        String menuKey = normalizeForMatch(menuName);
        String titleKey = normalizeForMatch(title);
        if (menuKey.length() < 2 || titleKey.isBlank()) return 0;

        String combined = (title + " " + sourceLink).toLowerCase();
        if (IRRELEVANT_CONTEXT_WORDS.stream().anyMatch(combined::contains)) return 0;
        if (LOW_QUALITY_IMAGE_WORDS.stream().anyMatch(combined::contains)) return 0;
        boolean exactDishName = titleKey.contains(menuKey);
        if (!exactDishName) return 0;
        boolean foodContext = FOOD_CONTEXT_WORDS.stream().anyMatch(combined::contains);
        if (!foodContext && !exactDishName) return 0;

        if (width > 0 && height > 0) {
            if (width < 500 || height < 350) return 0;
            double aspectRatio = (double) width / height;
            if (aspectRatio < 0.5 || aspectRatio > 2.0) return 0;
        }

        int score = 100;
        for (String token : meaningfulMenuTokens(menuName)) {
            if (titleKey.contains(normalizeForMatch(token))) score += 25;
        }
        if (foodContext) score += 20;
        if (width >= 800 && height >= 600) score += 25;
        else if (width >= 500 && height >= 350) score += 10;
        if (combined.contains("레시피") || combined.contains("요리") || combined.contains("맛집")) {
            score += 10;
        }
        return score >= 25 ? score : 0;
    }

    private record ImageCandidate(String url, int relevance) {
    }

    private List<String> meaningfulMenuTokens(String menuName) {
        return List.of(cleanSearchText(menuName).split("[^0-9A-Za-z가-힣ぁ-んァ-ン一-龥]+"))
                .stream()
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !Set.of("대표", "메뉴", "특선", "세트", "정식", "코스").contains(token))
                .toList();
    }

    private String normalizeForMatch(String value) {
        return cleanSearchText(value)
                .toLowerCase()
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

        int score = titleKey.contains(placeKey) ? 130 : 100;
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
