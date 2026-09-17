package com.kmj.ansik.dto;

import jakarta.validation.constraints.NotBlank;

public record SocialLoginRequest(
        @NotBlank String provider,
        @NotBlank String providerToken,
        @NotBlank String deviceId,
        String language
) {
}
