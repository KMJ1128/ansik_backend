package com.kmj.ansik.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        AuthUserDto user
) {
}
