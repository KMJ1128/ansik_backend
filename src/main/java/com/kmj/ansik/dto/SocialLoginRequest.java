package com.kmj.ansik.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

public record SocialLoginRequest(
        @NotBlank String provider,
        @NotBlank String providerToken,
        @NotBlank String deviceId,
        String language,
        @AssertTrue(message = "서비스 이용약관 동의가 필요합니다.") boolean termsAccepted,
        @AssertTrue(message = "개인정보 수집·이용 동의가 필요합니다.") boolean privacyCollectionAccepted,
        @NotBlank String consentVersion
) {
}
