package com.kmj.ansik.controller;

import com.kmj.ansik.dto.NearbyRestaurantDto;
import com.kmj.ansik.service.GooglePlaceService;
import com.kmj.ansik.service.KakaoService;
import com.kmj.ansik.service.NaverService;
import com.kmj.ansik.service.RestaurantAggregationService;
import com.kmj.ansik.service.TourService;
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

    private final KakaoService kakaoService;
    private final NaverService naverService;
    private final TourService tourService;
    private final GooglePlaceService googlePlaceService;
    private final RestaurantAggregationService restaurantAggregationService;

    public ApiController(
            KakaoService kakaoService,
            NaverService naverService,
            TourService tourService,
            GooglePlaceService googlePlaceService,
            RestaurantAggregationService restaurantAggregationService
    ) {
        this.kakaoService = kakaoService;
        this.naverService = naverService;
        this.tourService = tourService;
        this.googlePlaceService = googlePlaceService;
        this.restaurantAggregationService = restaurantAggregationService;
    }

    @GetMapping(value = "/place", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> searchPlace(
            @RequestParam String query,
            @RequestParam(required = false) Double mapX,
            @RequestParam(required = false) Double mapY
    ) {
        return kakaoService.searchPlace(query, mapX, mapY);
    }

    @GetMapping(value = "/image/exact", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<String>> getExactImages(
            @RequestParam(required = false) String tourId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false, defaultValue = "0") double mapX,
            @RequestParam(required = false, defaultValue = "0") double mapY
    ) {
        List<String> images = new ArrayList<>();

        if (tourId != null && !tourId.isBlank()) {
            images.addAll(tourService.getTourOfficialImages(tourId));
        }

        if (images.size() < 3 && title != null && !title.isBlank()) {
            images.addAll(googlePlaceService.getPlaceImages(title, mapY, mapX));
        }

        return ResponseEntity.ok(
                images.stream()
                        .filter(image -> image != null && !image.isBlank())
                        .distinct()
                        .limit(10)
                        .toList()
        );
    }

    @GetMapping(value = "/restaurants/nearby", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<NearbyRestaurantDto>> getTourApiNearbyRestaurants(
            @RequestParam double mapX,
            @RequestParam double mapY,
            @RequestParam int radius
    ) {
        return ResponseEntity.ok(
                restaurantAggregationService.getNearbyRestaurants(mapX, mapY, radius)
        );
    }

    @GetMapping(value = "/tour/menu-images", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<String>> getTourMenuImages(
            @RequestParam String contentId
    ) {
        return ResponseEntity.ok(tourService.getTourMenuImages(contentId));
    }

    @GetMapping(value = "/tour/location", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getNearbyRestaurants(
            @RequestParam double mapX,
            @RequestParam double mapY,
            @RequestParam int radius
    ) {
        return tourService.getNearbyRestaurants(mapX, mapY, radius);
    }

    @GetMapping(value = "/tour/detail", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getRestaurantDetails(
            @RequestParam String contentId
    ) {
        return tourService.getRestaurantDetails(contentId);
    }

    @GetMapping(value = "/tour/reviews", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPlaceReviews(
            @RequestParam String placeName,
            @RequestParam(defaultValue = "") String address,
            @RequestParam(defaultValue = "1") int start
    ) {
        return naverService.getBlogReviewList(placeName, address, start);
    }
}
