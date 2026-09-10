package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kmj.ansik.dto.AiCourseDto;
import com.kmj.ansik.dto.AiCourseDto.CourseDay;
import com.kmj.ansik.dto.AiCourseDto.CourseStop;
import com.kmj.ansik.dto.AiCourseRequest;
import com.kmj.ansik.dto.TourCourseCandidate;
import com.kmj.ansik.logging.ExternalApiLoggingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OpenAiCourseService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCourseService.class);
    private static final int MAX_DAYS = 7;
    private static final int MIN_STOPS_PER_DAY = 3;
    private static final int MAX_STOPS_PER_DAY = 7;

    private final TourService tourService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final ExternalApiBulkhead bulkhead;

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.model:gpt-5.6-luna}")
    private String model;

    @Value("${openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Autowired
    public OpenAiCourseService(TourService tourService, ExternalApiBulkhead bulkhead) {
        this.tourService = tourService;
        this.bulkhead = bulkhead;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(45000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getInterceptors().add(new ExternalApiLoggingInterceptor("OPENAI COURSE"));
    }

    public OpenAiCourseService(TourService tourService) {
        this(tourService, null);
    }

    public AiCourseDto createCourse(AiCourseRequest request) {
        String cityCode = clean(request.cityCode(), 24).toUpperCase(Locale.ROOT);
        String cityName = clean(request.cityName(), 60);
        int days = Math.max(1, Math.min(MAX_DAYS, request.days()));
        int nights = Math.max(0, Math.min(days - 1, request.nights()));
        int stopsPerDay = Math.max(MIN_STOPS_PER_DAY, Math.min(MAX_STOPS_PER_DAY, request.stopsPerDay()));
        String existingSchedule = cleanMultiline(request.existingSchedule(), 3000);
        List<String> preferences = cleanList(request.preferences(), 8, 60);
        List<String> healthConditions = cleanList(request.healthConditions(), 20, 80);
        String language = normalizeLanguage(request.language());

        if (cityCode.isBlank() || cityName.isBlank()) {
            return AiCourseDto.unavailable(cityCode, cityName, nights, days, preferences, "INVALID_REQUEST");
        }
        if (apiKey == null || apiKey.isBlank()) {
            return AiCourseDto.unavailable(cityCode, cityName, nights, days, preferences, "OPENAI_NOT_CONFIGURED");
        }

        List<TourCourseCandidate> allCandidates = tourService.getCourseCandidates(cityCode);
        List<ScheduledCandidate> scheduledCandidates = matchScheduledCandidates(
                existingSchedule, allCandidates, days
        );
        List<TourCourseCandidate> qualityCandidates = qualityCandidatePool(
                allCandidates, scheduledCandidates, days * stopsPerDay
        );
        int candidateLimit = Math.min(100, Math.max(48, days * stopsPerDay * 2));
        List<TourCourseCandidate> candidates = prioritizeCandidates(
                qualityCandidates, scheduledCandidates, candidateLimit
        );
        if (candidates.size() < days * stopsPerDay) {
            log.warn("[OPENAI COURSE] 관광 후보 부족 - cityCode={}, days={}, candidates={}",
                    cityCode, days, candidates.size());
            return AiCourseDto.unavailable(cityCode, cityName, nights, days, preferences, "INSUFFICIENT_PLACES");
        }

        long startedAt = System.nanoTime();
        log.info("[OPENAI COURSE] 생성 시작 - cityCode={}, days={}, preferences={}, healthConditionCount={}, candidates={}, model={}",
                cityCode, days, preferences, healthConditions.size(), candidates.size(), model);
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("store", false);
            body.put("max_output_tokens", Math.min(5200, 1100 + days * stopsPerDay * 95));
            body.set("reasoning", mapper.createObjectNode().put("effort", "low"));
            body.put("instructions", instructions(language, days, stopsPerDay));
            body.put("input", requestInput(
                    cityName, nights, days, stopsPerDay, existingSchedule,
                    preferences, healthConditions, candidates, scheduledCandidates
            ));
            ObjectNode text = mapper.createObjectNode();
            text.put("verbosity", "low");
            text.set("format", structuredOutputFormat(candidates, days, stopsPerDay));
            body.set("text", text);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            java.util.function.Supplier<ResponseEntity<String>> openAiCall = () -> restTemplate.exchange(
                    URI.create(baseUrl.replaceAll("/$", "") + "/responses"),
                    HttpMethod.POST, new HttpEntity<>(body.toString(), headers), String.class
            );
            ResponseEntity<String> response = bulkhead == null
                    ? openAiCall.get()
                    : bulkhead.openAi(openAiCall);

            JsonNode responseRoot = mapper.readTree(response.getBody());
            log.info("[OPENAI COURSE] 모델 응답 - responseId={}, status={}, inputTokens={}, outputTokens={}, elapsedMs={}",
                    responseRoot.path("id").asText(""),
                    responseRoot.path("status").asText(""),
                    responseRoot.path("usage").path("input_tokens").asInt(0),
                    responseRoot.path("usage").path("output_tokens").asInt(0),
                    elapsedMillis(startedAt));

            JsonNode result = mapper.readTree(extractOutputText(responseRoot));
            List<CourseDay> itinerary = resolveItinerary(
                    result.path("itinerary"), candidates, days, stopsPerDay,
                    !existingSchedule.isBlank()
            );
            itinerary = enforceScheduledCandidates(
                    itinerary, scheduledCandidates, candidates, days, stopsPerDay
            );
            if (itinerary.size() != days || itinerary.stream().anyMatch(day -> day.stops().size() != stopsPerDay)) {
                log.warn("[OPENAI COURSE] 불완전한 일정 응답 - requestedDays={}, returnedDays={}",
                        days, itinerary.size());
                return AiCourseDto.unavailable(cityCode, cityName, nights, days, preferences, "OPENAI_NO_RESULT");
            }

            return new AiCourseDto(
                    UUID.randomUUID().toString(),
                    result.path("title").asText(cityName + " AI Course").trim(),
                    cityCode,
                    cityName,
                    nights,
                    days,
                    preferences,
                    result.path("summary").asText("").trim(),
                    result.path("selectionReason").asText("").trim(),
                    stringList(result.path("travelTips"), 6),
                    itinerary,
                    "OPENAI_READY",
                    model
            );
        } catch (Exception e) {
            log.warn("[OPENAI COURSE] 생성 실패 - cityCode={}, elapsedMs={}",
                    cityCode, elapsedMillis(startedAt), e);
            return AiCourseDto.unavailable(cityCode, cityName, nights, days, preferences, "OPENAI_ERROR");
        }
    }

    private List<CourseDay> resolveItinerary(
            JsonNode dayNodes,
            List<TourCourseCandidate> candidates,
            int requestedDays,
            int stopsPerDay,
            boolean preserveFixedSchedule
    ) {
        if (!dayNodes.isArray()) return List.of();
        Map<String, TourCourseCandidate> byId = new HashMap<>();
        candidates.forEach(candidate -> byId.put(candidate.id(), candidate));
        Set<String> usedIds = new HashSet<>();
        List<CourseDay> result = new ArrayList<>();

        for (JsonNode dayNode : dayNodes) {
            int day = dayNode.path("day").asInt(0);
            if (day < 1 || day > requestedDays || result.stream().anyMatch(value -> value.day() == day)) continue;
            List<CourseStop> stops = new ArrayList<>();
            for (JsonNode stopNode : dayNode.path("stops")) {
                if (stops.size() >= stopsPerDay) break;
                String candidateId = stopNode.path("candidateId").asText("");
                TourCourseCandidate place = byId.get(candidateId);
                if (place == null || !usedIds.add(candidateId)) continue;
                stops.add(new CourseStop(
                        place.id(), place.name(), place.address(), place.category(), place.imageUrl(),
                        place.latitude(), place.longitude(),
                        stopNode.path("recommendedTime").asText("").trim(),
                        stopNode.path("reason").asText("").trim(),
                        stopNode.path("visitTip").asText("").trim(),
                        stopNode.path("healthNote").asText("").trim()
                ));
            }
            if (!stops.isEmpty()) {
                result.add(new CourseDay(
                        day,
                        dayNode.path("theme").asText("").trim(),
                        preserveFixedSchedule ? stops : optimizeRoute(stops)
                ));
            }
        }
        return result.stream().sorted(java.util.Comparator.comparingInt(CourseDay::day)).toList();
    }

    private ObjectNode structuredOutputFormat(
            List<TourCourseCandidate> candidates,
            int days,
            int stopsPerDay
    ) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        properties.putObject("title").put("type", "string");
        properties.putObject("summary").put("type", "string");
        properties.putObject("selectionReason").put("type", "string");
        properties.putObject("travelTips").put("type", "array")
                .putObject("items").put("type", "string");

        ObjectNode itinerary = properties.putObject("itinerary");
        itinerary.put("type", "array");
        itinerary.put("minItems", days);
        itinerary.put("maxItems", days);
        ObjectNode dayItem = itinerary.putObject("items");
        dayItem.put("type", "object");
        dayItem.put("additionalProperties", false);
        ObjectNode dayProperties = dayItem.putObject("properties");
        dayProperties.putObject("day").put("type", "integer");
        dayProperties.putObject("theme").put("type", "string");
        ObjectNode stops = dayProperties.putObject("stops");
        stops.put("type", "array");
        stops.put("minItems", stopsPerDay);
        stops.put("maxItems", stopsPerDay);
        ObjectNode stopItem = stops.putObject("items");
        stopItem.put("type", "object");
        stopItem.put("additionalProperties", false);
        ObjectNode stopProperties = stopItem.putObject("properties");
        ObjectNode candidateId = stopProperties.putObject("candidateId");
        candidateId.put("type", "string");
        ArrayNode enumValues = candidateId.putArray("enum");
        candidates.forEach(candidate -> enumValues.add(candidate.id()));
        stopProperties.putObject("recommendedTime").put("type", "string");
        stopProperties.putObject("reason").put("type", "string");
        stopProperties.putObject("visitTip").put("type", "string");
        stopProperties.putObject("healthNote").put("type", "string");
        stopItem.putArray("required").add("candidateId").add("recommendedTime")
                .add("reason").add("visitTip").add("healthNote");
        dayItem.putArray("required").add("day").add("theme").add("stops");
        root.putArray("required").add("title").add("summary").add("selectionReason")
                .add("travelTips").add("itinerary");

        ObjectNode format = mapper.createObjectNode();
        format.put("type", "json_schema");
        format.put("name", "ai_travel_course");
        format.put("strict", true);
        format.set("schema", root);
        return format;
    }

    private String instructions(String language, int days, int stopsPerDay) {
        return """
                Create a practical %d-day travel itinerary using only candidateId values in the supplied TourAPI candidate list.
                Never invent a place, address, coordinate, opening hour, price, menu, accessibility feature, or medical fact.
                Candidate quality is evidence-ranked. KTO_HUB means an official local hub attraction ranked by tourism data, KTO_RELATED means an officially related attraction, and TOURAPI_NEARBY is only a fallback.
                Prefer lower tourismRank values. Do not select TOURAPI_NEARBY when enough KTO_HUB or KTO_RELATED candidates are available.
                Use tourismTheme, such as history tourism, cultural tourism, nature tourism, or shopping, to match the requested preferences accurately.
                Exclude accommodation, transport facilities, ordinary offices, and places that do not function as a meaningful visitor stop.
                Use exactly %d stops per day and never duplicate a place.
                Route efficiency is critical: group nearby places into the same day and list them in realistic travel order.
                For multi-day trips, give each day a distinct primary area so the whole course explores several districts without inefficient cross-city zigzags.
                For a one-day trip, use adjacent areas when diversity is needed. Use each candidate's area and coordinates.
                ExistingSchedule is authoritative, not merely a preference. Preserve its day assignment, relative order, fixed times, areas, and requested activities.
                When requiredScheduleMatches is non-empty, every listed candidateId MUST appear on its specified day and in the same relative order. Never replace those candidates.
                The schedule may be short, colloquial, typo-filled, or incomplete. Use scheduleHints to infer likely place names, neighborhoods, time-of-day, and activity intent.
                Correct obvious spelling variants only when strongly supported by candidate names. A neighborhood plus an activity (for example, "Seongsu cafe" or "Han River picnic") is a soft constraint: choose a suitable candidate in that area and category.
                Do not ignore a vague line. Preserve its intent and supplement missing details with nearby TourAPI candidates.
                Only fill schedule gaps or unavailable generic requests with other candidates. Detailed days in the user's schedule must remain recognizably the same.
                Restaurants may be included, but healthNote must be cautious because exact menus and recipes are not provided.
                Compare the plan with supplied health conditions without diagnosing. Mention pacing, rest, diet, allergens, or mobility only when relevant.
                Keep every reason and tip concise. Explain the overall selection rationale and give practical touring advice.
                Output all human-readable text in %s.
                """.formatted(days, stopsPerDay, language);
    }

    private List<TourCourseCandidate> qualityCandidatePool(
            List<TourCourseCandidate> allCandidates,
            List<ScheduledCandidate> scheduledCandidates,
            int requiredCount
    ) {
        Set<String> scheduledIds = scheduledCandidates.stream()
                .map(ScheduledCandidate::candidateId)
                .collect(java.util.stream.Collectors.toSet());
        long evidenceRankedCount = allCandidates.stream()
                .filter(candidate -> !"TOURAPI_NEARBY".equals(candidate.selectionBasis()))
                .count();
        if (evidenceRankedCount < requiredCount) return allCandidates;

        List<TourCourseCandidate> result = allCandidates.stream()
                .filter(candidate -> scheduledIds.contains(candidate.id())
                        || !"TOURAPI_NEARBY".equals(candidate.selectionBasis()))
                .toList();
        log.info("[OPENAI COURSE] 품질 후보 제한 - total={}, evidenceRanked={}, selectedPool={}",
                allCandidates.size(), evidenceRankedCount, result.size());
        return result;
    }

    private String requestInput(
            String cityName,
            int nights,
            int days,
            int stopsPerDay,
            String existingSchedule,
            List<String> preferences,
            List<String> healthConditions,
            List<TourCourseCandidate> candidates,
            List<ScheduledCandidate> scheduledCandidates
    ) throws Exception {
        ObjectNode input = mapper.createObjectNode();
        input.put("destination", cityName);
        input.put("nights", nights);
        input.put("days", days);
        input.put("stopsPerDay", stopsPerDay);
        input.put("existingSchedule", existingSchedule);
        input.set("scheduleHints", mapper.valueToTree(scheduleHints(existingSchedule)));
        input.set("preferences", mapper.valueToTree(preferences));
        input.set("healthConditions", mapper.valueToTree(healthConditions));
        input.set("tourApiCandidates", mapper.valueToTree(candidates));
        input.set("requiredScheduleMatches", mapper.valueToTree(scheduledCandidates));
        return input.toString();
    }

    private List<TourCourseCandidate> prioritizeCandidates(
            List<TourCourseCandidate> allCandidates,
            List<ScheduledCandidate> scheduledCandidates,
            int limit
    ) {
        Map<String, TourCourseCandidate> byId = new HashMap<>();
        allCandidates.forEach(candidate -> byId.put(candidate.id(), candidate));
        List<TourCourseCandidate> result = new ArrayList<>();
        for (ScheduledCandidate scheduled : scheduledCandidates) {
            TourCourseCandidate candidate = byId.get(scheduled.candidateId());
            if (candidate != null && result.stream().noneMatch(value -> value.id().equals(candidate.id()))) {
                result.add(candidate);
            }
        }
        for (TourCourseCandidate candidate : allCandidates) {
            if (result.size() >= limit) break;
            if (result.stream().noneMatch(value -> value.id().equals(candidate.id()))) result.add(candidate);
        }
        return result;
    }

    private List<ScheduledCandidate> matchScheduledCandidates(
            String schedule,
            List<TourCourseCandidate> candidates,
            int requestedDays
    ) {
        if (schedule.isBlank()) return List.of();
        List<ScheduledCandidate> result = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        int currentDay = 1;
        java.util.regex.Pattern dayPattern = java.util.regex.Pattern.compile(
                "(?i)(?:day\\s*(\\d+)|(\\d+)\\s*(?:일차|日目|天))"
        );
        java.util.regex.Pattern itemPattern = java.util.regex.Pattern.compile("^(?:[-•]|\\d+[.)])\\s+.*");

        for (String rawLine : schedule.split("\\R")) {
            String line = rawLine.trim();
            java.util.regex.Matcher dayMatcher = dayPattern.matcher(line);
            if (dayMatcher.find()) {
                String value = dayMatcher.group(1) != null ? dayMatcher.group(1) : dayMatcher.group(2);
                currentDay = Math.max(1, Math.min(requestedDays, Integer.parseInt(value)));
                line = line.substring(dayMatcher.end())
                        .replaceFirst("^[\\s:*#：-]+", "")
                        .replaceFirst("[\\s*]+$", "")
                        .trim();
                if (line.isBlank()) continue;
            }
            if (!isScheduleContentLine(line)) continue;

            int matchedDay = currentDay;
            for (String hint : splitScheduleHints(line)) {
                if (result.stream().filter(value -> value.day() == matchedDay).count() >= MAX_STOPS_PER_DAY) break;
                String normalizedHint = normalizeForMatch(hint);
                List<TourCourseCandidate> exactMatches = candidates.stream()
                        .filter(candidate -> !usedIds.contains(candidate.id()))
                        .filter(candidate -> {
                            String name = normalizeForMatch(candidate.name());
                            return name.length() >= 2 && normalizedHint.contains(name);
                        })
                        .sorted(java.util.Comparator
                                .comparingInt((TourCourseCandidate candidate) ->
                                        normalizedHint.indexOf(normalizeForMatch(candidate.name())))
                                .thenComparing(candidate -> -normalizeForMatch(candidate.name()).length()))
                        .toList();
                if (!exactMatches.isEmpty()) {
                    for (TourCourseCandidate candidate : exactMatches) {
                        if (result.stream().filter(value -> value.day() == matchedDay).count() >= MAX_STOPS_PER_DAY) break;
                        usedIds.add(candidate.id());
                        result.add(new ScheduledCandidate(matchedDay, candidate.id(), candidate.name()));
                    }
                    continue;
                }
                TourCourseCandidate best = bestCandidateForHint(hint, candidates, usedIds);
                if (best != null) {
                    usedIds.add(best.id());
                    result.add(new ScheduledCandidate(matchedDay, best.id(), best.name()));
                }
            }
        }
        log.info("[OPENAI COURSE] 사용자 일정 장소 매칭 - requestedLines={}, matchedPlaces={}",
                schedule.lines().filter(line -> itemPattern.matcher(line.trim()).matches()).count(),
                result.size());
        return result;
    }

    private List<String> scheduleHints(String schedule) {
        if (schedule.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String rawLine : schedule.split("\\R")) {
            String line = rawLine.trim();
            if (!isScheduleContentLine(line)) continue;
            for (String hint : splitScheduleHints(line)) {
                String cleanHint = hint.trim();
                if (cleanHint.length() >= 2 && !result.contains(cleanHint)) result.add(cleanHint);
                if (result.size() >= 40) return result;
            }
        }
        return result;
    }

    private boolean isScheduleContentLine(String line) {
        if (line == null || line.isBlank()) return false;
        String value = line.trim();
        if (value.startsWith("#") || value.startsWith("[") || value.startsWith("**[")) return false;
        return !value.matches("(?i)^\\*{0,2}\\s*(?:day\\s*\\d+|\\d+\\s*(?:일차|日目|天)).*$");
    }

    private List<String> splitScheduleHints(String line) {
        String value = line
                .replaceFirst("^(?:[-•]|\\d+[.)])\\s+", "")
                .replaceFirst("(?i)^(?:오전|점심|오후|저녁|야간|마무리|morning|lunch|afternoon|evening|night)\\s*[:：]\\s*", "")
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("（[^）]*）", " ");
        String[] parts = value.split("(?i)\\s*(?:&|,|/|→|->|그리고|및|또는|갔다가|가고|다음)\\s*");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String hint = part.replaceAll("(?i)(방문|구경|산책|투어|이동|체험|visit|walk|tour)$", "").trim();
            if (hint.length() >= 2) result.add(hint);
        }
        return result;
    }

    private TourCourseCandidate bestCandidateForHint(
            String hint,
            List<TourCourseCandidate> candidates,
            Set<String> usedIds
    ) {
        String normalizedHint = normalizeForMatch(hint);
        if (normalizedHint.length() < 2) return null;
        TourCourseCandidate best = null;
        double bestScore = 0;
        for (TourCourseCandidate candidate : candidates) {
            if (usedIds.contains(candidate.id())) continue;
            String name = normalizeForMatch(candidate.name());
            if (name.length() < 2) continue;
            double score;
            if (normalizedHint.contains(name)) {
                score = 1.0 + Math.min(name.length(), 20) / 100.0;
            } else if (name.contains(normalizedHint) && normalizedHint.length() >= 3) {
                score = 0.9 + Math.min(normalizedHint.length(), 20) / 100.0;
            } else {
                int distance = editDistance(normalizedHint, name);
                int maxLength = Math.max(normalizedHint.length(), name.length());
                score = maxLength == 0 ? 0 : 1.0 - (double) distance / maxLength;
                boolean oneCharacterTypo = Math.min(normalizedHint.length(), name.length()) >= 3
                        && Math.abs(normalizedHint.length() - name.length()) <= 1
                        && distance <= 1;
                if (!oneCharacterTypo && score < 0.78) continue;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private int editDistance(String first, String second) {
        int[] previous = new int[second.length() + 1];
        for (int j = 0; j <= second.length(); j++) previous[j] = j;
        for (int i = 1; i <= first.length(); i++) {
            int[] current = new int[second.length() + 1];
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                int substitution = previous[j - 1] + (first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            previous = current;
        }
        return previous[second.length()];
    }

    private List<CourseDay> enforceScheduledCandidates(
            List<CourseDay> itinerary,
            List<ScheduledCandidate> scheduledCandidates,
            List<TourCourseCandidate> candidates,
            int requestedDays,
            int stopsPerDay
    ) {
        if (scheduledCandidates.isEmpty()) return itinerary;
        Map<Integer, CourseDay> byDay = new HashMap<>();
        Map<String, CourseStop> generatedStops = new HashMap<>();
        itinerary.forEach(day -> {
            byDay.put(day.day(), day);
            day.stops().forEach(stop -> generatedStops.put(stop.id(), stop));
        });
        Map<String, TourCourseCandidate> candidatesById = new HashMap<>();
        candidates.forEach(candidate -> candidatesById.put(candidate.id(), candidate));
        Set<String> lockedIds = scheduledCandidates.stream()
                .map(ScheduledCandidate::candidateId).collect(java.util.stream.Collectors.toSet());
        Set<String> usedIds = new HashSet<>();
        List<CourseDay> result = new ArrayList<>();

        for (int dayNumber = 1; dayNumber <= requestedDays; dayNumber++) {
            CourseDay generatedDay = byDay.get(dayNumber);
            List<CourseStop> merged = new ArrayList<>();
            for (ScheduledCandidate scheduled : scheduledCandidates) {
                if (scheduled.day() != dayNumber || merged.size() >= stopsPerDay) continue;
                TourCourseCandidate candidate = candidatesById.get(scheduled.candidateId());
                if (candidate == null || !usedIds.add(candidate.id())) continue;
                CourseStop generated = generatedStops.get(candidate.id());
                merged.add(generated != null ? generated : courseStop(candidate));
            }
            if (generatedDay != null) {
                for (CourseStop stop : generatedDay.stops()) {
                    if (merged.size() >= stopsPerDay) break;
                    if (lockedIds.contains(stop.id()) || !usedIds.add(stop.id())) continue;
                    merged.add(stop);
                }
            }
            for (TourCourseCandidate candidate : candidates) {
                if (merged.size() >= stopsPerDay) break;
                if (lockedIds.contains(candidate.id()) || !usedIds.add(candidate.id())) continue;
                merged.add(courseStop(candidate));
            }
            String theme = generatedDay == null ? "" : generatedDay.theme();
            result.add(new CourseDay(dayNumber, theme, merged));
        }
        return result;
    }

    private CourseStop courseStop(TourCourseCandidate place) {
        return new CourseStop(
                place.id(), place.name(), place.address(), place.category(), place.imageUrl(),
                place.latitude(), place.longitude(), "", "", "", ""
        );
    }

    private String normalizeForMatch(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace("n서울타워", "남산서울타워")
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private record ScheduledCandidate(int day, String candidateId, String name) {
    }

    private List<CourseStop> optimizeRoute(List<CourseStop> stops) {
        if (stops.size() < 3) return withSequentialTimes(stops);
        // The AI's first stop usually carries a morning/meal-time intent. Keep it fixed,
        // then reduce unnecessary backtracking among the remaining selected places.
        List<CourseStop> remaining = new ArrayList<>(stops.subList(1, stops.size()));
        List<CourseStop> route = new ArrayList<>();
        route.add(stops.get(0));
        while (!remaining.isEmpty()) {
            CourseStop current = route.get(route.size() - 1);
            CourseStop next = remaining.stream()
                    .min(java.util.Comparator.comparingDouble(value -> distanceKm(current, value)))
                    .orElseThrow();
            route.add(next);
            remaining.remove(next);
        }
        double distance = routeDistanceKm(route);
        log.info("[OPENAI COURSE] 일자 동선 최적화 - stops={}, routeDistanceKm={}",
                stops.size(), String.format(Locale.ROOT, "%.1f", distance));
        return withSequentialTimes(route);
    }

    private List<CourseStop> withSequentialTimes(List<CourseStop> stops) {
        String[][] schedules = {
                {}, {}, {},
                {"10:00", "14:00", "18:00"},
                {"09:30", "12:00", "15:00", "18:00"},
                {"09:00", "11:30", "14:00", "16:30", "19:00"},
                {"09:00", "10:30", "12:00", "14:00", "16:30", "19:00"},
                {"09:00", "10:30", "12:00", "14:00", "16:00", "18:00", "20:00"}
        };
        String[] timeSlots = schedules[Math.min(stops.size(), 7)];
        List<CourseStop> result = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            CourseStop stop = stops.get(i);
            result.add(new CourseStop(
                    stop.id(), stop.name(), stop.address(), stop.category(), stop.imageUrl(),
                    stop.latitude(), stop.longitude(), timeSlots[Math.min(i, timeSlots.length - 1)],
                    stop.reason(), stop.visitTip(), stop.healthNote()
            ));
        }
        return result;
    }

    private double routeDistanceKm(List<CourseStop> stops) {
        double total = 0;
        for (int i = 1; i < stops.size(); i++) total += distanceKm(stops.get(i - 1), stops.get(i));
        return total;
    }

    private double distanceKm(CourseStop first, CourseStop second) {
        double earthRadius = 6371.0;
        double latDistance = Math.toRadians(second.latitude() - first.latitude());
        double lonDistance = Math.toRadians(second.longitude() - first.longitude());
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(first.latitude())) * Math.cos(Math.toRadians(second.latitude()))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private String extractOutputText(JsonNode response) {
        for (JsonNode output : response.path("output")) {
            for (JsonNode content : output.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    String text = content.path("text").asText("");
                    if (!text.isBlank()) return text;
                }
            }
        }
        throw new IllegalStateException("OpenAI course response did not contain output_text");
    }

    private List<String> stringList(JsonNode node, int limit) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank() && !result.contains(value)) result.add(value);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private List<String> cleanList(List<String> values, int limit, int maxLength) {
        if (values == null) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> clean(value, maxLength))
                .distinct().limit(limit).toList();
    }

    private String clean(String value, int maxLength) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private String cleanMultiline(String value, int maxLength) {
        if (value == null) return "";
        String clean = value.replace("\r", "").replace("\t", " ").trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private String normalizeLanguage(String language) {
        if (language == null) return "ko";
        String value = language.toLowerCase(Locale.ROOT);
        if (value.startsWith("en")) return "en";
        if (value.startsWith("ja")) return "ja";
        if (value.startsWith("zh")) return "zh-CN";
        return "ko";
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
