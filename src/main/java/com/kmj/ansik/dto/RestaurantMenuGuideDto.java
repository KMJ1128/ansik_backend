package com.kmj.ansik.dto;

import java.util.List;

public record RestaurantMenuGuideDto(
        String restaurantName,
        String address,
        List<MenuItem> menus,
        String status,
        String generatedBy,
        String disclaimer
) {
    public static RestaurantMenuGuideDto unavailable(
            String restaurantName,
            String address,
            String status,
            String disclaimer
    ) {
        return new RestaurantMenuGuideDto(
                restaurantName,
                address,
                List.of(),
                status,
                "",
                disclaimer
        );
    }

    public record MenuItem(
            String name,
            String description,
            List<String> tasteTags,
            List<String> typicalIngredients,
            List<String> possibleAllergens,
            List<String> imageUrls,
            List<String> sourceUrls,
            String confidence,
            String healthRiskLevel,
            String healthRiskSummary,
            List<String> healthRiskReasons,
            List<String> questionsForRestaurant
    ) {
    }
}
