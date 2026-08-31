package com.kmj.ansik.dto;

import java.util.ArrayList;
import java.util.List;

public class NearbyRestaurantDto {

    private String id;
    private String title;
    private String address;
    private double latitude;
    private double longitude;
    private String imageUrl;
    private List<String> imageUrls = new ArrayList<>();
    private String tourContentId;
    private String tourLanguage = "ko";
    private boolean koreanFallback;
    private String kakaoPlaceId;
    private List<String> sources = new ArrayList<>();
    private int distanceMeters;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }

    public String getTourContentId() {
        return tourContentId;
    }

    public void setTourContentId(String tourContentId) {
        this.tourContentId = tourContentId;
    }

    public String getTourLanguage() {
        return tourLanguage;
    }

    public void setTourLanguage(String tourLanguage) {
        this.tourLanguage = tourLanguage;
    }

    public boolean isKoreanFallback() {
        return koreanFallback;
    }

    public void setKoreanFallback(boolean koreanFallback) {
        this.koreanFallback = koreanFallback;
    }

    public String getKakaoPlaceId() {
        return kakaoPlaceId;
    }

    public void setKakaoPlaceId(String kakaoPlaceId) {
        this.kakaoPlaceId = kakaoPlaceId;
    }

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    public int getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(int distanceMeters) {
        this.distanceMeters = distanceMeters;
    }
}
