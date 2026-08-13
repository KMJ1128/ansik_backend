package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TourService {
    private static final Logger log = LoggerFactory.getLogger(TourService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final NaverService naverService;

    @Value("${api.tourapi.key}")
    private String tourApiKey;

    public TourService(NaverService naverService) {
        this.naverService = naverService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000); // 공공데이터는 조금 느릴 수 있어 5초
        this.restTemplate = new RestTemplate(factory);
    }

    public List<String> getTourOfficialImages(String contentId) {
        List<String> images = new ArrayList<>();
        try {
            String url = "https://apis.data.go.kr/B551011/KorService2/detailImage1?MobileOS=AND&MobileApp=Ansik&_type=json&imageYN=Y&subImageYN=Y&numOfRows=10&contentId="
                    + contentId + "&serviceKey=" + tourApiKey;
            URI uri = new URI(url);

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            String body = response.getBody();

            if (body == null || body.trim().startsWith("<")) {
                log.warn("[TOUR DETAIL IMAGE] API 키 오류 또는 트래픽 초과 (XML 수신) - contentId={}", contentId);
                return images;
            }

            JsonNode root = mapper.readTree(body);
            JsonNode items = root.path("response").path("body").path("items");
            if (items.isObject() && items.has("item")) {
                JsonNode itemArray = items.path("item");
                if (itemArray.isArray()) {
                    for (JsonNode item : itemArray) {
                        String img = item.path("originImgurl").asText("");
                        if (!img.isBlank()) images.add(img.replace("http://", "https://"));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[TOUR DETAIL IMAGE] 갤러리 파 파싱 실패 - contentId={}", contentId);
        }
        return images;
    }

    public ResponseEntity<String> getNearbyRestaurants(double mapX, double mapY, int radius) {
        try {
            String url = "https://apis.data.go.kr/B551011/KorService2/locationBasedList2?MobileOS=AND&MobileApp=Ansik&_type=json&contentTypeId=39&numOfRows=40&pageNo=1&arrange=E&mapX="
                    + mapX + "&mapY=" + mapY + "&radius=" + radius + "&serviceKey=" + tourApiKey;
            URI uri = new URI(url);

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            if (response.getBody() == null || response.getBody().trim().startsWith("<")) return getEmptyTourResponse();

            JsonNode rootNode = mapper.readTree(response.getBody());
            JsonNode itemsNode = rootNode.path("response").path("body").path("items");

            if (itemsNode.isTextual() || !itemsNode.has("item")) return getEmptyTourResponse();

            JsonNode itemArrayNode = itemsNode.path("item");
            List<JsonNode> rawList = new ArrayList<>();
            itemArrayNode.forEach(rawList::add);

            // 💡 속도 극대화: parallelStream으로 네이버 블로그 검색 병열 처리
            List<ObjectNode> placeList = rawList.parallelStream().map(item -> {
                ObjectNode placeNode = (ObjectNode) item.deepCopy();
                String title = placeNode.path("title").asText("");
                int blogCount = naverService.getBlogReviewCount(title + " 식당");
                placeNode.put("blogCount", blogCount);
                return placeNode;
            }).collect(Collectors.toList());

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
            log.error("[TOUR LOCATION] 주변 음식점 검색 실패", e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    public ResponseEntity<String> getRestaurantDetails(String contentId) {
        try {
            String url = "https://apis.data.go.kr/B551011/KorService2/detailIntro2?MobileOS=AND&MobileApp=Ansik&_type=json&contentTypeId=39&numOfRows=1&pageNo=1&contentId="
                    + contentId + "&serviceKey=" + tourApiKey;
            URI uri = new URI(url);

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            if (response.getBody() == null || response.getBody().trim().startsWith("<")) return ResponseEntity.ok("{}");

            JsonNode rootNode = mapper.readTree(response.getBody());
            return ResponseEntity.ok(mapper.writeValueAsString(rootNode));
        } catch (Exception e) {
            log.error("[TOUR DETAIL] 음식점 상세 검색 실패", e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    private ResponseEntity<String> getEmptyTourResponse() {
        return ResponseEntity.ok("{\"response\":{\"body\":{\"items\":{\"item\":[]}}}}");
    }
}