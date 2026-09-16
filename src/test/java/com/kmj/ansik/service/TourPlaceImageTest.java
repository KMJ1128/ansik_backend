package com.kmj.ansik.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TourPlaceImageTest {
    @Test
    void matchesOfficialAttractionInsteadOfSimilarlyNamedBusiness() {
        TourService service = new TourService();
        ReflectionTestUtils.setField(service, "tourApiKey", "test");
        RestTemplate client = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(anything()).andRespond(withSuccess("""
                {"response":{"body":{"items":{"item":[
                {"title":"경포대 카페","firstimage":"https://example.com/cafe.jpg"},
                {"title":"경포대","firstimage":"https://example.com/attraction.jpg"}
                ]}}}}
                """, MediaType.APPLICATION_JSON));
        assertThat(service.findOfficialPlaceImages("경포대", 37.8, 128.9))
                .containsExactly("https://example.com/attraction.jpg");
        server.verify();
    }

    @Test
    void leavesImageEmptyWhenOnlyDifferentPlaceMatches() {
        TourService service = new TourService();
        ReflectionTestUtils.setField(service, "tourApiKey", "test");
        RestTemplate client = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(anything()).andRespond(withSuccess("""
                {"response":{"body":{"items":{"item":[
                {"title":"경포대 카페","firstimage":"https://example.com/cafe.jpg"}
                ]}}}}
                """, MediaType.APPLICATION_JSON));
        assertThat(service.findOfficialPlaceImages("경포대", 37.8, 128.9)).isEmpty();
        server.verify();
    }
}
