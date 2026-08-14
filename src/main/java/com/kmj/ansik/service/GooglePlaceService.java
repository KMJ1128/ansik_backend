package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class GooglePlaceService {

    private static final Logger log = LoggerFactory.getLogger(GooglePlaceService.class);
    private static final int DAILY_API_LIMIT = 100;
    private static final int IMAGE_CACHE_MAX_SIZE = 1000;
    private static final int MAX_IMAGES = 3;

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${api.google.key}")
    private String googleApiKey;

    private final Map<String, List<String>> imageCache = Collections.synchronizedMap(
            new LinkedHashMap<String, List<String>>(IMAGE_CACHE_MAX_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > IMAGE_CACHE_MAX_SIZE;
                }
            }
    );

    private final AtomicInteger dailyApiCount = new AtomicInteger(0);
    private volatile LocalDate lastResetDate = LocalDate.now();

    public GooglePlaceService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restTemplate = new RestTemplate(factory);
    }

    public List<String> getPlaceImages(String placeName, double lat, double lng) {
        if (placeName == null || placeName.isBlank() || googleApiKey == null || googleApiKey.isBlank()) {
            return Collections.emptyList();
        }

        String cacheKey = buildCacheKey(placeName, lat, lng);
        List<String> cached = imageCache.get(cacheKey);
        if (cached != null) {
            log.info("[GOOGLE API] 이미지 캐시 사용 - {}", placeName);
            return cached;
        }

        if (!checkAndIncrementQuota()) {
            log.warn("[GOOGLE API] 하루 호출 제한 도달 - {}", placeName);
            return Collections.emptyList();
        }

        List<String> images = new ArrayList<>();

        try {
            String cleanName = sanitizePlaceName(placeName);

            URI searchUri = UriComponentsBuilder
                    .fromUriString("https://maps.googleapis.com/maps/api/place/textsearch/json")
                    .queryParam("query", cleanName)
                    .queryParam("location", lat + "," + lng)
                    .queryParam("radius", 1000)
                    .queryParam("key", googleApiKey)
                    .build()
                    .encode()
                    .toUri();

            String response = restTemplate.getForObject(searchUri, String.class);
            if (response == null || response.isBlank()) {
                return Collections.emptyList();
            }

            JsonNode root = mapper.readTree(response);
            JsonNode results = root.path("results");

            if (!results.isArray() || results.isEmpty()) {
                return Collections.emptyList();
            }

            JsonNode photos = results.get(0).path("photos");
            if (!photos.isArray()) {
                return Collections.emptyList();
            }

            int limit = Math.min(photos.size(), MAX_IMAGES);
            for (int i = 0; i < limit; i++) {
                String photoReference = photos.get(i).path("photo_reference").asText("");
                if (photoReference.isBlank()) {
                    continue;
                }

                String redirectedUrl = resolveGooglePhotoRedirect(photoReference);
                if (redirectedUrl != null && !redirectedUrl.isBlank()) {
                    images.add(redirectedUrl);
                }
            }

            if (!images.isEmpty()) {
                List<String> immutableImages = List.copyOf(images);
                imageCache.put(cacheKey, immutableImages);
                return immutableImages;
            }
        } catch (Exception e) {
            log.error("[GOOGLE API] 장소 이미지 검색 실패 - {}", placeName, e);
        }

        return Collections.emptyList();
    }

    private String buildCacheKey(String placeName, double lat, double lng) {
        return placeName.trim().toLowerCase() + "_" + lat + "_" + lng;
    }

    private String sanitizePlaceName(String placeName) {
        return placeName
                .replaceAll("\\s*\\(.*?\\)\\s*", "")
                .replaceAll("\\s*\\[.*?\\]\\s*", "")
                .trim();
    }

    private synchronized boolean checkAndIncrementQuota() {
        LocalDate today = LocalDate.now();

        if (!today.equals(lastResetDate)) {
            lastResetDate = today;
            dailyApiCount.set(0);
        }

        if (dailyApiCount.get() >= DAILY_API_LIMIT) {
            return false;
        }

        int used = dailyApiCount.incrementAndGet();
        log.info("[GOOGLE API] 오늘 사용량: {}/{}", used, DAILY_API_LIMIT);
        return true;
    }

    private String resolveGooglePhotoRedirect(String photoReference) {
        HttpURLConnection connection = null;

        try {
            String requestUrl = UriComponentsBuilder
                    .fromUriString("https://maps.googleapis.com/maps/api/place/photo")
                    .queryParam("maxwidth", 800)
                    .queryParam("photoreference", photoReference)
                    .queryParam("key", googleApiKey)
                    .build()
                    .encode()
                    .toUriString();

            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setInstanceFollowRedirects(false);
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    responseCode == 307 ||
                    responseCode == 308
            ) {
                return connection.getHeaderField("Location");
            }

            log.warn("[GOOGLE API] 사진 CDN URL 변환 실패 - HTTP {}", responseCode);
            return null;
        } catch (Exception e) {
            log.warn("[GOOGLE API] 사진 리다이렉트 확인 실패", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
