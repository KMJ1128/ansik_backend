package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmj.ansik.logging.ExternalApiLoggingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Service
public class TourService {

    private static final Logger log = LoggerFactory.getLogger(TourService.class);
    private static final String TOUR_API_ROOT = "https://apis.data.go.kr/B551011";
    private static final int MAX_RESTAURANT_RESULTS = 20;

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${api.tourapi.key}")
    private String tourApiKey;

    public TourService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getInterceptors().add(new ExternalApiLoggingInterceptor("TOUR API"));
    }

    public List<String> getTourOfficialImages(String contentId, String language) {
        return getTourImages(contentId, "Y", language);
    }

    public List<String> getTourMenuImages(String contentId, String language) {
        return getTourImages(contentId, "N", language);
    }

    public ResponseEntity<String> getNearbyRestaurants(
            double mapX,
            double mapY,
            int radius,
            String language
    ) {
        try {
            TourApiLocale locale = resolveLocale(language);
            URI uri = UriComponentsBuilder
                    .fromUriString(locale.baseUrl() + "/locationBasedList2")
                    .queryParam("MobileOS", "AND")
                    .queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json")
                    .queryParam("contentTypeId", locale.restaurantContentTypeId())
                    .queryParam("numOfRows", MAX_RESTAURANT_RESULTS)
                    .queryParam("pageNo", 1)
                    .queryParam("arrange", "E")
                    .queryParam("mapX", mapX)
                    .queryParam("mapY", mapY)
                    .queryParam("radius", radius)
                    .queryParam("serviceKey", tourApiKey)
                    .build(true)
                    .toUri();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            String body = response.getBody();

            if (isInvalidTourResponse(body)) {
                return getEmptyTourResponse();
            }

            JsonNode rootNode = mapper.readTree(body);
            JsonNode itemsNode = rootNode
                    .path("response")
                    .path("body")
                    .path("items");

            if (!itemsNode.isObject() || !itemsNode.has("item")) {
                return getEmptyTourResponse();
            }

            JsonNode itemNode = itemsNode.path("item");
            if (!itemNode.isArray()) {
                return getEmptyTourResponse();
            }

            return ResponseEntity.ok(mapper.writeValueAsString(rootNode));
        } catch (Exception e) {
            log.error("[TOUR LOCATION] 주변 음식점 검색 실패 - language={}", language, e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    public ResponseEntity<String> getRestaurantDetails(String contentId, String language) {
        try {
            TourApiLocale locale = resolveLocale(language);
            URI uri = UriComponentsBuilder
                    .fromUriString(locale.baseUrl() + "/detailIntro2")
                    .queryParam("MobileOS", "AND")
                    .queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json")
                    .queryParam("contentTypeId", locale.restaurantContentTypeId())
                    .queryParam("numOfRows", 1)
                    .queryParam("pageNo", 1)
                    .queryParam("contentId", contentId)
                    .queryParam("serviceKey", tourApiKey)
                    .build(true)
                    .toUri();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            String body = response.getBody();

            if (isInvalidTourResponse(body)) {
                return ResponseEntity.ok("{}");
            }

            JsonNode rootNode = mapper.readTree(body);
            return ResponseEntity.ok(mapper.writeValueAsString(rootNode));
        } catch (Exception e) {
            log.error("[TOUR DETAIL] 음식점 상세 검색 실패 - contentId={}, language={}", contentId, language, e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    private List<String> getTourImages(String contentId, String imageYn, String language) {
        List<String> images = new ArrayList<>();

        if (contentId == null || contentId.isBlank()) {
            return images;
        }

        try {
            TourApiLocale locale = resolveLocale(language);
            URI uri = UriComponentsBuilder
                    .fromUriString(locale.baseUrl() + "/detailImage2")
                    .queryParam("MobileOS", "AND")
                    .queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json")
                    .queryParam("imageYN", imageYn)
                    .queryParam("numOfRows", 20)
                    .queryParam("pageNo", 1)
                    .queryParam("contentId", contentId)
                    .queryParam("serviceKey", tourApiKey)
                    .build(true)
                    .toUri();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            String body = response.getBody();

            if (isInvalidTourResponse(body)) {
                return images;
            }

            JsonNode root = mapper.readTree(body);
            JsonNode itemArray = root
                    .path("response")
                    .path("body")
                    .path("items")
                    .path("item");

            if (!itemArray.isArray()) {
                return images;
            }

            for (JsonNode item : itemArray) {
                String imageUrl = item.path("originimgurl").asText("");
                if (imageUrl.isBlank()) {
                    imageUrl = item.path("originImgurl").asText("");
                }

                if (!imageUrl.isBlank()) {
                    images.add(imageUrl.replace("http://", "https://"));
                }
            }
        } catch (Exception e) {
            log.error(
                    "[TOUR DETAIL IMAGE] 이미지 조회 실패 - contentId={}, imageYN={}, language={}",
                    contentId,
                    imageYn,
                    language,
                    e
            );
        }

        return images.stream().distinct().toList();
    }

    private boolean isInvalidTourResponse(String body) {
        return body == null || body.isBlank() || body.trim().startsWith("<");
    }

    private ResponseEntity<String> getEmptyTourResponse() {
        return ResponseEntity.ok(
                "{\"response\":{\"body\":{\"items\":{\"item\":[]}}}}"
        );
    }

    public String normalizeLanguage(String language) {
        return resolveLocale(language).language();
    }

    private TourApiLocale resolveLocale(String language) {
        String normalized = language == null
                ? "ko"
                : language.trim().toLowerCase(java.util.Locale.ROOT);

        return switch (normalized) {
            case "en", "en-us", "en-gb" ->
                    new TourApiLocale("en", TOUR_API_ROOT + "/EngService2", 82);
            case "ja", "ja-jp" ->
                    new TourApiLocale("ja", TOUR_API_ROOT + "/JpnService2", 82);
            case "zh", "zh-cn", "zh-hans", "zh-hans-cn" ->
                    new TourApiLocale("zh-CN", TOUR_API_ROOT + "/ChsService2", 82);
            default ->
                    new TourApiLocale("ko", TOUR_API_ROOT + "/KorService2", 39);
        };
    }

    private record TourApiLocale(
            String language,
            String baseUrl,
            int restaurantContentTypeId
    ) {
    }
}
