package com.kmj.ansik.service;

import com.kmj.ansik.dto.RestaurantMenuGuideDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRestaurantMenuServiceTest {

    @Test
    void fallsBackSafelyWhenApiKeyIsNotConfigured() {
        OpenAiRestaurantMenuService service =
                new OpenAiRestaurantMenuService(new NaverService());

        RestaurantMenuGuideDto guide = service.getMenuGuide(
                "테스트 식당",
                "서울특별시 중구 테스트로 1",
                "ko"
        );

        assertThat(guide.status()).isEqualTo("OPENAI_NOT_CONFIGURED");
        assertThat(guide.menus()).isEmpty();
    }
}
