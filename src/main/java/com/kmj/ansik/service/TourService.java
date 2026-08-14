package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final String TOUR_BASE_URL = "https://apis.data.go.kr/B551011/KorService2";
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
    }

    public List<String> getTourOfficialImages(String contentId) {
        return getTourImages(contentId, "Y");
    }

    public List<String> getTourMenuImages(String contentId) {
        return getTourImages(contentId, "N");
    }

    public ResponseEntity<String> getNearbyRestaurants(
            double mapX,
            double mapY,
            int radius
    ) {
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(TOUR_BASE_URL + "/locationBasedList2")
                    .queryParam("MobileOS", "AND")
                    .queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json")
                    .queryParam("contentTypeId", 39)
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
            log.error("[TOUR LOCATION] 주변 음식점 검색 실패", e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    public ResponseEntity<String> getRestaurantDetails(String contentId) {
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(TOUR_BASE_URL + "/detailIntro2")
                    .queryParam("MobileOS", "AND")
                    .queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json")
                    .queryParam("contentTypeId", 39)
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
            log.error("[TOUR DETAIL] 음식점 상세 검색 실패", e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    private List<String> getTourImages(String contentId, String imageYn) {
        List<String> images = new ArrayList<>();

        if (contentId == null || contentId.isBlank()) {
            return images;
        }

        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(TOUR_BASE_URL + "/detailImage2")
                    .queryParam("MobileOS", "AND")
                    .queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json")
                    .queryParam("imageYN", imageYn)
                    .queryParam("subImageYN", "Y")
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
                    "[TOUR DETAIL IMAGE] 이미지 조회 실패 - contentId={}, imageYN={}",
                    contentId,
                    imageYn,
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
}
