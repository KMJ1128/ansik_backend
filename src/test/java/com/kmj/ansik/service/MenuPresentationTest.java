package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmj.ansik.dto.RestaurantMenuGuideDto.MenuItem;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MenuPresentationTest {

    @Test
    void reviewNeedsLocalAddressEvidenceNotJustDistrict() {
        NaverService service = new NaverService();
        int wrong = ReflectionTestUtils.invokeMethod(service, "calculateReviewMatch", "이북만두",
                List.of("중구", "무교로", "무교동"), "이북만두 방문 후기", "중구 식당을 소개합니다");
        int matching = ReflectionTestUtils.invokeMethod(service, "calculateReviewMatch", "이북만두",
                List.of("중구", "무교로", "무교동"), "이북만두 방문 후기", "무교동에서 먹은 만두");
        assertThat(wrong).isZero();
        assertThat(matching).isGreaterThanOrEqualTo(120);
    }

    @Test
    void translationDoesNotChangeTheOriginalName() throws Exception {
        var service = new OpenAiRestaurantMenuService(mock(NaverService.class));
        var json = new ObjectMapper().readTree("""
                [{"name":"소불고기","displayName":"Soy-marinated beef",
                "description":"Thin beef cooked in a soy marinade.","tasteTags":[],
                "typicalIngredients":[],"possibleAllergens":[],"sourceUrls":[],
                "confidence":"medium","healthRiskLevel":"unknown","healthRiskSummary":"",
                "healthRiskReasons":[],"questionsForRestaurant":[]}]
                """);
        List<MenuItem> result = ReflectionTestUtils.invokeMethod(service, "parseMenus", json, false);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("소불고기");
        assertThat(result.get(0).displayName()).isEqualTo("Soy-marinated beef");
    }

    @Test
    void translationIsRequiredByStructuredOutputSchema() {
        JsonNode format = ReflectionTestUtils.invokeMethod(
                new OpenAiRestaurantMenuService(mock(NaverService.class)),
                "structuredOutputFormat"
        );
        JsonNode item = format.path("schema").path("properties").path("menus").path("items");
        assertThat(item.path("properties").has("displayName")).isTrue();
        assertThat(item.path("required").toString()).contains("displayName");
    }
}
