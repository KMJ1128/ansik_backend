package com.kmj.ansik.controller;

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

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final KakaoService kakaoService;
    private final NaverService naverService;
    private final TourService tourService;
    private final PopularPlaceService popularPlaceService;

    public ApiController(KakaoService kakaoService, NaverService naverService,
                         TourService tourService, PopularPlaceService popularPlaceService) {
        this.kakaoService = kakaoService;
        this.naverService = naverService;
        this.tourService = tourService;
        this.popularPlaceService = popularPlaceService;
    }

    @GetMapping(value = "/place", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> searchPlace(@RequestParam String query) {
        return kakaoService.searchPlace(query);
    }

    // 💡 네이버 이미지 검색으로 원상복구
    @GetMapping(value = "/image", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> searchImage(@RequestParam String query) {
        log.info("[API] 네이버 이미지 검색 요청 - query={}", query);
        return naverService.searchImage(query);
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