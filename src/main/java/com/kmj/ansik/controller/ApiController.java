package com.kmj.ansik.controller;

import com.kmj.ansik.service.GooglePlaceService;
import com.kmj.ansik.service.KakaoService;
import com.kmj.ansik.service.NaverService;
import com.kmj.ansik.service.PopularPlaceService;
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
    private final PopularPlaceService popularPlaceService;
    private final GooglePlaceService googlePlaceService; // 💡 구글 서비스 추가

    public ApiController(KakaoService kakaoService, NaverService naverService,
                         TourService tourService, PopularPlaceService popularPlaceService,
                         GooglePlaceService googlePlaceService) {
        this.kakaoService = kakaoService;
        this.naverService = naverService;
        this.tourService = tourService;
        this.popularPlaceService = popularPlaceService;
        this.googlePlaceService = googlePlaceService;
    }

    @GetMapping(value = "/place", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> searchPlace(@RequestParam String query) {
        return kakaoService.searchPlace(query);
    }

    // 🔥 100% 팩트 기반 이미지 반환 (TourAPI + Google Places)
    @GetMapping(value = "/image/exact", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<String>> getExactImages(
            @RequestParam(required = false) String tourId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false, defaultValue = "0") double mapX,
            @RequestParam(required = false, defaultValue = "0") double mapY) {

        List<String> images = new ArrayList<>();

        // 1. 공공데이터 공식 갤러리 이미지 확인 (무료이므로 가장 먼저 호출)
        if (tourId != null && !tourId.isBlank()) {
            images.addAll(tourService.getTourOfficialImages(tourId));
        }

        // 2. 모자란 사진은 구글 Places API 로 채우기 (캐시 적용)
        if (images.size() < 3 && title != null && !title.isBlank()) {
            // TourAPI는 mapX가 경도(lng), mapY가 위도(lat)임
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

    @GetMapping(value = "/tour/popular-places", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPopularPlaces(
            @RequestParam(defaultValue = "126.9780") double mapX,
            @RequestParam(defaultValue = "37.5665") double mapY) {
        return popularPlaceService.getHotPlacesByBlogCount(false, mapX, mapY);
    }

    @GetMapping(value = "/tour/popular-restaurants", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPopularRestaurants(
            @RequestParam(defaultValue = "126.9780") double mapX,
            @RequestParam(defaultValue = "37.5665") double mapY) {
        return popularPlaceService.getHotPlacesByBlogCount(true, mapX, mapY);
    }
}