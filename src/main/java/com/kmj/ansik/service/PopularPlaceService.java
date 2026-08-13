package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class PopularPlaceService {

    private static final Logger log = LoggerFactory.getLogger(PopularPlaceService.class);
    private final KakaoService kakaoService;
    private final NaverService naverService;
    private final ObjectMapper mapper = new ObjectMapper();

    public PopularPlaceService(KakaoService kakaoService, NaverService naverService) {
        this.kakaoService = kakaoService;
        this.naverService = naverService;
    }

    public ResponseEntity<String> getHotPlacesByBlogCount(boolean restaurantOnly, double mapX, double mapY) {
        try {
            List<String> keywords = new ArrayList<>();
            if (restaurantOnly) {
                keywords.add("맛집");
                keywords.add("식당");
                keywords.add("카페");
            } else {
                keywords.add("관광지");
                keywords.add("가볼만한곳");
                keywords.add("명소");
            }

            List<JsonNode> kakaoPlaces = new ArrayList<>();

            for (String keyword : keywords) {
                JsonNode rootNode = kakaoService.searchKeywordNode(keyword, 15, mapX, mapY, 5000);
                if (rootNode != null && rootNode.has("documents")) {
                    JsonNode documents = rootNode.path("documents");
                    if (documents.isArray()) {
                        for (JsonNode document : documents) {
                            boolean isDuplicate = false;
                            String newId = document.path("id").asText("");
                            for (JsonNode existing : kakaoPlaces) {
                                if (existing.path("id").asText("").equals(newId)) {
                                    isDuplicate = true;
                                    break;
                                }
                            }
                            if (!isDuplicate) {
                                kakaoPlaces.add(document);
                            }
                        }
                    }
                }
                if (kakaoPlaces.size() >= 20) break;
            }

            if (kakaoPlaces.isEmpty()) {
                return ResponseEntity.ok(createEmptyTourResponse());
            }

            // 💡 병열 스레드로 각 장소별 네이버 리뷰 수 조회 속도 향상
            List<ObjectNode> places = kakaoPlaces.parallelStream().map(doc -> {
                String placeName = doc.path("place_name").asText("");
                String address = doc.path("road_address_name").asText("");
                if (address.isBlank()) address = doc.path("address_name").asText("");
                String x = doc.path("x").asText("");
                String y = doc.path("y").asText("");
                String id = doc.path("id").asText("");

                if (placeName.isBlank()) return null;

                String searchKeyword = restaurantOnly ? placeName + " 맛집" : placeName;
                int blogCount = naverService.getBlogReviewCount(searchKeyword);

                ObjectNode placeObj = mapper.createObjectNode();
                placeObj.put("contentid", id);
                placeObj.put("title", placeName);
                placeObj.put("addr1", address);
                placeObj.put("addr2", "");
                placeObj.put("mapx", x);
                placeObj.put("mapy", y);
                placeObj.put("firstimage", "");
                placeObj.put("firstimage2", "");
                placeObj.put("blogCount", blogCount);

                return placeObj;
            }).filter(Objects::nonNull).collect(Collectors.toList());

            places.sort((a, b) -> Integer.compare(b.path("blogCount").asInt(0), a.path("blogCount").asInt(0)));

            int resultCount = Math.min(places.size(), 10);
            ArrayNode itemArray = mapper.createArrayNode();

            for (int i = 0; i < resultCount; i++) {
                ObjectNode place = places.get(i);
                place.remove("blogCount");
                itemArray.add(place);
            }

            ObjectNode itemsNode = mapper.createObjectNode();
            itemsNode.set("item", itemArray);

            ObjectNode bodyNode = mapper.createObjectNode();
            bodyNode.set("items", itemsNode);

            ObjectNode responseOuter = mapper.createObjectNode();
            responseOuter.set("body", bodyNode);

            ObjectNode responseNode = mapper.createObjectNode();
            responseNode.set("response", responseOuter);

            return ResponseEntity.ok(mapper.writeValueAsString(responseNode));

        } catch (Exception e) {
            log.error("[POPULAR] 인기 데이터 동적 처리 실패", e);
            return ResponseEntity.status(500).body("{\"error\":\"인기 데이터 동적 처리 실패\"}");
        }
    }

    private String createEmptyTourResponse() {
        try {
            ObjectNode responseNode = mapper.createObjectNode();
            ObjectNode responseOuter = mapper.createObjectNode();
            ObjectNode bodyNode = mapper.createObjectNode();
            ObjectNode itemsNode = mapper.createObjectNode();
            itemsNode.set("item", mapper.createArrayNode());
            bodyNode.set("items", itemsNode);
            responseOuter.set("body", bodyNode);
            responseNode.set("response", responseOuter);
            return mapper.writeValueAsString(responseNode);
        } catch (Exception e) {
            return "{\"response\":{\"body\":{\"items\":{\"item\":[]}}}}";
        }
    }
}