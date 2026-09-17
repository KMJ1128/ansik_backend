package com.kmj.ansik.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

public class SocialLoginRequest {

    @NotBlank
    private String provider;

    @NotBlank
    private String providerToken;

    @NotBlank
    private String deviceId;

    private String language;

    @AssertTrue(message = "서비스 이용약관 동의가 필요합니다.")
    private boolean termsAccepted;

    @AssertTrue(message = "개인정보 수집·이용 동의가 필요합니다.")
    private boolean privacyCollectionAccepted;

    @NotBlank
    private String consentVersion;

    public SocialLoginRequest() {
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderToken() {
        return providerToken;
    }

    public void setProviderToken(String providerToken) {
        this.providerToken = providerToken;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public boolean isTermsAccepted() {
        return termsAccepted;
    }

    public void setTermsAccepted(boolean termsAccepted) {
        this.termsAccepted = termsAccepted;
    }

    public boolean isPrivacyCollectionAccepted() {
        return privacyCollectionAccepted;
    }

    public void setPrivacyCollectionAccepted(boolean privacyCollectionAccepted) {
        this.privacyCollectionAccepted = privacyCollectionAccepted;
    }

    public String getConsentVersion() {
        return consentVersion;
    }

    public void setConsentVersion(String consentVersion) {
        this.consentVersion = consentVersion;
    }
}
