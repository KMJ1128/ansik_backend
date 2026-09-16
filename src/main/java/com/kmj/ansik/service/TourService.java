package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmj.ansik.logging.ExternalApiLoggingInterceptor;
import com.kmj.ansik.dto.TourCourseCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TourService {

    private static final Logger log = LoggerFactory.getLogger(TourService.class);
    private static final String TOUR_API_ROOT = "https://apis.data.go.kr/B551011";
    private static final int MAX_RESTAURANT_RESULTS = 20;
    private static final int MAX_COURSE_CANDIDATES = 100;
    private static final int MAX_HUB_RESULTS_PER_REGION = 35;
    private static final DateTimeFormatter YEAR_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Map<String, CitySpec> COURSE_CITIES = Map.ofEntries(
            Map.entry("SEOUL", new CitySpec(List.of(
                    new GeoPoint(126.9780, 37.5665),
                    new GeoPoint(126.9237, 37.5563),
                    new GeoPoint(127.0276, 37.4979),
                    new GeoPoint(127.1050, 37.5133),
                    new GeoPoint(126.9810, 37.5324)
            ), 8500, "11", List.of("11110", "11440", "11680", "11710", "11170"))),
            Map.entry("BUSAN", new CitySpec(List.of(
                    new GeoPoint(129.0756, 35.1796),
                    new GeoPoint(129.1604, 35.1631),
                    new GeoPoint(129.0305, 35.0987)
            ), 9500, "26", List.of("26110", "26350", "26200"))),
            Map.entry("JEJU", new CitySpec(List.of(
                    new GeoPoint(126.5312, 33.4996),
                    new GeoPoint(126.5600, 33.2541)
            ), 20000, "50", List.of("50110", "50130"))),
            Map.entry("INCHEON", new CitySpec(List.of(new GeoPoint(126.7052, 37.4563)), 18000, "28", List.of("28110", "28185"))),
            Map.entry("DAEGU", new CitySpec(List.of(new GeoPoint(128.6014, 35.8714)), 18000, "27", List.of("27110", "27260"))),
            Map.entry("DAEJEON", new CitySpec(List.of(new GeoPoint(127.3845, 36.3504)), 18000, "30", List.of("30170", "30200"))),
            Map.entry("GWANGJU", new CitySpec(List.of(new GeoPoint(126.8526, 35.1595)), 18000, "29", List.of("29110", "29140"))),
            Map.entry("ULSAN", new CitySpec(List.of(new GeoPoint(129.3114, 35.5384)), 18000, "31", List.of("31140", "31200"))),
            Map.entry("SEJONG", new CitySpec(List.of(new GeoPoint(127.2890, 36.4800)), 18000, "36", List.of("36110"))),
            Map.entry("GYEONGJU", new CitySpec(List.of(new GeoPoint(129.2247, 35.8562)), 18000, "47", List.of("47130"))),
            Map.entry("GANGNEUNG", new CitySpec(List.of(new GeoPoint(128.8761, 37.7519)), 18000, "51", List.of("51150"))),
            Map.entry("JEONJU", new CitySpec(List.of(new GeoPoint(127.1480, 35.8242)), 16000, "52", List.of("52111", "52113"))),
            Map.entry("YEOSU", new CitySpec(List.of(new GeoPoint(127.6622, 34.7604)), 18000, "46", List.of("46130"))),
            Map.entry("SOKCHO", new CitySpec(List.of(new GeoPoint(128.5918, 38.2070)), 16000, "51", List.of("51210")))
    );

    private final RestTemplate restTemplate;
    private final ExternalApiBulkhead bulkhead;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${api.tourapi.key}")
    private String tourApiKey;

    @Autowired
    public TourService(ExternalApiBulkhead bulkhead) {
        this.bulkhead = bulkhead;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getInterceptors().add(new ExternalApiLoggingInterceptor("TOUR API"));
    }

    public TourService() {
        this(null);
    }

    public List<String> getTourOfficialImages(String contentId, String language) {
        return getTourImages(contentId, "Y", language);
    }

    public List<String> findOfficialPlaceImages(String name, double latitude, double longitude) {
        if (name == null || name.isBlank() || latitude == 0 || longitude == 0) return List.of();
        try {
            URI uri = UriComponentsBuilder.fromUriString(TOUR_API_ROOT + "/KorService2/locationBasedList2")
                    .queryParam("MobileOS", "AND").queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json").queryParam("numOfRows", 100).queryParam("pageNo", 1)
                    .queryParam("mapX", longitude).queryParam("mapY", latitude).queryParam("radius", 1500)
                    .queryParam("arrange", "E").queryParam("serviceKey", tourApiKey).build(true).toUri();
            JsonNode items = mapper.readTree(requestValidBody(uri)).path("response").path("body").path("items").path("item");
            String target = normalizeTourName(name);
            for (JsonNode item : items) {
                String candidate = normalizeTourName(item.path("title").asText(""));
                if (!candidate.equals(target)) continue;
                String first = item.path("firstimage").asText("");
                if (first.isBlank()) first = item.path("firstimage2").asText("");
                if (!first.isBlank()) return List.of(first.replace("http://", "https://"));
                return getTourOfficialImages(item.path("contentid").asText(""), "ko");
            }
        } catch (Exception e) {
            log.warn("[TOUR IMAGE] 장소 재매칭 실패 - name={}", name);
        }
        return List.of();
    }

    public record PlaceStory(String title, String overview, List<String> images, String language) {}

    public PlaceStory getPlaceStory(String title, double latitude, double longitude, String language) {
        String requested = normalizeLanguage(language);
        for (String lang : "ko".equals(requested) ? List.of("ko") : List.of(requested, "ko")) {
            try {
                TourApiLocale locale = resolveLocale(lang);
                List<String> storyTypes = "ko".equals(lang) ? List.of("12", "14") : List.of("76", "78");
                String keyword = java.util.Arrays.stream(title.split("[()\\n]"))
                        .map(String::trim)
                        .filter(part -> !part.isBlank())
                        .filter(part -> !"ko".equals(lang) || part.matches(".*\\p{IsHangul}.*"))
                        .findFirst()
                        .orElse(title.trim());
                for (String storyType : storyTypes) {
                    URI search = UriComponentsBuilder.fromUriString(locale.baseUrl() + "/searchKeyword2")
                            .queryParam("MobileOS", "AND").queryParam("MobileApp", "Ansik")
                            .queryParam("_type", "json").queryParam("numOfRows", 100).queryParam("pageNo", 1)
                            .queryParam("contentTypeId", storyType)
                            .queryParam("keyword", URLEncoder.encode(
                                    "ko".equals(lang) ? normalizeTourName(keyword) : keyword,
                                    StandardCharsets.UTF_8))
                            .queryParam("arrange", "E").queryParam("serviceKey", tourApiKey).build(true).toUri();
                    JsonNode items = mapper.readTree(requestValidBody(search)).path("response").path("body").path("items").path("item");
                    if (!items.isArray()) continue;
                    for (JsonNode item : items) {
                    String type = item.path("contenttypeid").asText();
                    // Attractions and cultural facilities only; a restaurant overview is not a heritage story.
                    if (!Set.of("12", "14", "76", "78").contains(type)) continue;
                    String name = normalizeTourName(item.path("title").asText());
                    boolean matches = java.util.Arrays.stream(title.split("[()\\n]"))
                            .map(this::normalizeTourName).anyMatch(part -> !part.isBlank() && part.equals(name));
                    if (!matches) continue;
                    String id = item.path("contentid").asText();
                    URI detail = UriComponentsBuilder.fromUriString(locale.baseUrl() + "/detailCommon2")
                            .queryParam("MobileOS", "AND").queryParam("MobileApp", "Ansik")
                            .queryParam("_type", "json").queryParam("contentId", id)
                            .queryParam("serviceKey", tourApiKey).build(true).toUri();
                    JsonNode details = mapper.readTree(requestValidBody(detail)).path("response").path("body").path("items").path("item");
                    if (!details.isArray() || details.isEmpty()) continue;
                    String overview = details.get(0).path("overview").asText("").trim();
                    if (overview.isBlank()) continue;
                    List<String> images = new ArrayList<>();
                    String first = item.path("firstimage").asText("");
                    if (!first.isBlank()) images.add(first);
                    images.addAll(getTourOfficialImages(id, lang));
                    return new PlaceStory(item.path("title").asText(title), overview,
                            images.stream().distinct().limit(8).toList(), lang);
                    }
                }
            } catch (Exception e) {
                log.warn("[PLACE STORY] 조회 실패 - title={}, language={}, error={}", title, lang, e.getClass().getSimpleName());
            }
        }
        return new PlaceStory(title, "", List.of(), requested);
    }

    public ResponseEntity<String> getNearbyRestaurants(
            double mapX,
            double mapY,
            int radius,
            String language
    ) {
        try {
            TourApiLocale locale = resolveLocale(language);
            URI uri = UriComponentsBuilder
                    .fromUriString(locale.baseUrl() + "/locationBasedList2")
                    .queryParam("MobileOS", "AND")
                    .queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json")
                    .queryParam("contentTypeId", locale.restaurantContentTypeId())
                    .queryParam("numOfRows", MAX_RESTAURANT_RESULTS)
                    .queryParam("pageNo", 1)
                    .queryParam("arrange", "E")
                    .queryParam("mapX", mapX)
                    .queryParam("mapY", mapY)
                    .queryParam("radius", radius)
                    .queryParam("serviceKey", tourApiKey)
                    .build(true)
                    .toUri();

            // Contest compliance: tourism data is fetched from the OpenAPI in real time.
            // Do not persist or serve a database-cached copy of this response.
            String body = requestValidBody(uri);

            if (isInvalidTourResponse(body)) {
                return getEmptyTourResponse();
            }

            JsonNode rootNode = mapper.readTree(body);
            JsonNode itemsNode = rootNode
                    .path("response")
                    .path("body")
                    .path("items");

            if (!itemsNode.isObject() || !itemsNode.has("item")) {
                return getEmptyTourResponse();
            }

            JsonNode itemNode = itemsNode.path("item");
            if (!itemNode.isArray()) {
                return getEmptyTourResponse();
            }

            return ResponseEntity.ok(mapper.writeValueAsString(rootNode));
        } catch (Exception e) {
            log.error("[TOUR LOCATION] 주변 음식점 검색 실패 - language={}", language, e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    public List<TourCourseCandidate> getCourseCandidates(String cityCode) {
        String normalizedCode = cityCode == null ? "" : cityCode.trim().toUpperCase();
        CitySpec city = COURSE_CITIES.get(normalizedCode);
        if (city == null) {
            log.warn("[TOUR COURSE] 지원하지 않는 도시 - cityCode={}", cityCode);
            return List.of();
        }

        Map<String, TourCourseCandidate> nearbyCandidates = new LinkedHashMap<>();
        for (GeoPoint center : city.centers()) {
            try {
                URI uri = UriComponentsBuilder
                        .fromUriString(TOUR_API_ROOT + "/KorService2/locationBasedList2")
                        .queryParam("MobileOS", "AND")
                        .queryParam("MobileApp", "Ansik")
                        .queryParam("_type", "json")
                        .queryParam("numOfRows", 80)
                        .queryParam("pageNo", 1)
                        .queryParam("arrange", "E")
                        .queryParam("mapX", center.longitude())
                        .queryParam("mapY", center.latitude())
                        .queryParam("radius", city.radiusMeters())
                        .queryParam("serviceKey", tourApiKey)
                        .build(true)
                        .toUri();

                String body = requestValidBody(uri);
                if (isInvalidTourResponse(body)) continue;
                JsonNode items = mapper.readTree(body)
                        .path("response").path("body").path("items").path("item");
                if (!items.isArray()) continue;

                for (JsonNode item : items) {
                    String contentTypeId = item.path("contenttypeid").asText("");
                    String category = courseCategory(contentTypeId);
                    if (category.isBlank()) continue;
                    String id = item.path("contentid").asText("").trim();
                    String name = item.path("title").asText("").trim();
                    double longitude = item.path("mapx").asDouble(0.0);
                    double latitude = item.path("mapy").asDouble(0.0);
                    if (id.isBlank() || name.isBlank() || latitude == 0.0 || longitude == 0.0) continue;

                    String imageUrl = item.path("firstimage").asText("");
                    if (imageUrl.isBlank()) imageUrl = item.path("firstimage2").asText("");
                    nearbyCandidates.putIfAbsent(id, new TourCourseCandidate(
                            id,
                            name,
                            item.path("addr1").asText("").trim(),
                            courseArea(item.path("addr1").asText("")),
                            category,
                            imageUrl.replace("http://", "https://"),
                            latitude,
                            longitude
                    ));
                }
            } catch (Exception e) {
                log.warn("[TOUR COURSE] 후보 조회 실패 - cityCode={}, center={}", normalizedCode, center, e);
            }
        }

        List<TourCourseCandidate> nearby = nearbyCandidates.values().stream().toList();
        Map<String, TourCourseCandidate> ranked = new LinkedHashMap<>();
        Set<String> rankedNames = new HashSet<>();
        List<String> orderedDistricts = orderDistrictsByTourismDiversity(city);

        for (String districtCode : orderedDistricts) {
            addHubCandidates(city, districtCode, nearby, ranked, rankedNames);
        }
        for (String districtCode : orderedDistricts) {
            addRelatedCandidates(city, districtCode, nearby, ranked, rankedNames);
        }

        int fallbackRank = 5000;
        for (TourCourseCandidate candidate : diversifyByArea(nearby, MAX_COURSE_CANDIDATES)) {
            if (ranked.size() >= MAX_COURSE_CANDIDATES) break;
            String normalizedName = normalizeTourName(candidate.name());
            if (ranked.containsKey(candidate.id()) || rankedNames.contains(normalizedName)) continue;
            ranked.put(candidate.id(), withRanking(candidate, fallbackRank++, "TOURAPI_NEARBY"));
            rankedNames.add(normalizedName);
        }

        List<TourCourseCandidate> result = new ArrayList<>(ranked.values().stream()
                .limit(MAX_COURSE_CANDIDATES)
                .toList());
        List<TourCourseCandidate> restaurantCandidates = nearby.stream()
                .filter(candidate -> "RESTAURANT".equals(candidate.category()))
                .filter(candidate -> result.stream().noneMatch(value -> value.id().equals(candidate.id())))
                .limit(7)
                .toList();
        for (TourCourseCandidate restaurant : restaurantCandidates) {
            if (result.size() >= MAX_COURSE_CANDIDATES) {
                int replaceIndex = -1;
                for (int index = result.size() - 1; index >= 0; index--) {
                    if (!"RESTAURANT".equals(result.get(index).category())) {
                        replaceIndex = index;
                        break;
                    }
                }
                if (replaceIndex < 0) break;
                result.remove(replaceIndex);
            }
            result.add(withRanking(restaurant, 4000 + result.size(), "TOURAPI_NEARBY"));
        }
        long verifiedCount = result.stream()
                .filter(candidate -> !"TOURAPI_NEARBY".equals(candidate.selectionBasis()))
                .count();
        log.info("[TOUR COURSE] 도시 후보 조회 완료 - cityCode={}, candidateCount={}, rankedTourismCount={}",
                normalizedCode, result.size(), verifiedCount);
        return result;
    }

    private void addHubCandidates(
            CitySpec city,
            String districtCode,
            List<TourCourseCandidate> nearby,
            Map<String, TourCourseCandidate> ranked,
            Set<String> rankedNames
    ) {
        JsonNode items = fetchLatestBigDataItems(
                "/LocgoHubTarService1/areaBasedList1",
                city.areaCode(), districtCode,
                Map.of("numOfRows", String.valueOf(MAX_HUB_RESULTS_PER_REGION))
        );
        if (!items.isArray()) return;

        for (JsonNode item : items) {
            String name = item.path("hubTatsNm").asText("").trim();
            String largeCategory = item.path("hubCtgryLclsNm").asText("").trim();
            String middleCategory = item.path("hubCtgryMclsNm").asText("").trim();
            int rank = item.path("hubRank").asInt(999);
            double longitude = item.path("mapX").asDouble(0.0);
            double latitude = item.path("mapY").asDouble(0.0);
            if (!isEligibleHub(name, largeCategory, middleCategory)
                    || latitude == 0.0 || longitude == 0.0) continue;

            String normalizedName = normalizeTourName(name);
            if (normalizedName.isBlank() || rankedNames.contains(normalizedName)) continue;
            TourCourseCandidate matched = findCandidateByName(name, nearby);
            TourCourseCandidate candidate;
            if (matched != null) {
                candidate = withRanking(matched, rank, "KTO_HUB", middleCategory);
            } else {
                String id = "hub-" + UUID.nameUUIDFromBytes(
                        (name + "|" + latitude + "|" + longitude).getBytes(StandardCharsets.UTF_8)
                );
                String areaName = item.path("signguNm").asText("").trim();
                String address = (item.path("areaNm").asText("") + " " + areaName).trim();
                candidate = new TourCourseCandidate(
                        id, name.replace('/', ' '), address, areaName,
                        hubCategory(largeCategory, middleCategory), "",
                        latitude, longitude, rank, "KTO_HUB", middleCategory
                );
            }
            ranked.putIfAbsent(candidate.id(), candidate);
            rankedNames.add(normalizeTourName(candidate.name()));
        }
    }

    private void addRelatedCandidates(
            CitySpec city,
            String districtCode,
            List<TourCourseCandidate> nearby,
            Map<String, TourCourseCandidate> ranked,
            Set<String> rankedNames
    ) {
        JsonNode items = fetchLatestBigDataItems(
                "/TarRlteTarService1/areaBasedList1",
                city.areaCode(), districtCode,
                Map.of("numOfRows", "100")
        );
        if (!items.isArray()) return;

        for (JsonNode item : items) {
            String name = item.path("rlteTatsNm").asText("").trim();
            int rank = item.path("rlteRank").asInt(999);
            TourCourseCandidate matched = findCandidateByName(name, nearby);
            if (matched == null) continue;
            String normalizedName = normalizeTourName(matched.name());
            if (rankedNames.contains(normalizedName)) continue;
            TourCourseCandidate candidate = withRanking(
                    matched, 1000 + rank, "KTO_RELATED",
                    item.path("rlteCtgryMclsNm").asText("").trim()
            );
            ranked.putIfAbsent(candidate.id(), candidate);
            rankedNames.add(normalizedName);
        }
    }

    private List<String> orderDistrictsByTourismDiversity(CitySpec city) {
        Map<String, Double> scores = new HashMap<>();
        JsonNode items = fetchLatestBigDataItems(
                "/AreaTarDivService/areaTouDivList",
                city.areaCode(), null,
                Map.of("numOfRows", "200", "touDivIxCd", "31")
        );
        if (items.isArray()) {
            for (JsonNode item : items) {
                String districtCode = item.path("signguCd").asText("").trim();
                double value = item.path("touDivIxVal").asDouble(0.0);
                if (!districtCode.isBlank()) scores.merge(districtCode, value, Math::max);
            }
        }
        return city.districtCodes().stream()
                .sorted(Comparator.comparingDouble((String code) -> scores.getOrDefault(code, 0.0)).reversed())
                .toList();
    }

    private JsonNode fetchLatestBigDataItems(
            String path,
            String areaCode,
            String districtCode,
            Map<String, String> extraParameters
    ) {
        for (int monthsAgo = 1; monthsAgo <= 12; monthsAgo++) {
            String baseMonth = YearMonth.now().minusMonths(monthsAgo).format(YEAR_MONTH_FORMAT);
            try {
                UriComponentsBuilder builder = UriComponentsBuilder
                        .fromUriString(TOUR_API_ROOT + path)
                        .queryParam("MobileOS", "AND")
                        .queryParam("MobileApp", "Ansik")
                        .queryParam("_type", "json")
                        .queryParam("pageNo", 1)
                        .queryParam("baseYm", baseMonth)
                        .queryParam("areaCd", areaCode)
                        .queryParam("serviceKey", tourApiKey);
                if (districtCode != null && !districtCode.isBlank()) {
                    builder.queryParam("signguCd", districtCode);
                }
                extraParameters.forEach(builder::queryParam);
                String body = requestValidBody(builder.build(true).toUri());
                JsonNode root = mapper.readTree(body);
                String resultCode = root.path("response").path("header").path("resultCode").asText("");
                if (!resultCode.isBlank() && !"0000".equals(resultCode)) return mapper.createArrayNode();
                JsonNode items = root.path("response").path("body").path("items").path("item");
                if (items.isArray() && !items.isEmpty()) return items;
            } catch (Exception e) {
                log.debug("[TOUR BIGDATA] 조회 실패 - path={}, areaCode={}, districtCode={}, baseMonth={}",
                        path, areaCode, districtCode, baseMonth, e);
                return mapper.createArrayNode();
            }
        }
        return mapper.createArrayNode();
    }

    private TourCourseCandidate findCandidateByName(String targetName, List<TourCourseCandidate> candidates) {
        String target = normalizeTourName(targetName);
        if (target.length() < 2) return null;
        return candidates.stream()
                .filter(candidate -> {
                    String candidateName = normalizeTourName(candidate.name());
                    if (candidateName.equals(target)) return true;
                    int shorter = Math.min(candidateName.length(), target.length());
                    return shorter >= 4 && (candidateName.contains(target) || target.contains(candidateName));
                })
                .min(Comparator.comparingInt(candidate ->
                        Math.abs(normalizeTourName(candidate.name()).length() - target.length())))
                .orElse(null);
    }

    private TourCourseCandidate withRanking(TourCourseCandidate candidate, int rank, String basis) {
        return withRanking(candidate, rank, basis, candidate.tourismTheme());
    }

    private TourCourseCandidate withRanking(
            TourCourseCandidate candidate,
            int rank,
            String basis,
            String tourismTheme
    ) {
        return new TourCourseCandidate(
                candidate.id(), candidate.name(), candidate.address(), candidate.area(),
                candidate.category(), candidate.imageUrl(), candidate.latitude(), candidate.longitude(),
                rank, basis, tourismTheme
        );
    }

    private String normalizeTourName(String value) {
        if (value == null) return "";
        return value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\([^)]*\\)", "")
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private boolean isEligibleHub(String name, String largeCategory, String middleCategory) {
        String categories = largeCategory + " " + middleCategory;
        if (name.isBlank() || categories.contains("숙박") || categories.contains("교통")) return false;
        String normalizedName = normalizeTourName(name);
        return !normalizedName.matches(".*(호텔|리조트|모텔|펜션|게스트하우스|레지던스)$")
                && !(middleCategory.contains("기타관광") && normalizedName.endsWith("역"));
    }

    private String hubCategory(String largeCategory, String middleCategory) {
        String value = largeCategory + " " + middleCategory;
        if (value.contains("음식")) return "RESTAURANT";
        if (value.contains("쇼핑")) return "SHOPPING";
        if (value.contains("문화")) return "CULTURE";
        if (value.contains("레저") || value.contains("스포츠")) return "LEISURE";
        return "ATTRACTION";
    }

    private List<TourCourseCandidate> diversifyByArea(List<TourCourseCandidate> candidates, int limit) {
        Map<String, List<TourCourseCandidate>> byArea = new LinkedHashMap<>();
        for (TourCourseCandidate candidate : candidates) {
            byArea.computeIfAbsent(candidate.area(), ignored -> new ArrayList<>()).add(candidate);
        }
        List<TourCourseCandidate> result = new ArrayList<>();
        int round = 0;
        boolean added;
        do {
            added = false;
            for (List<TourCourseCandidate> areaCandidates : byArea.values()) {
                if (round < areaCandidates.size() && result.size() < limit) {
                    result.add(areaCandidates.get(round));
                    added = true;
                }
            }
            round++;
        } while (added && result.size() < limit);
        return result;
    }

    private String courseArea(String address) {
        if (address == null || address.isBlank()) return "기타";
        String[] parts = address.trim().split("\\s+");
        return parts.length >= 2 ? parts[1] : parts[0];
    }

    private String courseCategory(String contentTypeId) {
        return switch (contentTypeId) {
            case "12" -> "ATTRACTION";
            case "14" -> "CULTURE";
            case "15" -> "FESTIVAL";
            case "28" -> "LEISURE";
            case "38" -> "SHOPPING";
            case "39" -> "RESTAURANT";
            default -> "";
        };
    }

    public ResponseEntity<String> getRestaurantDetails(String contentId, String language) {
        try {
            TourApiLocale locale = resolveLocale(language);
            URI uri = UriComponentsBuilder
                    .fromUriString(locale.baseUrl() + "/detailIntro2")
                    .queryParam("MobileOS", "AND")
                    .queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json")
                    .queryParam("contentTypeId", locale.restaurantContentTypeId())
                    .queryParam("numOfRows", 1)
                    .queryParam("pageNo", 1)
                    .queryParam("contentId", contentId)
                    .queryParam("serviceKey", tourApiKey)
                    .build(true)
                    .toUri();

            String body = requestValidBody(uri);

            if (isInvalidTourResponse(body)) {
                return ResponseEntity.ok("{}");
            }

            JsonNode rootNode = mapper.readTree(body);
            return ResponseEntity.ok(mapper.writeValueAsString(rootNode));
        } catch (Exception e) {
            log.error("[TOUR DETAIL] 음식점 상세 검색 실패 - contentId={}, language={}", contentId, language, e);
            return ResponseEntity.status(500).body("{}");
        }
    }

    private List<String> getTourImages(String contentId, String imageYn, String language) {
        List<String> images = new ArrayList<>();

        if (contentId == null || contentId.isBlank()) {
            return images;
        }

        try {
            TourApiLocale locale = resolveLocale(language);
            URI uri = UriComponentsBuilder
                    .fromUriString(locale.baseUrl() + "/detailImage2")
                    .queryParam("MobileOS", "AND")
                    .queryParam("MobileApp", "Ansik")
                    .queryParam("_type", "json")
                    .queryParam("imageYN", imageYn)
                    .queryParam("numOfRows", 20)
                    .queryParam("pageNo", 1)
                    .queryParam("contentId", contentId)
                    .queryParam("serviceKey", tourApiKey)
                    .build(true)
                    .toUri();

            String body = requestValidBody(uri);

            if (isInvalidTourResponse(body)) {
                return images;
            }

            JsonNode root = mapper.readTree(body);
            JsonNode itemArray = root
                    .path("response")
                    .path("body")
                    .path("items")
                    .path("item");

            if (!itemArray.isArray()) {
                return images;
            }

            for (JsonNode item : itemArray) {
                String imageUrl = item.path("originimgurl").asText("");
                if (imageUrl.isBlank()) {
                    imageUrl = item.path("originImgurl").asText("");
                }

                if (!imageUrl.isBlank()) {
                    images.add(imageUrl.replace("http://", "https://"));
                }
            }
        } catch (Exception e) {
            log.error(
                    "[TOUR DETAIL IMAGE] 이미지 조회 실패 - contentId={}, imageYN={}, language={}",
                    contentId,
                    imageYn,
                    language,
                    e
            );
        }

        return images.stream().distinct().toList();
    }

    private String requestValidBody(URI uri) {
        byte[] responseBytes = bulkhead == null
                ? restTemplate.getForEntity(uri, byte[].class).getBody()
                : bulkhead.tourApi(() -> restTemplate.getForEntity(uri, byte[].class).getBody());
        String body = responseBytes == null
                ? null
                : new String(responseBytes, StandardCharsets.UTF_8);
        if (isInvalidTourResponse(body)) {
            throw new IllegalStateException("TourAPI returned an invalid response");
        }
        return body;
    }

    private boolean isInvalidTourResponse(String body) {
        return body == null || body.isBlank() || body.trim().startsWith("<");
    }

    private ResponseEntity<String> getEmptyTourResponse() {
        return ResponseEntity.ok(
                "{\"response\":{\"body\":{\"items\":{\"item\":[]}}}}"
        );
    }

    public String normalizeLanguage(String language) {
        return resolveLocale(language).language();
    }

    private TourApiLocale resolveLocale(String language) {
        String normalized = language == null
                ? "ko"
                : language.trim().toLowerCase(java.util.Locale.ROOT);

        return switch (normalized) {
            case "en", "en-us", "en-gb" ->
                    new TourApiLocale("en", TOUR_API_ROOT + "/EngService2", 82);
            case "ja", "ja-jp" ->
                    new TourApiLocale("ja", TOUR_API_ROOT + "/JpnService2", 82);
            case "zh", "zh-cn", "zh-hans", "zh-hans-cn" ->
                    new TourApiLocale("zh-CN", TOUR_API_ROOT + "/ChsService2", 82);
            default ->
                    new TourApiLocale("ko", TOUR_API_ROOT + "/KorService2", 39);
        };
    }

    private record TourApiLocale(
            String language,
            String baseUrl,
            int restaurantContentTypeId
    ) {
    }

    private record GeoPoint(double longitude, double latitude) {
    }

    private record CitySpec(
            List<GeoPoint> centers,
            int radiusMeters,
            String areaCode,
            List<String> districtCodes
    ) {
    }
}
