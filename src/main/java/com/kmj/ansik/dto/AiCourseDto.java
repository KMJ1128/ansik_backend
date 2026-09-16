package com.kmj.ansik.dto;

import java.util.List;

public record AiCourseDto(
        String id,
        String title,
        String cityCode,
        String cityName,
        int nights,
        int days,
        List<String> preferences,
        String summary,
        String selectionReason,
        List<String> travelTips,
        List<CourseDay> itinerary,
        String status,
        String generatedBy
) {
    public static AiCourseDto unavailable(
            String cityCode,
            String cityName,
            int nights,
            int days,
            List<String> preferences,
            String status
    ) {
        return new AiCourseDto(
                "", "", cityCode, cityName, nights, days, preferences,
                "", "", List.of(), List.of(), status, ""
        );
    }

    public record CourseDay(int day, String theme, List<CourseStop> stops) {
    }

    public record CourseStop(
            String id,
            String name,
            String address,
            String category,
            String imageUrl,
            double latitude,
            double longitude,
            String recommendedTime,
            String reason,
            String visitTip,
            String healthNote,
            int estimatedTransferMinutes,
            int suggestedStayMinutes,
            boolean timingEstimated,
            boolean timingNeedsReview
    ) {
        public CourseStop(String id, String name, String address, String category, String imageUrl,
                          double latitude, double longitude, String recommendedTime,
                          String reason, String visitTip, String healthNote) {
            this(id, name, address, category, imageUrl, latitude, longitude, recommendedTime,
                    reason, visitTip, healthNote, 0, 0, false, false);
        }
    }
}
