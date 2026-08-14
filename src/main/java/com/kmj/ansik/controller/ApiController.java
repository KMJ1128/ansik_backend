package com.kmj.ansik.controller;

import com.kmj.ansik.service.GooglePlaceService;
import com.kmj.ansik.service.KakaoService;
import com.kmj.ansik.service.NaverService;
import com.kmj.ansik.service.TourService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final KakaoService kakaoService;
    private final NaverService naverService;
    private final TourService tourService;
    private final GooglePlaceService googlePlaceService;

    // 💡 PopularPlaceService 의존성 완전 제거됨
    public ApiController(KakaoService kakaoService, NaverService naverService,
                         TourService tourService, GooglePlaceService googlePlaceService) {
        this.kakaoService = kakaoService;
        this.naverService = naverService;
        this.tourService = tourService;
        this.googlePlaceService = googlePlaceService;
    }

    // 💡 검색 시 거리순 정렬을 위한 좌표 파라미터 적용 (동명이인 가게 방지)
    @GetMapping(value = "/place", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> searchPlace(
            @RequestParam String query,
            @RequestParam(required = false) Double mapX,
            @RequestParam(required = false) Double mapY) {
        return kakaoService.searchPlace(query, mapX, mapY);
    }

    @GetMapping(value = "/image/exact", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<String>> getExactImages(
            @RequestParam(required = false) String tourId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false, defaultValue = "0") double mapX,
            @RequestParam(required = false, defaultValue = "0") double mapY) {

        List<String> images = new ArrayList<>();

        if (tourId != null && !tourId.isBlank()) {
            images.addAll(tourService.getTourOfficialImages(tourId));
        }

        if (images.size() < 3 && title != null && !title.isBlank()) {
            images.addAll(googlePlaceService.getPlaceImages(title, mapY, mapX));
        }

        return ResponseEntity.ok(images);
    }

    @GetMapping(value = "/tour/location", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getNearbyRestaurants(
            @RequestParam double mapX,
            @RequestParam double mapY,
            @RequestParam int radius) {
        return tourService.getNearbyRestaurants(mapX, mapY, radius);
    }

    @GetMapping(value = "/tour/detail", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getRestaurantDetails(@RequestParam String contentId) {
        return tourService.getRestaurantDetails(contentId);
    }

    // 💡 리뷰 무한 스크롤 및 정확도 향상 파라미터 유지
    @GetMapping(value = "/tour/reviews", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPlaceReviews(
            @RequestParam String placeName,
            @RequestParam(defaultValue = "") String address,
            @RequestParam(defaultValue = "1") int start) {
        return naverService.getBlogReviewList(placeName, address, start);
    }
}