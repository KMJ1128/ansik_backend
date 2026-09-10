package com.kmj.ansik.dto;

public record TourCourseCandidate(
        String id,
        String name,
        String address,
        String area,
        String category,
        String imageUrl,
        double latitude,
        double longitude,
        int tourismRank,
        String selectionBasis,
        String tourismTheme
) {
    public TourCourseCandidate(
            String id,
            String name,
            String address,
            String area,
            String category,
            String imageUrl,
            double latitude,
            double longitude
    ) {
        this(id, name, address, area, category, imageUrl, latitude, longitude,
                9999, "TOURAPI_NEARBY", "");
    }

    public TourCourseCandidate(
            String id,
            String name,
            String address,
            String area,
            String category,
            String imageUrl,
            double latitude,
            double longitude,
            int tourismRank,
            String selectionBasis
    ) {
        this(id, name, address, area, category, imageUrl, latitude, longitude,
                tourismRank, selectionBasis, "");
    }
}
