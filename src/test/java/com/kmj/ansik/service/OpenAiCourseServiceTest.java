package com.kmj.ansik.service;

import com.kmj.ansik.dto.AiCourseDto;
import com.kmj.ansik.dto.AiCourseRequest;
import com.kmj.ansik.dto.TourCourseCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCourseServiceTest {

    @Test
    void failsSafelyWhenOpenAiIsNotConfigured() {
        OpenAiCourseService service = new OpenAiCourseService(new TourService());

        AiCourseDto result = service.createCourse(new AiCourseRequest(
                "SEOUL", "서울", 2, 3, 4, "",
                List.of("역사"), List.of("고혈압"), "ko"
        ));

        assertThat(result.status()).isEqualTo("OPENAI_NOT_CONFIGURED");
        assertThat(result.itinerary()).isEmpty();
    }

    @Test
    void understandsRoughScheduleKeywordsAndOneCharacterTypos() throws Exception {
        OpenAiCourseService service = new OpenAiCourseService(new TourService());
        var method = OpenAiCourseService.class.getDeclaredMethod(
                "matchScheduledCandidates", String.class, List.class, int.class
        );
        method.setAccessible(true);
        List<TourCourseCandidate> candidates = List.of(
                new TourCourseCandidate("1", "경복궁", "서울 종로구", "종로구", "ATTRACTION", "", 37.57, 126.97),
                new TourCourseCandidate("2", "광장시장", "서울 종로구", "종로구", "SHOPPING", "", 37.57, 127.00)
        );

        List<?> matches = (List<?>) method.invoke(
                service, "1일차 경복공 갔다가 광장시장", candidates, 3
        );

        assertThat(matches).hasSize(2);
        assertThat(matches.toString()).contains("경복궁", "광장시장", "day=1");
    }

    @Test
    void excludesUnrankedNearbyFallbackWhenTourismEvidenceIsSufficient() throws Exception {
        OpenAiCourseService service = new OpenAiCourseService(new TourService());
        var method = OpenAiCourseService.class.getDeclaredMethod(
                "qualityCandidatePool", List.class, List.class, int.class, boolean.class
        );
        method.setAccessible(true);
        List<TourCourseCandidate> candidates = new java.util.ArrayList<>(
                IntStream.rangeClosed(1, 15)
                        .mapToObj(index -> new TourCourseCandidate(
                                "hub-" + index, "중심 관광지 " + index, "강릉시", "강릉시",
                                "ATTRACTION", "", 37.7, 128.8,
                                index, "KTO_HUB"
                        ))
                        .toList()
        );
        candidates.add(new TourCourseCandidate(
                "nearby", "리고엠", "강릉시", "강릉시",
                "ATTRACTION", "", 37.7, 128.8,
                5000, "TOURAPI_NEARBY"
        ));

        @SuppressWarnings("unchecked")
        List<TourCourseCandidate> result = (List<TourCourseCandidate>) method.invoke(
                service, candidates, List.of(), 15, false
        );

        assertThat(result).hasSize(15);
        assertThat(result).noneMatch(candidate -> candidate.id().equals("nearby"));
    }
}
