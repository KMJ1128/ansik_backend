package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kmj.ansik.dto.RestaurantMenuGuideDto;
import com.kmj.ansik.dto.RestaurantMenuGuideDto.MenuItem;
import com.kmj.ansik.logging.ExternalApiLoggingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Service
public class OpenAiRestaurantMenuService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiRestaurantMenuService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final int MAX_MENUS = 6;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final PersistentCacheService persistentCacheService;
    private final ExternalApiBulkhead bulkhead;
    private final Cache<String, CacheEntry> cache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(CACHE_TTL)
            .build();
    private final Map<String, CompletableFuture<RestaurantMenuGuideDto>> inFlight = new ConcurrentHashMap<>();

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.model:gpt-5.6-luna}")
    private String model;

    @Value("${openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Autowired
    public OpenAiRestaurantMenuService(
            NaverService naverService,
            PersistentCacheService persistentCacheService,
            ExternalApiBulkhead bulkhead
    ) {
        this.persistentCacheService = persistentCacheService;
        this.bulkhead = bulkhead;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getInterceptors().add(new ExternalApiLoggingInterceptor("OPENAI RESPONSES"));
    }

    public OpenAiRestaurantMenuService(NaverService naverService) {
        this(naverService, null, null);
    }

    public RestaurantMenuGuideDto getMenuGuide(
            String restaurantName,
            String address,
            String language
    ) {
        return getMenuGuide(restaurantName, address, language, List.of());
    }

    public RestaurantMenuGuideDto getMenuGuide(
            String restaurantName,
            String address,
            String language,
            List<String> menuHints
    ) {
        return getMenuGuide(restaurantName, address, language, menuHints, List.of());
    }

    public RestaurantMenuGuideDto getMenuGuide(
            String restaurantName,
            String address,
            String language,
            List<String> menuHints,
            List<String> healthConditions
    ) {
        String safeName = restaurantName == null ? "" : restaurantName.trim();
        String safeAddress = address == null ? "" : address.trim();
        String lang = normalizeLanguage(language);
        List<String> safeHints = normalizeMenuHints(menuHints);
        List<String> safeHealthConditions = normalizeHealthConditions(healthConditions);

        log.info("[OPENAI MENU] 메뉴 가이드 요청 - restaurant='{}', language={}, addressProvided={}, tourMenuHintCount={}, healthConditionCount={}",
                safeName, lang, !safeAddress.isBlank(), safeHints.size(), safeHealthConditions.size());

        if (safeName.isBlank()) {
            return RestaurantMenuGuideDto.unavailable(
                    safeName,
                    safeAddress,
                    "INVALID_REQUEST",
                    disclaimer(lang)
            );
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[OPENAI MENU] API 키가 없어 조사 생략 - restaurant='{}'", safeName);
            return RestaurantMenuGuideDto.unavailable(
                    safeName,
                    safeAddress,
                    "OPENAI_NOT_CONFIGURED",
                    disclaimer(lang)
            );
        }

        String cacheKey = normalizeCacheKey(safeName, safeAddress, lang, safeHints, safeHealthConditions);
        CacheEntry cached = cache.getIfPresent(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            log.info("[OPENAI MENU] 캐시 사용 - restaurant='{}', menuCount={}, expiresAt={}",
                    safeName, cached.guide().menus().size(), cached.expiresAt());
            return cached.guide();
        }

        if (safeHealthConditions.isEmpty() && persistentCacheService != null) {
            try {
                var persistent = persistentCacheService.get("openai-restaurant-menu", cacheKey);
                if (persistent.isPresent()) {
                    RestaurantMenuGuideDto guide = mapper.readValue(
                            persistent.get(), RestaurantMenuGuideDto.class
                    );
                    cache.put(cacheKey, new CacheEntry(guide, Instant.now().plus(CACHE_TTL)));
                    log.info("[OPENAI MENU] MySQL 캐시 사용 - restaurant='{}'", safeName);
                    return guide;
                }
            } catch (Exception cacheReadFailure) {
                log.warn("[OPENAI MENU] MySQL 캐시 역직렬화 실패 - restaurant='{}'",
                        safeName, cacheReadFailure);
            }
        }

        CompletableFuture<RestaurantMenuGuideDto> ownRequest = new CompletableFuture<>();
        CompletableFuture<RestaurantMenuGuideDto> existingRequest = inFlight.putIfAbsent(cacheKey, ownRequest);
        if (existingRequest != null) {
            log.info("[OPENAI MENU] 동일 요청 처리 중 - 기존 결과 대기, restaurant='{}'", safeName);
            return existingRequest.join();
        }
        try {
            RestaurantMenuGuideDto guide = requestMenuGuide(
                    safeName, safeAddress, lang, safeHints, safeHealthConditions
            );
            if ("OPENAI_READY".equals(guide.status())) {
                cache.put(cacheKey, new CacheEntry(guide, Instant.now().plus(CACHE_TTL)));
                if (safeHealthConditions.isEmpty() && persistentCacheService != null) {
                    try {
                        persistentCacheService.put(
                                "openai-restaurant-menu", cacheKey, mapper.writeValueAsString(guide),
                                Duration.ofDays(7), Duration.ofDays(7)
                        );
                    } catch (Exception cacheWriteFailure) {
                        log.warn("[OPENAI MENU] MySQL 캐시 직렬화 실패 - restaurant='{}'",
                                safeName, cacheWriteFailure);
                    }
                }
            }
            ownRequest.complete(guide);
            log.info("[OPENAI MENU] 메뉴 가이드 처리 종료 - restaurant='{}', status={}, menuCount={}",
                    safeName, guide.status(), guide.menus().size());
            return guide;
        } catch (RuntimeException e) {
            ownRequest.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(cacheKey, ownRequest);
        }
    }

    private RestaurantMenuGuideDto requestMenuGuide(
            String restaurantName,
            String address,
            String language,
            List<String> menuHints,
            List<String> healthConditions
    ) {
        long startedAt = System.nanoTime();
        boolean useTourMenuHints = !menuHints.isEmpty();
        log.info("[OPENAI MENU] 조사 시작 - restaurant='{}', model={}, mode={}, maxMenus={}, maxWebCalls={}",
                restaurantName, model, useTourMenuHints ? "TOUR_HINT" : "WEB_FALLBACK",
                MAX_MENUS, useTourMenuHints ? 0 : 1);
        try {
            ObjectNode request = mapper.createObjectNode();
            request.put("model", model);
            request.put("store", false);
            request.put("max_output_tokens", healthConditions.isEmpty()
                    ? (useTourMenuHints ? 1100 : 1300)
                    : (useTourMenuHints ? 1500 : 1700));
            request.set("reasoning", mapper.createObjectNode().put("effort", useTourMenuHints ? "none" : "low"));
            if (!useTourMenuHints) {
                request.put("max_tool_calls", 1);
                request.set("tools", mapper.createArrayNode().add(
                        mapper.createObjectNode()
                                .put("type", "web_search_preview")
                                .put("search_context_size", "low")
                ));
                request.set("include", mapper.createArrayNode().add("web_search_call.action.sources"));
            }
            request.put("instructions", instructions(language, useTourMenuHints, !healthConditions.isEmpty()));
            request.put("input", requestInput(restaurantName, address, menuHints, healthConditions));
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
            ResponseEntity<String> response = bulkhead == null
                    ? openAiCall.get()
                    : bulkhead.openAi(openAiCall);

            JsonNode responseRoot = mapper.readTree(response.getBody());
            log.info(
                    "[OPENAI MENU] 모델 응답 수신 - restaurant='{}', responseId={}, responseStatus={}, inputTokens={}, outputTokens={}, totalTokens={}, elapsedMs={}",
                    restaurantName,
                    responseRoot.path("id").asText(""),
                    responseRoot.path("status").asText(""),
                    responseRoot.path("usage").path("input_tokens").asInt(0),
                    responseRoot.path("usage").path("output_tokens").asInt(0),
                    responseRoot.path("usage").path("total_tokens").asInt(0),
                    elapsedMillis(startedAt)
            );
            String outputText = extractOutputText(responseRoot);
            JsonNode result = mapper.readTree(outputText);
            if (!result.path("matchedRestaurant").asBoolean(false)) {
                log.info("[OPENAI MENU] 정확한 식당 확인 실패 - restaurant='{}', elapsedMs={}",
                        restaurantName, elapsedMillis(startedAt));
                return RestaurantMenuGuideDto.unavailable(
                        restaurantName,
                        address,
                        "OPENAI_NO_MATCH",
                        disclaimer(language)
                );
            }

            List<MenuItem> menus = parseMenus(result.path("menus"), !useTourMenuHints);
            if (menus.isEmpty()) {
                log.info("[OPENAI MENU] 근거가 충분한 메뉴 없음 - restaurant='{}', elapsedMs={}",
                        restaurantName, elapsedMillis(startedAt));
                return RestaurantMenuGuideDto.unavailable(
                        restaurantName,
                        address,
                        "OPENAI_NO_MATCH",
                        disclaimer(language)
                );
            }

            RestaurantMenuGuideDto guide = new RestaurantMenuGuideDto(
                    restaurantName,
                    address,
                    menus,
                    "OPENAI_READY",
                    model,
                    disclaimer(language)
            );
            log.info("[OPENAI MENU] 메뉴 가이드 생성 완료 - restaurant='{}', menuCount={}, elapsedMs={}",
                    restaurantName, menus.size(), elapsedMillis(startedAt));
            return guide;
        } catch (Exception e) {
            log.warn("[OPENAI MENU] 식당 메뉴 조사 실패 - restaurant='{}', elapsedMs={}, errorType={}, message={}",
                    restaurantName, elapsedMillis(startedAt), e.getClass().getSimpleName(), e.getMessage(), e);
            return RestaurantMenuGuideDto.unavailable(
                    restaurantName,
                    address,
                    "OPENAI_ERROR",
                    disclaimer(language)
            );
        }
    }

    private List<MenuItem> parseMenus(JsonNode menuNodes, boolean requireSources) {
        if (!menuNodes.isArray()) {
            return List.of();
        }

        List<MenuCandidate> candidates = new ArrayList<>();
        for (JsonNode node : menuNodes) {
            if (candidates.size() >= MAX_MENUS) {
                break;
            }
            String name = node.path("name").asText("").trim();
            String description = node.path("description").asText("").trim();
            List<String> sources = stringList(node.path("sourceUrls")).stream()
                    .filter(this::isHttpUrl)
                    .distinct()
                    .limit(3)
                    .toList();
            if (name.isBlank() || description.isBlank() || (requireSources && sources.isEmpty())) {
                continue;
            }

            candidates.add(new MenuCandidate(
                    name,
                    node.path("displayName").asText(name).trim(),
                    description,
                    stringList(node.path("tasteTags")),
                    stringList(node.path("typicalIngredients")),
                    stringList(node.path("possibleAllergens")),
                    sources,
                    node.path("confidence").asText("medium"),
                    normalizeRiskLevel(node.path("healthRiskLevel").asText("unknown")),
                    node.path("healthRiskSummary").asText("").trim(),
                    stringList(node.path("healthRiskReasons")).stream().limit(4).toList(),
                    stringList(node.path("questionsForRestaurant")).stream().limit(3).toList()
            ));
        }
        return candidates.stream()
                .map(candidate -> new MenuItem(
                        candidate.name(),
                        candidate.description(),
                        candidate.tasteTags(),
                        candidate.typicalIngredients(),
                        candidate.possibleAllergens(),
                        candidate.sourceUrls(),
                        candidate.confidence(),
                        candidate.healthRiskLevel(),
                        candidate.healthRiskSummary(),
                        candidate.healthRiskReasons(),
                        candidate.questionsForRestaurant(),
                        candidate.displayName()
                ))
                .toList();
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values.stream().distinct().limit(12).toList();
    }

    private ObjectNode structuredOutputFormat() throws Exception {
        JsonNode schema = mapper.readTree("""
                {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "matchedRestaurant": {"type": "boolean"},
                    "menus": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "additionalProperties": false,
                        "properties": {
                          "name": {"type": "string"},
                          "displayName": {"type": "string"},
                          "description": {"type": "string"},
                          "tasteTags": {"type": "array", "items": {"type": "string"}},
                          "typicalIngredients": {"type": "array", "items": {"type": "string"}},
                          "possibleAllergens": {"type": "array", "items": {"type": "string"}},
                          "sourceUrls": {"type": "array", "items": {"type": "string"}},
                          "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
                          "healthRiskLevel": {"type": "string", "enum": ["safe", "caution", "avoid", "unknown"]},
                          "healthRiskSummary": {"type": "string"},
                          "healthRiskReasons": {"type": "array", "items": {"type": "string"}},
                          "questionsForRestaurant": {"type": "array", "items": {"type": "string"}}
                        },
                        "required": ["name", "displayName", "description", "tasteTags", "typicalIngredients", "possibleAllergens", "sourceUrls", "confidence", "healthRiskLevel", "healthRiskSummary", "healthRiskReasons", "questionsForRestaurant"]
                      }
                    }
                  },
                  "required": ["matchedRestaurant", "menus"]
                }
                """);
        ObjectNode format = mapper.createObjectNode();
        format.put("type", "json_schema");
        format.put("name", "restaurant_menu_guide");
        format.put("strict", true);
        format.set("schema", schema);
        return format;
    }

    private String extractOutputText(JsonNode response) {
        for (JsonNode output : response.path("output")) {
            if (!"message".equals(output.path("type").asText())) {
                continue;
            }
            for (JsonNode content : output.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    String text = content.path("text").asText("");
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        throw new IllegalStateException("OpenAI response did not contain output_text");
    }

    private String instructions(String language, boolean useTourMenuHints, boolean assessHealth) {
        String evidenceRule = useTourMenuHints
                ? "Treat the supplied TourAPI menu text as the restaurant menu evidence. Split combined text into at most 6 distinct menu items. sourceUrls must be empty."
                : "Use one concise web search to verify the restaurant by name and address. Return only sourced menu items and include 1-2 supporting URLs per item. If not verified, set matchedRestaurant=false.";
        String healthRule = assessHealth
                ? """
                Compare every menu with the supplied health conditions. This is cautious food screening, not diagnosis.
                Use avoid only for a direct allergy, prohibited ingredient, or clear dietary/religious conflict.
                Use caution for likely condition-related concerns (such as sodium, sugar, saturated fat, purines, potassium, or irritating ingredients) and whenever preparation is uncertain.
                Use safe only when no conflict is evident from typical ingredients; never claim guaranteed safety. Use unknown when evidence is insufficient.
                Write one short localized summary, at most 4 concise reasons, and at most 3 practical questions to ask the restaurant.
                """
                : "Set healthRiskLevel to unknown and all health assessment text/array fields to empty values.";
        return """
                Build a compact menu guide for travelers. %s
                Never invent restaurant-specific menus or recipes. Explain each listed dish generally.
                Keep name exactly as the original Korean menu for ordering and matching.
                Set displayName to a concise, understandable dish name in the output language.
                For English, Japanese and Chinese, translate the food meaning, not just Korean pronunciation.
                Include a short description, taste tags, typical ingredients, and only directly implied possible allergens.
                %s
                Use empty arrays when uncertain. Output language: %s. Maximum 6 menus; descriptions under 45 words.
                """.formatted(evidenceRule, healthRule, language);
    }

    private String requestInput(
            String restaurantName,
            String address,
            List<String> menuHints,
            List<String> healthConditions
    ) {
        StringBuilder input = new StringBuilder("Restaurant: ").append(restaurantName)
                .append("\nAddress: ").append(address);
        if (!menuHints.isEmpty()) {
            input.append("\nTourAPI menu text: ").append(String.join(" | ", menuHints));
        }
        if (!healthConditions.isEmpty()) {
            input.append("\nUser health/diet/allergy/religious conditions: ")
                    .append(String.join(" | ", healthConditions));
        }
        return input.toString();
    }

    private boolean isHttpUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String normalizeLanguage(String language) {
        if (language == null) return "ko";
        String value = language.toLowerCase(Locale.ROOT);
        if (value.startsWith("en")) return "en";
        if (value.startsWith("ja")) return "ja";
        if (value.startsWith("zh")) return "zh-CN";
        return "ko";
    }

    private String normalizeCacheKey(
            String name,
            String address,
            String language,
            List<String> menuHints,
            List<String> healthConditions
    ) {
        return ("menu-v5|" + name + "|" + address + "|" + language + "|" + String.join("|", menuHints)
                + "|health:" + String.join("|", healthConditions))
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> normalizeMenuHints(List<String> menuHints) {
        if (menuHints == null) return List.of();
        return menuHints.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.replaceAll("[\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(2)
                .map(value -> value.length() <= 600 ? value : value.substring(0, 600))
                .toList();
    }

    private List<String> normalizeHealthConditions(List<String> healthConditions) {
        if (healthConditions == null) return List.of();
        return healthConditions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.replaceAll("[\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .limit(20)
                .map(value -> value.length() <= 80 ? value : value.substring(0, 80))
                .toList();
    }

    private String normalizeRiskLevel(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "safe", "caution", "avoid" -> value.toLowerCase(Locale.ROOT);
            default -> "unknown";
        };
    }

    private String disclaimer(String language) {
        return switch (language) {
            case "en" -> "Menus are based on public web sources. General ingredient information may differ from the restaurant's current offering and recipe. Confirm prices and allergens with the restaurant.";
            case "ja" -> "公開ウェブ情報を基にしたメニューです。一般的な食材情報は、店舗の現在の提供内容・レシピと異なる場合があります。価格とアレルゲンは店舗にご確認ください。";
            case "zh-CN" -> "菜单信息基于公开网页资料。一般食材信息可能与餐厅当前供应及实际配方不同，价格和过敏原请向餐厅确认。";
            default -> "공개 웹 자료를 바탕으로 정리한 메뉴입니다. 일반 재료 정보는 식당의 현재 판매 내용·실제 조리법과 다를 수 있으므로 가격과 알레르기 성분은 식당에 확인하세요.";
        };
    }

    private record CacheEntry(RestaurantMenuGuideDto guide, Instant expiresAt) {
    }

    private record MenuCandidate(
            String name,
            String displayName,
            String description,
            List<String> tasteTags,
            List<String> typicalIngredients,
            List<String> possibleAllergens,
            List<String> sourceUrls,
            String confidence,
            String healthRiskLevel,
            String healthRiskSummary,
            List<String> healthRiskReasons,
            List<String> questionsForRestaurant
    ) {
    }
}
