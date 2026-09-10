package com.kmj.ansik.dto;

public record AuthUserDto(
        long id,
        String nickname,
        String profileImageUrl,
        String preferredLanguage,
        String provider
) {
}
