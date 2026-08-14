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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class GooglePlaceService {
    private static final Logger log = LoggerFactory.getLogger(GooglePlaceService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${api.google.key}")
    private String googleApiKey;

    private final Map<String, List<String>> imageCache = Collections.synchronizedMap(
            new LinkedHashMap<String, List<String>>(1000, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > 1000;
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
        String cacheKey = placeName + "_" + lat + "_" + lng;

        if (imageCache.containsKey(cacheKey)) {
            log.info("[GOOGLE API] 캐시 히트! (비용 0원) - {}", placeName);
            return imageCache.get(cacheKey);
        }

        if (!checkAndIncrementQuota()) {
            log.warn("[GOOGLE API] 하루 최대 호출 제한(100회) 도달 - {}", placeName);
            return Collections.emptyList();
        }

        List<String> images = new ArrayList<>();
        try {
            String cleanName = placeName.replaceAll("\\s*\\(.*?\\)\\s*", "").replaceAll("\\s*\\[.*?\\]\\s*", "").trim();

            URI searchUri = UriComponentsBuilder.fromUriString("https://maps.googleapis.com/maps/api/place/textsearch/json")
                    .queryParam("query", cleanName)
                    .queryParam("location", lat + "," + lng)
                    .queryParam("radius", 1000)
                    .queryParam("key", googleApiKey)
                    .build()
                    .encode()
                    .toUri();

            String response = restTemplate.getForObject(searchUri, String.class);
            JsonNode root = mapper.readTree(response);
            JsonNode results = root.path("results");

            if (results.isArray() && !results.isEmpty()) {
                JsonNode place = results.get(0);
                JsonNode photos = place.path("photos");

                if (photos.isArray()) {
                    int limit = Math.min(photos.size(), 3);
                    for (int i = 0; i < limit; i++) {
                        String photoRef = photos.get(i).path("photo_reference").asText();
                        String photoUrl = "https://maps.googleapis.com/maps/api/place/photo?maxwidth=800&photoreference="
                                + photoRef + "&key=" + googleApiKey;

                        String actualCdnUrl = getRedirectUrl(photoUrl);
                        if (actualCdnUrl != null) {
                            images.add(actualCdnUrl);
                        }
                    }
                }
            }

            if (!images.isEmpty()) {
                imageCache.put(cacheKey, images);
                log.info("[GOOGLE API] 신규 검색 및 캐시 저장 완료 - {}", placeName);
            } else {
                log.warn("[GOOGLE API] 구글에 사진 없음 - {}", placeName);
            }

        } catch (Exception e) {
            log.error("[GOOGLE API] 구글 장소 검색 실패 - {}", placeName, e);
        }
        return images;
    }

    private synchronized boolean checkAndIncrementQuota() {
        LocalDate today = LocalDate.now();
        if (!today.equals(lastResetDate)) {
            lastResetDate = today;
            dailyApiCount.set(0);
        }

        if (dailyApiCount.get() >= 100) {
            return false;
        }

        int currentCount = dailyApiCount.incrementAndGet();
        log.info("[GOOGLE API] API 호출 완료 (오늘 잔여 횟수: {}/100)", currentCount);
        return true;
    }

    private String getRedirectUrl(String requestUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(requestUrl).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setInstanceFollowRedirects(false);
            conn.connect();
            int resCode = conn.getResponseCode();
            if (resCode == 301 || resCode == 302 || resCode == 303) {
                return conn.getHeaderField("Location");
            }
        } catch (Exception e) {
        }
        return requestUrl;
    }
}