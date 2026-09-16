package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmj.ansik.dto.RestaurantMenuGuideDto.MenuItem;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MenuPresentationTest {
    @Test void rejectsVideoThumbnailsEvenWhenTheDishNameMatches() {
        int result = ReflectionTestUtils.invokeMethod(new NaverService(), "calculateImageRelevance",
                "불고기", "불고기 요리 사진", "https://i.ytimg.com/vi/example/maxresdefault.jpg", 1280, 720);
        assertThat(result).isZero();
        assertThat(score("불고기", "불고기 요리 사진", 0, 0)).isZero();
    }
    @Test void reviewNeedsLocalAddressEvidenceNotJustDistrict() {
        NaverService service = new NaverService();
        int wrong = ReflectionTestUtils.invokeMethod(service, "calculateReviewMatch", "이북만두",
                List.of("중구", "무교로", "무교동"), "이북만두 방문 후기", "중구 식당을 소개합니다");
        int matching = ReflectionTestUtils.invokeMethod(service, "calculateReviewMatch", "이북만두",
                List.of("중구", "무교로", "무교동"), "이북만두 방문 후기", "무교동에서 먹은 만두");
        assertThat(wrong).isZero();
        assertThat(matching).isGreaterThanOrEqualTo(120);
    }
    @Test void translationDoesNotChangeTheOriginalNameOrImageQuery() throws Exception {
        NaverService images = mock(NaverService.class);
        when(images.getMenuImages("소불고기")).thenReturn(List.of("https://example.com/beef.jpg"));
        var service = new OpenAiRestaurantMenuService(images);
        var json = new ObjectMapper().readTree("""
                [{"name":"소불고기","displayName":"Soy-marinated beef",
                "description":"Thin beef cooked in a soy marinade.","imageSearchQuery":"소불고기"}]
                """);
        List<MenuItem> result = ReflectionTestUtils.invokeMethod(service,"parseMenus",json,false);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("소불고기");
        assertThat(result.get(0).displayName()).isEqualTo("Soy-marinated beef");
        verify(images).getMenuImages("소불고기");
    }
    @Test void translationIsRequiredByStructuredOutputSchema() {
        JsonNode format = ReflectionTestUtils.invokeMethod(new OpenAiRestaurantMenuService(mock(NaverService.class)),"structuredOutputFormat");
        JsonNode item = format.path("schema").path("properties").path("menus").path("items");
        assertThat(item.path("properties").has("displayName")).isTrue();
        assertThat(item.path("required").toString()).contains("displayName");
    }
    private int score(String query, String title, int width, int height) {
        return ReflectionTestUtils.invokeMethod(new NaverService(),"calculateImageRelevance",
                query,title,"https://example.com/photo.jpg",width,height);
    }
    @Test void rejectsWrongDishAndGameImages() {
        assertThat(score("떡볶이","떡볶이 게임 공략",1200,800)).isZero();
        assertThat(score("소불고기","돼지불고기 요리",1200,800)).isZero();
        assertThat(score("떡볶이","떡볶이맛 과자",1200,800)).isZero();
    }
    @Test void allowsCorrectModeratelySizedFoodPhotoButNotTinyImage() {
        assertThat(score("아메리카노","아메리카노 커피 사진",480,320)).isGreaterThanOrEqualTo(130);
        assertThat(score("아메리카노","아메리카노 커피 사진",120,120)).isZero();
    }
}
