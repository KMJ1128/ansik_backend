package com.kmj.ansik.dto;

import java.util.List;

public record AiCourseRequest(
        String cityCode,
        String cityName,
        int nights,
        int days,
        int stopsPerDay,
        String existingSchedule,
        List<String> preferences,
        List<String> healthConditions,
        String language
) {
}
