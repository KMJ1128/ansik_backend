package com.kmj.ansik.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTokenServiceTest {

    private final AuthTokenService service = new AuthTokenService(
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()),
            30,
            30
    );

    @Test
    void signedAccessTokenRestoresUserId() {
        String token = service.createAccessToken(42L);
        assertThat(service.verifyAccessToken(token)).isEqualTo(42L);
    }

    @Test
    void refreshTokenStoresOnlyAStableHash() {
        AuthTokenService.RefreshToken token = service.createRefreshToken();
        assertThat(token.raw()).isNotBlank();
        assertThat(token.hash()).hasSize(64).isEqualTo(service.hash(token.raw()));
        assertThat(token.hash()).doesNotContain(token.raw());
    }
}
