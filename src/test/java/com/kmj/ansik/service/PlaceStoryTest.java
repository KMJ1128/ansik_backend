package com.kmj.ansik.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PlaceStoryTest {
    @Test
    void bilingualPinUsesOfficialStoryAndNotSimilarlyNamedRestaurant() {
        TourService service = new TourService();
        ReflectionTestUtils.setField(service, "tourApiKey", "test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate")).build();
        server.expect(anything()).andRespond(withSuccess("""
            {"response":{"body":{"items":{"item":[
            {"title":"경복궁","contenttypeid":"39","contentid":"restaurant"},
            {"title":"경복궁","contenttypeid":"12","contentid":"126508","firstimage":"https://example.com/palace.jpg"}
            ]}}}}
            """, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("""
            {"response":{"body":{"items":{"item":[{"overview":"공식 역사 설명<br>다음 문단"}]}}}}
            """, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("""
            {"response":{"body":{"items":{"item":[]}}}}
            """, MediaType.APPLICATION_JSON));
        var story = service.getPlaceStory("Gyeongbokgung (경복궁)", 37.5796, 126.977, "ko");
        assertThat(story.overview()).isEqualTo("공식 역사 설명<br>다음 문단");
        assertThat(story.images()).containsExactly("https://example.com/palace.jpg");
        server.verify();
    }

    @Test
    void unrelatedPlaceDoesNotProduceStory() {
        TourService service = new TourService();
        ReflectionTestUtils.setField(service, "tourApiKey", "test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate")).build();
        server.expect(anything()).andRespond(withSuccess("""
            {"response":{"body":{"items":{"item":[{"title":"경복궁 박물관","contenttypeid":"14"}]}}}}
            """, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("""
            {"response":{"body":{"items":{"item":[]}}}}
            """, MediaType.APPLICATION_JSON));
        assertThat(service.getPlaceStory("경복궁", 37.5796, 126.977, "ko").overview()).isEmpty();
        server.verify();
    }
}
