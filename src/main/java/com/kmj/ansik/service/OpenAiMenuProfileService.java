package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kmj.ansik.dto.MenuProfileDto;
import com.kmj.ansik.dto.MenuProfileDto.NutritionInfo;
import com.kmj.ansik.logging.ExternalApiLoggingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class OpenAiMenuProfileService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiMenuProfileService.class);
    private static final Duration CACHE_TTL = Duration.ofDays(7);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final KFindNutritionService nutritionService;
    private final PersistentCacheService persistentCacheService;
    private final ExternalApiBulkhead bulkhead;
    private final Cache<String, CacheEntry> cache = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterWrite(CACHE_TTL)
            .build();

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.model:gpt-5.6-luna}")
    private String model;

    @Value("${openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    public OpenAiMenuProfileService(
            KFindNutritionService nutritionService,
            PersistentCacheService persistentCacheService,
            ExternalApiBulkhead bulkhead
    ) {
        this.nutritionService = nutritionService;
        this.persistentCacheService = persistentCacheService;
        this.bulkhead = bulkhead;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getInterceptors().add(new ExternalApiLoggingInterceptor("OPENAI MENU PROFILE"));
    }

    public MenuProfileDto getProfile(String menuName, String language) {
        String safeName = menuName == null ? "" : menuName.trim();
        String lang = normalizeLanguage(language);
        MenuProfileDto fallback = fallbackProfile(safeName, lang, nutritionQuery(safeName));
        if (safeName.isBlank()) {
            return fallback;
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[OPENAI PROFILE] API 키가 없어 일반 설명을 제공하지 않음 - menuName='{}'", safeName);
            return fallback;
        }

        String cacheKey = ("localized-v2|" + safeName + "|" + lang).toLowerCase(Locale.ROOT);
        CacheEntry cached = cache.getIfPresent(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            log.info("[OPENAI PROFILE] 캐시 사용 - menuName='{}', language={}", safeName, lang);
            return cached.profile();
        }

        try {
            var persistent = persistentCacheService.get("openai-menu-profile", cacheKey);
            if (persistent.isPresent()) {
                MenuProfileDto profile = mapper.readValue(persistent.get(), MenuProfileDto.class);
                cache.put(cacheKey, new CacheEntry(profile, Instant.now().plus(CACHE_TTL)));
                log.info("[OPENAI PROFILE] MySQL 캐시 사용 - menuName='{}', language={}", safeName, lang);
                return profile;
            }
        } catch (Exception cacheReadFailure) {
            log.warn("[OPENAI PROFILE] MySQL 캐시 역직렬화 실패 - menuName='{}'", safeName, cacheReadFailure);
        }

        MenuProfileDto profile = requestProfile(safeName, lang, fallback);
        if (profile.matchStatus().startsWith("OPENAI")) {
            cache.put(cacheKey, new CacheEntry(profile, Instant.now().plus(CACHE_TTL)));
            try {
                persistentCacheService.put(
                        "openai-menu-profile", cacheKey, mapper.writeValueAsString(profile),
                        Duration.ofDays(30), Duration.ofDays(30)
                );
            } catch (Exception cacheWriteFailure) {
                log.warn("[OPENAI PROFILE] MySQL 캐시 직렬화 실패 - menuName='{}'", safeName, cacheWriteFailure);
            }
        }
        return profile;
    }

    private MenuProfileDto requestProfile(String menuName, String language, MenuProfileDto fallback) {
        long startedAt = System.nanoTime();
        log.info("[OPENAI PROFILE] 메뉴 설명 생성 시작 - menuName='{}', language={}, model={}",
                menuName, language, model);
        try {
            ObjectNode request = mapper.createObjectNode();
            request.put("model", model);
            request.put("store", false);
            request.put("max_output_tokens", 500);
            request.set("reasoning", mapper.createObjectNode().put("effort", "none"));
            request.put("instructions", instructions(language));
            request.put("input", "Menu text: " + menuName);
            ObjectNode textConfig = mapper.createObjectNode();
            textConfig.put("verbosity", "low");
            textConfig.set("format", structuredOutputFormat());
            request.set("text", textConfig);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            java.util.function.Supplier<ResponseEntity<String>> openAiCall = () -> restTemplate.exchange(
                    URI.create(baseUrl.replaceAll("/$", "") + "/responses"),
                    HttpMethod.POST, new HttpEntity<>(request.toString(), headers), String.class
            );
            ResponseEntity<String> response = bulkhead.openAi(openAiCall);

            JsonNode responseRoot = mapper.readTree(response.getBody());
            String outputText = extractOutputText(responseRoot);
            JsonNode result = mapper.readTree(outputText);
            log.info(
                    "[OPENAI PROFILE] 모델 응답 수신 - menuName='{}', responseId={}, status={}, inputTokens={}, outputTokens={}, elapsedMs={}",
                    menuName,
                    responseRoot.path("id").asText(""),
                    responseRoot.path("status").asText(""),
                    responseRoot.path("usage").path("input_tokens").asInt(0),
                    responseRoot.path("usage").path("output_tokens").asInt(0),
                    elapsedMillis(startedAt)
            );

            if (!result.path("isFood").asBoolean(false)) {
                log.info("[OPENAI PROFILE] 음식으로 판단되지 않음 - menuName='{}'", menuName);
                return fallback;
            }

            String description = result.path("description").asText("").trim();
            if (description.isBlank()) {
                return fallback;
            }

            String canonicalKoreanName = result.path("canonicalKoreanName").asText("").trim();
            NutritionInfo nutrition = nutritionService.findByKoreanFoodName(canonicalKoreanName).orElse(null);
            MenuProfileDto profile = new MenuProfileDto(
                    result.path("displayName").asText(menuName),
                    canonicalKoreanName,
                    description,
                    stringList(result.path("tasteTags")),
                    stringList(result.path("typicalIngredients")),
                    stringList(result.path("possibleAllergens")),
                    nutrition,
                    "OPENAI_GENERATED",
                    "OPENAI_GENERAL_KNOWLEDGE",
                    "",
                    disclaimer(language)
            );
            log.info("[OPENAI PROFILE] 메뉴 설명 생성 완료 - menuName='{}', ingredientCount={}, allergenCount={}, elapsedMs={}",
                    menuName, profile.typicalIngredients().size(), profile.possibleAllergens().size(),
                    elapsedMillis(startedAt));
            return profile;
        } catch (Exception e) {
            log.warn("[OPENAI PROFILE] 메뉴 설명 생성 실패 - menuName='{}', elapsedMs={}, errorType={}, message={}",
                    menuName, elapsedMillis(startedAt), e.getClass().getSimpleName(), e.getMessage(), e);
            return fallback;
        }
    }

    private String instructions(String language) {
        return """
                Explain a food menu item for an international traveler.
                First decide whether the input is a recognizable food or drink menu item.
                The input may be Korean, English, Japanese, Chinese, transliterated, or contain suffixes such as 'etc.'.
                Describe the general dish, taste, and typical ingredients. Do not claim a restaurant's exact recipe.
                Do not invent unusual ingredients. List possible allergens only when they are directly implied by typical ingredients; otherwise return an empty array.
                Never treat unrelated legal, real-estate, office, warehouse, or product text as a food explanation.
                Write displayName, description, taste tags, ingredients, and allergens in language: %s.
                displayName must explain the dish name in that language, not only transliterate Korean.
                Keep the description clear and under 45 words and return at most 8 items in each array.
                """.formatted(language);
    }

    private ObjectNode structuredOutputFormat() throws Exception {
        JsonNode schema = mapper.readTree("""
                {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "isFood": {"type": "boolean"},
                    "displayName": {"type": "string"},
                    "canonicalKoreanName": {"type": "string"},
                    "description": {"type": "string"},
                    "tasteTags": {"type": "array", "items": {"type": "string"}},
                    "typicalIngredients": {"type": "array", "items": {"type": "string"}},
                    "possibleAllergens": {"type": "array", "items": {"type": "string"}}
                  },
                  "required": ["isFood", "displayName", "canonicalKoreanName", "description", "tasteTags", "typicalIngredients", "possibleAllergens"]
                }
                """);
        ObjectNode format = mapper.createObjectNode();
        format.put("type", "json_schema");
        format.put("name", "menu_profile");
        format.put("strict", true);
        format.set("schema", schema);
        return format;
    }

    private String extractOutputText(JsonNode response) {
        for (JsonNode output : response.path("output")) {
            if (!"message".equals(output.path("type").asText())) continue;
            for (JsonNode content : output.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    String text = content.path("text").asText("");
                    if (!text.isBlank()) return text;
                }
            }
        }
        throw new IllegalStateException("OpenAI response did not contain output_text");
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank() && !values.contains(value)) values.add(value);
            if (values.size() >= 10) break;
        }
        return List.copyOf(values);
    }

    private String normalizeLanguage(String language) {
        if (language == null) return "ko";
        String value = language.toLowerCase(Locale.ROOT);
        if (value.startsWith("en")) return "en";
        if (value.startsWith("ja")) return "ja";
        if (value.startsWith("zh")) return "zh-CN";
        return "ko";
    }

    private MenuProfileDto fallbackProfile(String menuName, String language, String nutritionQuery) {
        NutritionInfo nutrition = nutritionService.findByKoreanFoodName(nutritionQuery).orElse(null);
        return new MenuProfileDto(
                menuName,
                nutritionQuery,
                "",
                List.of(),
                List.of(),
                List.of(),
                nutrition,
                nutrition == null ? "NO_MATCH" : "K_FIND_ONLY",
                nutrition == null ? "" : "K-FIND",
                "",
                disclaimer(language)
        );
    }

    private String nutritionQuery(String menuName) {
        if (menuName == null || !menuName.matches(".*[가-힣].*")) return "";
        return menuName
                .replaceAll("(?i)(대표|메뉴|특선|세트|코스|정식)", " ")
                .split("[/,·(]|\\s+등(?:\\s|$)")[0]
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String disclaimer(String language) {
        return switch (language) {
            case "en" -> "This is an AI-generated general dish explanation, not the restaurant's exact recipe. Confirm ingredients and allergens directly with the restaurant.";
            case "ja" -> "AIが作成した一般的な料理説明で、店舗の正確なレシピではありません。食材とアレルゲンは店舗に直接ご確認ください。";
            case "zh-CN" -> "这是AI生成的一般菜品说明，并非餐厅的准确配方。食材和过敏原请直接向餐厅确认。";
            default -> "AI가 생성한 일반적인 음식 설명이며 식당의 정확한 조리법은 아닙니다. 식재료와 알레르기 성분은 식당에 직접 확인하세요.";
        };
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private record CacheEntry(MenuProfileDto profile, Instant expiresAt) {
    }
}
