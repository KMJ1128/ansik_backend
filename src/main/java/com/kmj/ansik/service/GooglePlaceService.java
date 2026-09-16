package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmj.ansik.logging.ExternalApiLoggingInterceptor;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GooglePlaceService {

    private static final Logger log = LoggerFactory.getLogger(GooglePlaceService.class);
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

    public GooglePlaceService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getInterceptors().add(new ExternalApiLoggingInterceptor("GOOGLE PLACES"));
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
            String status = root.path("status").asText("");
            JsonNode results = root.path("results");

            if (!results.isArray() || results.isEmpty()) {
                log.warn(
                        "[GOOGLE API] 장소 검색 결과 없음 - place='{}', status='{}', message='{}'",
                        placeName,
                        status,
                        root.path("error_message").asText("")
                );
                return Collections.emptyList();
            }

            JsonNode match = chooseBestCandidate(results, cleanName, lat, lng);
            if (match == null) {
                log.warn("[GOOGLE API] 좌표와 일치하는 사진 장소 없음 - place='{}'", placeName);
                return Collections.emptyList();
            }
            JsonNode photos = match.path("photos");
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

    /**
     * Prefer an exact localized name, but allow a coordinate match for translated
     * names (for example "Junggu Office" and "중구청"). Text Search is already
     * biased to the supplied point, so a photographed result within 250 m is a
     * safer fallback than discarding every non-identical localized name.
     */
    private JsonNode chooseBestCandidate(JsonNode results, String placeName, double lat, double lng) {
        String expected = normalizeName(placeName);
        boolean hasCoordinates = isValidCoordinate(lat, lng);
        JsonNode nearestCoordinateMatch = null;
        double nearestMeters = Double.MAX_VALUE;

        for (JsonNode candidate : results) {
            if (!candidate.path("photos").isArray() || candidate.path("photos").isEmpty()) {
                continue;
            }

            String actual = normalizeName(candidate.path("name").asText(""));
            double meters = candidateDistanceMeters(candidate, lat, lng);
            boolean closeByName = actual.equals(expected)
                    || (!actual.isBlank() && !expected.isBlank()
                    && (actual.contains(expected) || expected.contains(actual)));

            if (closeByName && (!hasCoordinates || meters <= 1500)) {
                return candidate;
            }
            if (hasCoordinates && meters <= 250 && meters < nearestMeters) {
                nearestCoordinateMatch = candidate;
                nearestMeters = meters;
            }
        }

        if (nearestCoordinateMatch != null) {
            log.info(
                    "[GOOGLE API] 번역명 좌표 매칭 사용 - requested='{}', matched='{}', distance={}m",
                    placeName,
                    nearestCoordinateMatch.path("name").asText(""),
                    Math.round(nearestMeters)
            );
            return nearestCoordinateMatch;
        }

        if (!hasCoordinates) {
            for (JsonNode candidate : results) {
                if (candidate.path("photos").isArray() && !candidate.path("photos").isEmpty()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private boolean isValidCoordinate(double lat, double lng) {
        return lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
                && !(lat == 0.0 && lng == 0.0);
    }

    private double candidateDistanceMeters(JsonNode candidate, double lat, double lng) {
        if (!isValidCoordinate(lat, lng)) return Double.MAX_VALUE;
        JsonNode point = candidate.path("geometry").path("location");
        double candidateLat = point.path("lat").asDouble(0);
        double candidateLng = point.path("lng").asDouble(0);
        if (!isValidCoordinate(candidateLat, candidateLng)) return Double.MAX_VALUE;

        double dLat = Math.toRadians(candidateLat - lat);
        double dLng = Math.toRadians(candidateLng - lng);
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(lat))
                * Math.cos(Math.toRadians(candidateLat)) * Math.pow(Math.sin(dLng / 2), 2);
        return 6371000 * 2 * Math.asin(Math.min(1, Math.sqrt(a)));
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
