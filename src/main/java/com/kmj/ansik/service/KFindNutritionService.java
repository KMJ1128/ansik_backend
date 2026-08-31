package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmj.ansik.dto.MenuProfileDto.NutritionInfo;
import com.kmj.ansik.logging.ExternalApiLoggingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class KFindNutritionService {

    private static final Logger log = LoggerFactory.getLogger(KFindNutritionService.class);
    private static final String API_URL =
            "https://apis.data.go.kr/1471000/FoodNtrCpntDbInfo02/getFoodNtrCpntDbInq02";

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Optional<NutritionInfo>> cache = new ConcurrentHashMap<>();

    @Value("${api.kfind.key:}")
    private String apiKey;

    public KFindNutritionService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(3500);
        restTemplate = new RestTemplate(factory);
        restTemplate.getInterceptors().add(new ExternalApiLoggingInterceptor("K-FIND"));
    }

    public Optional<NutritionInfo> findByKoreanFoodName(String foodName) {
        if (foodName == null || foodName.isBlank()) {
            return Optional.empty();
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.debug("[K-FIND] 전용 API 키가 없어 영양정보 조회 생략 - foodName='{}'", foodName);
            return Optional.empty();
        }

        return cache.computeIfAbsent(foodName.trim(), this::requestNutrition);
    }

    private Optional<NutritionInfo> requestNutrition(String foodName) {
        long startedAt = System.nanoTime();
        log.info("[K-FIND] 영양정보 조회 시작 - foodName='{}'", foodName);
        try {
            URI uri = UriComponentsBuilder.fromUriString(API_URL)
                    .queryParam("serviceKey", apiKey)
                    .queryParam("pageNo", 1)
                    .queryParam("numOfRows", 10)
                    .queryParam("type", "json")
                    .queryParam("FOOD_NM_KR", foodName)
                    .build()
                    .encode()
                    .toUri();

            String body = restTemplate.getForObject(uri, String.class);
            if (body == null || body.isBlank() || body.trim().startsWith("<")) {
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(body);
            JsonNode items = root.path("body").path("items");
            if (items.isMissingNode()) {
                items = root.path("response").path("body").path("items");
            }
            if (items.isObject() && items.has("item")) {
                items = items.path("item");
            }
            if (!items.isArray() || items.isEmpty()) {
                log.info("[K-FIND] 검색 결과 없음 - foodName='{}', elapsedMs={}", foodName, elapsedMillis(startedAt));
                return Optional.empty();
            }

            JsonNode best = null;
            String normalizedTarget = normalize(foodName);
            for (JsonNode item : items) {
                String candidate = text(item, "FOOD_NM_KR", "foodNmKr", "foodNm");
                if (best == null || normalize(candidate).equals(normalizedTarget)) {
                    best = item;
                }
                if (normalize(candidate).equals(normalizedTarget)) {
                    break;
                }
            }

            if (best == null) {
                return Optional.empty();
            }

            NutritionInfo result = new NutritionInfo(
                    text(best, "FOOD_NM_KR", "foodNmKr", "foodNm"),
                    text(best, "Z10500", "FOOD_REF_AMT", "foodRefAmt"),
                    number(best, "AMT_NUM1", "energy"),
                    number(best, "AMT_NUM3", "carbohydrate"),
                    number(best, "AMT_NUM4", "protein"),
                    number(best, "AMT_NUM7", "fat"),
                    number(best, "AMT_NUM8", "sugar"),
                    number(best, "AMT_NUM14", "sodium"),
                    "K-FIND"
            );
            log.info("[K-FIND] 영양정보 조회 완료 - foodName='{}', matched='{}', elapsedMs={}",
                    foodName, result.matchedFoodName(), elapsedMillis(startedAt));
            return Optional.of(result);
        } catch (Exception e) {
            log.warn("[K-FIND] 영양정보 조회 실패 - foodName='{}', elapsedMs={}, errorType={}, reason={}",
                    foodName, elapsedMillis(startedAt), e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private Double number(JsonNode node, String... names) {
        String value = text(node, names).replace(",", "").replace("-", "").trim();
        if (value.isBlank()) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^0-9a-z가-힣]", "");
    }
}
