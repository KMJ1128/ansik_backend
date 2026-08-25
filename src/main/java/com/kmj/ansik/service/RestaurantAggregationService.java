package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmj.ansik.dto.NearbyRestaurantDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class RestaurantAggregationService {

    private static final Logger log =
            LoggerFactory.getLogger(RestaurantAggregationService.class);

    private static final int KAKAO_MAX_PAGES = 2;
    private static final double DUPLICATE_DISTANCE_METERS = 120.0;

    private final TourService tourService;
    private final KakaoService kakaoService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RestaurantAggregationService(
            TourService tourService,
            KakaoService kakaoService
    ) {
        this.tourService = tourService;
        this.kakaoService = kakaoService;

        log.info(
                "[RESTAURANT AGGREGATION] 서비스 초기화 완료 - kakaoMaxPages={}, duplicateDistance={}m",
                KAKAO_MAX_PAGES,
                DUPLICATE_DISTANCE_METERS
        );
    }

    public List<NearbyRestaurantDto> getNearbyRestaurants(
            double mapX,
            double mapY,
            int radius
    ) {
        long startedAt = System.currentTimeMillis();

        log.info(
                "[RESTAURANT AGGREGATION] TourAPI 등록 식당 검색 시작 - mapX={}, mapY={}, radius={}m",
                mapX,
                mapY,
                radius
        );

        List<NearbyRestaurantDto> merged = new ArrayList<>();

        int beforeTourCount = merged.size();
        addTourRestaurants(
                merged,
                mapX,
                mapY,
                radius
        );
        int tourAddedCount = merged.size() - beforeTourCount;

        log.info(
                "[RESTAURANT AGGREGATION] TourAPI 반영 완료 - added={}, mergedTotal={}",
                tourAddedCount,
                merged.size()
        );

        KakaoMergeResult kakaoMergeResult = merged.isEmpty()
                ? new KakaoMergeResult(0, 0)
                : enrichWithKakaoMatches(
                        merged,
                        mapX,
                        mapY,
                        radius
                );

        log.info(
                "[RESTAURANT AGGREGATION] Kakao 보정 완료 - matched={}, pages={}, tourApiTotal={}",
                kakaoMergeResult.duplicateMergedCount(),
                kakaoMergeResult.pagesProcessed(),
                merged.size()
        );

        merged.forEach(item -> {
            int distance =
                    (int) Math.round(
                            distanceMeters(
                                    mapY,
                                    mapX,
                                    item.getLatitude(),
                                    item.getLongitude()
                            )
                    );

            item.setDistanceMeters(distance);
        });

        merged.sort(
                Comparator.comparingInt(
                        NearbyRestaurantDto::getDistanceMeters
                )
        );

        long elapsedMs =
                System.currentTimeMillis() - startedAt;

        log.info(
                "[RESTAURANT AGGREGATION] TourAPI 등록 식당 검색 완료 - total={}, elapsed={}ms",
                merged.size(),
                elapsedMs
        );

        if (log.isDebugEnabled()) {
            for (int i = 0; i < merged.size(); i++) {
                NearbyRestaurantDto item =
                        merged.get(i);

                log.debug(
                        "[RESTAURANT AGGREGATION] result[{}] title='{}', distance={}m, sources={}, tourContentId='{}', kakaoPlaceId='{}'",
                        i,
                        item.getTitle(),
                        item.getDistanceMeters(),
                        item.getSources(),
                        item.getTourContentId(),
                        item.getKakaoPlaceId()
                );
            }
        }

        return merged;
    }

    private void addTourRestaurants(
            List<NearbyRestaurantDto> merged,
            double mapX,
            double mapY,
            int radius
    ) {
        long startedAt =
                System.currentTimeMillis();

        log.info(
                "[RESTAURANT AGGREGATION][TOUR] 요청 시작 - mapX={}, mapY={}, radius={}m",
                mapX,
                mapY,
                radius
        );

        try {
            ResponseEntity<String> response =
                    tourService.getNearbyRestaurants(
                            mapX,
                            mapY,
                            radius
                    );

            log.debug(
                    "[RESTAURANT AGGREGATION][TOUR] HTTP 응답 상태 - status={}",
                    response.getStatusCode()
            );

            String body =
                    response.getBody();

            if (
                    body == null ||
                            body.isBlank()
            ) {
                log.warn(
                        "[RESTAURANT AGGREGATION][TOUR] 응답 본문이 비어 있음"
                );
                return;
            }

            JsonNode items =
                    mapper.readTree(body)
                            .path("response")
                            .path("body")
                            .path("items")
                            .path("item");

            if (!items.isArray()) {
                log.warn(
                        "[RESTAURANT AGGREGATION][TOUR] item 배열 없음 - bodyLength={}",
                        body.length()
                );
                return;
            }

            int parsedCount = 0;
            int skippedInvalidCoordinateCount = 0;

            for (JsonNode item : items) {
                double lat =
                        parseDouble(
                                item.path("mapy").asText()
                        );

                double lng =
                        parseDouble(
                                item.path("mapx").asText()
                        );

                if (
                        lat == 0.0 ||
                                lng == 0.0
                ) {
                    skippedInvalidCoordinateCount++;

                    log.debug(
                            "[RESTAURANT AGGREGATION][TOUR] 좌표 누락으로 제외 - title='{}', contentId='{}', mapx='{}', mapy='{}'",
                            item.path("title").asText(""),
                            item.path("contentid").asText(""),
                            item.path("mapx").asText(""),
                            item.path("mapy").asText("")
                    );

                    continue;
                }

                NearbyRestaurantDto restaurant =
                        new NearbyRestaurantDto();

                String contentId =
                        item.path("contentid")
                                .asText("");

                String title =
                        item.path("title")
                                .asText("");

                String address =
                        item.path("addr1")
                                .asText("");

                String image =
                        secureUrl(
                                item.path("firstimage")
                                        .asText("")
                        );

                String image2 =
                        secureUrl(
                                item.path("firstimage2")
                                        .asText("")
                        );

                restaurant.setId(
                        contentId.isBlank()
                                ? UUID.randomUUID().toString()
                                : "tour:" + contentId
                );

                restaurant.setTitle(title);
                restaurant.setAddress(address);
                restaurant.setLatitude(lat);
                restaurant.setLongitude(lng);
                restaurant.setTourContentId(contentId);
                restaurant.setSources(
                        new ArrayList<>(
                                List.of("TOUR_API")
                        )
                );

                List<String> images =
                        new ArrayList<>();

                if (!image.isBlank()) {
                    images.add(image);
                }

                if (
                        !image2.isBlank() &&
                                !image2.equals(image)
                ) {
                    images.add(image2);
                }

                restaurant.setImageUrls(images);
                restaurant.setImageUrl(
                        images.isEmpty()
                                ? ""
                                : images.get(0)
                );

                merged.add(restaurant);
                parsedCount++;

                log.debug(
                        "[RESTAURANT AGGREGATION][TOUR] 추가 - title='{}', contentId='{}', lat={}, lng={}, imageCount={}",
                        title,
                        contentId,
                        lat,
                        lng,
                        images.size()
                );
            }

            log.info(
                    "[RESTAURANT AGGREGATION][TOUR] 처리 완료 - parsed={}, skippedInvalidCoordinate={}, elapsed={}ms",
                    parsedCount,
                    skippedInvalidCoordinateCount,
                    System.currentTimeMillis() - startedAt
            );

        } catch (Exception e) {
            log.error(
                    "[RESTAURANT AGGREGATION][TOUR] TourAPI 음식점 파싱 실패 - mapX={}, mapY={}, radius={}m",
                    mapX,
                    mapY,
                    radius,
                    e
            );
        }
    }

    private KakaoMergeResult enrichWithKakaoMatches(
            List<NearbyRestaurantDto> merged,
            double mapX,
            double mapY,
            int radius
    ) {
        long startedAt =
                System.currentTimeMillis();

        log.info(
                "[RESTAURANT AGGREGATION][KAKAO] 요청 시작 - mapX={}, mapY={}, radius={}m, maxPages={}",
                mapX,
                mapY,
                radius,
                KAKAO_MAX_PAGES
        );

        int duplicateMergedCount = 0;
        int pagesProcessed = 0;

        for (
                int page = 1;
                page <= KAKAO_MAX_PAGES;
                page++
        ) {
            try {
                log.debug(
                        "[RESTAURANT AGGREGATION][KAKAO] 페이지 요청 - page={}",
                        page
                );

                ResponseEntity<String> response =
                        kakaoService
                                .searchNearbyRestaurants(
                                        mapX,
                                        mapY,
                                        radius,
                                        page
                                );

                pagesProcessed++;

                log.debug(
                        "[RESTAURANT AGGREGATION][KAKAO] HTTP 응답 상태 - page={}, status={}",
                        page,
                        response.getStatusCode()
                );

                String body =
                        response.getBody();

                if (
                        body == null ||
                                body.isBlank()
                ) {
                    log.warn(
                            "[RESTAURANT AGGREGATION][KAKAO] 응답 본문이 비어 있음 - page={}",
                            page
                    );
                    break;
                }

                JsonNode root =
                        mapper.readTree(body);

                JsonNode documents =
                        root.path("documents");

                if (!documents.isArray()) {
                    log.warn(
                            "[RESTAURANT AGGREGATION][KAKAO] documents 배열 없음 - page={}, bodyLength={}",
                            page,
                            body.length()
                    );
                    break;
                }

                int pageDuplicateCount = 0;
                int pageSkippedCoordinateCount = 0;
                int pageUnmatchedCount = 0;

                for (JsonNode document : documents) {
                    double lat =
                            parseDouble(
                                    document.path("y")
                                            .asText()
                            );

                    double lng =
                            parseDouble(
                                    document.path("x")
                                            .asText()
                            );

                    String title =
                            document.path("place_name")
                                    .asText("");

                    String kakaoId =
                            document.path("id")
                                    .asText("");

                    if (
                            lat == 0.0 ||
                                    lng == 0.0
                    ) {
                        pageSkippedCoordinateCount++;

                        log.debug(
                                "[RESTAURANT AGGREGATION][KAKAO] 좌표 누락으로 제외 - title='{}', kakaoId='{}', x='{}', y='{}'",
                                title,
                                kakaoId,
                                document.path("x").asText(""),
                                document.path("y").asText("")
                        );

                        continue;
                    }

                    String address =
                            document.path(
                                    "road_address_name"
                            ).asText("");

                    if (address.isBlank()) {
                        address =
                                document.path(
                                        "address_name"
                                ).asText("");
                    }

                    NearbyRestaurantDto duplicate =
                            findDuplicate(
                                    merged,
                                    title,
                                    lat,
                                    lng
                            );

                    if (duplicate != null) {
                        duplicate.setKakaoPlaceId(
                                kakaoId
                        );

                        Set<String> sourceSet =
                                new LinkedHashSet<>(
                                        duplicate.getSources()
                                );

                        sourceSet.add(
                                "KAKAO"
                        );

                        duplicate.setSources(
                                new ArrayList<>(
                                        sourceSet
                                )
                        );

                        if (
                                duplicate.getAddress() == null ||
                                        duplicate.getAddress().isBlank()
                        ) {
                            duplicate.setAddress(
                                    address
                            );
                        }

                        duplicateMergedCount++;
                        pageDuplicateCount++;

                        log.debug(
                                "[RESTAURANT AGGREGATION][KAKAO] 기존 식당과 병합 - title='{}', kakaoId='{}', existingId='{}', sources={}",
                                title,
                                kakaoId,
                                duplicate.getId(),
                                duplicate.getSources()
                        );

                        continue;
                    }

                    pageUnmatchedCount++;

                    log.debug(
                            "[RESTAURANT AGGREGATION][KAKAO] TourAPI 미등록 식당 제외 - title='{}', kakaoId='{}', lat={}, lng={}",
                            title,
                            kakaoId,
                            lat,
                            lng
                    );
                }

                boolean isEnd =
                        root.path("meta")
                                .path("is_end")
                                .asBoolean(true);

                log.info(
                        "[RESTAURANT AGGREGATION][KAKAO] 페이지 처리 완료 - page={}, documents={}, matched={}, unmatchedSkipped={}, skippedInvalidCoordinate={}, isEnd={}",
                        page,
                        documents.size(),
                        pageDuplicateCount,
                        pageUnmatchedCount,
                        pageSkippedCoordinateCount,
                        isEnd
                );

                if (isEnd) {
                    break;
                }

            } catch (Exception e) {
                log.error(
                        "[RESTAURANT AGGREGATION][KAKAO] 음식점 파싱 실패 - page={}, mapX={}, mapY={}, radius={}m",
                        page,
                        mapX,
                        mapY,
                        radius,
                        e
                );
                break;
            }
        }

        log.info(
                "[RESTAURANT AGGREGATION][KAKAO] 처리 종료 - pagesProcessed={}, duplicateMerged={}, elapsed={}ms",
                pagesProcessed,
                duplicateMergedCount,
                System.currentTimeMillis() - startedAt
        );

        return new KakaoMergeResult(
                duplicateMergedCount,
                pagesProcessed
        );
    }

    private NearbyRestaurantDto findDuplicate(
            List<NearbyRestaurantDto> restaurants,
            String title,
            double lat,
            double lng
    ) {
        String normalizedTarget =
                normalizeName(title);

        if (normalizedTarget.isBlank()) {
            return null;
        }

        for (
                NearbyRestaurantDto existing :
                restaurants
        ) {
            String normalizedExisting =
                    normalizeName(
                            existing.getTitle()
                    );

            if (
                    !normalizedExisting.equals(
                            normalizedTarget
                    )
            ) {
                continue;
            }

            double distance =
                    distanceMeters(
                            existing.getLatitude(),
                            existing.getLongitude(),
                            lat,
                            lng
                    );

            if (
                    distance <=
                            DUPLICATE_DISTANCE_METERS
            ) {
                log.debug(
                        "[RESTAURANT AGGREGATION][DEDUP] 중복 감지 - incoming='{}', existing='{}', distance={}m",
                        title,
                        existing.getTitle(),
                        Math.round(distance)
                );

                return existing;
            }
        }

        return null;
    }

    private String normalizeName(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll(
                        "\\([^)]*\\)",
                        ""
                )
                .replaceAll(
                        "\\[[^]]*]",
                        ""
                )
                .replaceAll(
                        "[^0-9a-z가-힣]",
                        ""
                )
                .trim();
    }

    private double parseDouble(
            String value
    ) {
        try {
            return Double.parseDouble(
                    value
            );
        } catch (Exception e) {
            log.debug(
                    "[RESTAURANT AGGREGATION] 숫자 변환 실패 - value='{}'",
                    value
            );
            return 0.0;
        }
    }

    private String secureUrl(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value.replace(
                "http://",
                "https://"
        );
    }

    private double distanceMeters(
            double lat1,
            double lng1,
            double lat2,
            double lng2
    ) {
        final double earthRadiusMeters =
                6371000.0;

        double dLat =
                Math.toRadians(
                        lat2 - lat1
                );

        double dLng =
                Math.toRadians(
                        lng2 - lng1
                );

        double a =
                Math.sin(dLat / 2)
                        * Math.sin(dLat / 2)
                        + Math.cos(
                        Math.toRadians(lat1)
                )
                        * Math.cos(
                        Math.toRadians(lat2)
                )
                        * Math.sin(dLng / 2)
                        * Math.sin(dLng / 2);

        double c =
                2 * Math.atan2(
                        Math.sqrt(a),
                        Math.sqrt(1 - a)
                );

        return earthRadiusMeters * c;
    }

    private record KakaoMergeResult(
            int duplicateMergedCount,
            int pagesProcessed
    ) {
    }
}
