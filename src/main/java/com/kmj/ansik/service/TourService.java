package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Service
public class TourService {
    private static final Logger log = LoggerFactory.getLogger(TourService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private final NaverService naverService;

    @Value("${api.tourapi.key}")
    private String tourApiKey;

    public TourService(NaverService naverService) {
        this.naverService = naverService;
    }

    public ResponseEntity<String> getNearbyRestaurants(double mapX, double mapY, int radius) {
        try {
            URI uri = UriComponentsBuilder.fromUriString("https://apis.data.go.kr/B551011/KorService2/locationBasedList2")
                    .queryParam("serviceKey", tourApiKey)
                    .queryParam("MobileOS", "AND")
                    .queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json")
                    .queryParam("contentTypeId", 39)
                    .queryParam("numOfRows", 40)
                    .queryParam("pageNo", 1)
                    .queryParam("arrange", "E")
                    .queryParam("mapX", mapX)
                    .queryParam("mapY", mapY)
                    .queryParam("radius", radius)
                    .build().encode().toUri();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);

            if (response.getBody() == null) return response;

            JsonNode rootNode = mapper.readTree(response.getBody());
            JsonNode bodyNode = rootNode.path("response").path("body");
            JsonNode itemsNode = bodyNode.path("items");

            if (itemsNode.isTextual() || !itemsNode.has("item")) {
                return getEmptyTourResponse();
            }

            JsonNode itemArrayNode = itemsNode.path("item");

            List<ObjectNode> placeList = new ArrayList<>();
            for (JsonNode item : itemArrayNode) {
                ObjectNode placeNode = (ObjectNode) item.deepCopy();
                String title = placeNode.path("title").asText("");

                int blogCount = naverService.getBlogReviewCount(title + " 식당");
                placeNode.put("blogCount", blogCount);

                placeList.add(placeNode);
            }

            placeList.sort((a, b) -> Integer.compare(b.path("blogCount").asInt(0), a.path("blogCount").asInt(0)));

            int limit = Math.min(placeList.size(), 20);
            ArrayNode sortedItemArray = mapper.createArrayNode();

            for (int i = 0; i < limit; i++) {
                ObjectNode p = placeList.get(i);
                p.remove("blogCount");
                sortedItemArray.add(p);
            }

            ((ObjectNode) itemsNode).set("item", sortedItemArray);

            return ResponseEntity.ok(mapper.writeValueAsString(rootNode));

        } catch (Exception e) {
            log.error("[TOUR LOCATION] 주변 음식점 검색 및 인기순 정렬 실패", e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    public ResponseEntity<String> getRestaurantDetails(String contentId) {
        try {
            URI uri = UriComponentsBuilder.fromUriString("https://apis.data.go.kr/B551011/KorService2/detailIntro2")
                    .queryParam("serviceKey", tourApiKey)
                    .queryParam("MobileOS", "AND")
                    .queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json")
                    .queryParam("contentTypeId", 39)
                    .queryParam("numOfRows", 1)
                    .queryParam("pageNo", 1)
                    .queryParam("contentId", contentId)
                    .build().encode().toUri();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            if (response.getBody() == null) return response;

            JsonNode rootNode = mapper.readTree(response.getBody());
            JsonNode bodyNode = rootNode.path("response").path("body");
            JsonNode itemsNode = bodyNode.path("items");

            if (itemsNode.isTextual()) {
                ObjectNode emptyItems = mapper.createObjectNode();
                emptyItems.set("item", mapper.createArrayNode());
                if (bodyNode.isObject()) {
                    ((ObjectNode) bodyNode).set("items", emptyItems);
                }
                return ResponseEntity.ok(mapper.writeValueAsString(rootNode));
            }
            return response;
        } catch (Exception e) {
            log.error("[TOUR DETAIL] 음식점 상세 검색 실패", e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    private ResponseEntity<String> getEmptyTourResponse() {
        try {
            ObjectNode responseNode = mapper.createObjectNode();
            ObjectNode responseOuter = mapper.createObjectNode();
            ObjectNode bodyNode = mapper.createObjectNode();
            ObjectNode itemsNode = mapper.createObjectNode();
            itemsNode.set("item", mapper.createArrayNode());
            bodyNode.set("items", itemsNode);
            responseOuter.set("body", bodyNode);
            responseNode.set("response", responseOuter);
            return ResponseEntity.ok(mapper.writeValueAsString(responseNode));
        } catch (Exception e) {
            return ResponseEntity.ok("{\"response\":{\"body\":{\"items\":{\"item\":[]}}}}");
        }
    }
}