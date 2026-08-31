package com.kmj.ansik.dto;

import java.util.List;

public record MenuProfileDto(
        String menuName,
        String canonicalKoreanName,
        String description,
        List<String> tasteTags,
        List<String> typicalIngredients,
        List<String> possibleAllergens,
        NutritionInfo nutrition,
        String matchStatus,
        String descriptionSource,
        String descriptionSourceUrl,
        String disclaimer
) {
    public record NutritionInfo(
            String matchedFoodName,
            String basisAmount,
            Double energyKcal,
            Double carbohydrateG,
            Double proteinG,
            Double fatG,
            Double sugarG,
            Double sodiumMg,
            String sourceName
    ) {
    }
}
