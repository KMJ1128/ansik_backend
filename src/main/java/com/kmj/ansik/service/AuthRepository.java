package com.kmj.ansik.service;

import com.kmj.ansik.dto.AuthUserDto;
import com.kmj.ansik.service.ProviderIdentityService.ProviderIdentity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Repository
public class AuthRepository {

    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public AuthUserDto upsertSocialUser(ProviderIdentity identity, String language) {
        Optional<AuthUserDto> existing = findBySocial(identity.provider(), identity.providerUserId());
        if (existing.isPresent()) {
            long userId = existing.get().id();
            jdbc.update("""
                    UPDATE users
                       SET nickname = ?, profile_image_url = ?, preferred_language = ?, last_login_at = UTC_TIMESTAMP(6)
                     WHERE id = ? AND status = 'ACTIVE'
                    """, identity.nickname(), nullable(identity.profileImageUrl()), normalizeLanguage(language), userId);
            jdbc.update("""
                    UPDATE social_accounts
                       SET email = ?, display_name = ?, profile_image_url = ?, last_login_at = UTC_TIMESTAMP(6)
                     WHERE user_id = ? AND provider = ?
                    """, nullable(identity.email()), identity.nickname(), nullable(identity.profileImageUrl()),
                    userId, identity.provider());
            return findById(userId).orElseThrow();
        }

        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO users
                            (nickname, profile_image_url, preferred_language, status, created_at, updated_at, last_login_at)
                        VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, identity.nickname());
                statement.setString(2, nullable(identity.profileImageUrl()));
                statement.setString(3, normalizeLanguage(language));
                return statement;
            }, keyHolder);
            Number generated = keyHolder.getKey();
            if (generated == null) throw new IllegalStateException("User ID was not generated");
            long userId = generated.longValue();

            jdbc.update("""
                    INSERT INTO social_accounts
                        (user_id, provider, provider_user_id, email, display_name, profile_image_url, created_at, last_login_at)
                    VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, userId, identity.provider(), identity.providerUserId(), nullable(identity.email()),
                    identity.nickname(), nullable(identity.profileImageUrl()));
            jdbc.update("INSERT INTO user_preferences (user_id) VALUES (?)", userId);
            return findById(userId).orElseThrow();
        } catch (DuplicateKeyException race) {
            return findBySocial(identity.provider(), identity.providerUserId()).orElseThrow(() -> race);
        }
    }

    public Optional<AuthUserDto> findBySocial(String provider, String providerUserId) {
        List<AuthUserDto> users = jdbc.query("""
                SELECT u.id, u.nickname, u.profile_image_url, u.preferred_language, s.provider
                  FROM social_accounts s
                  JOIN users u ON u.id = s.user_id
                 WHERE s.provider = ? AND s.provider_user_id = ? AND u.status = 'ACTIVE'
                """, (rs, row) -> mapUser(rs), provider, providerUserId);
        return users.stream().findFirst();
    }

    public Optional<AuthUserDto> findById(long userId) {
        List<AuthUserDto> users = jdbc.query("""
                SELECT u.id, u.nickname, u.profile_image_url, u.preferred_language,
                       COALESCE(MIN(s.provider), '') AS provider
                  FROM users u
             LEFT JOIN social_accounts s ON s.user_id = u.id
                 WHERE u.id = ? AND u.status = 'ACTIVE'
              GROUP BY u.id, u.nickname, u.profile_image_url, u.preferred_language
                """, (rs, row) -> mapUser(rs), userId);
        return users.stream().findFirst();
    }

    public void saveRefreshToken(long userId, String deviceId, AuthTokenService.RefreshToken token) {
        jdbc.update("""
                INSERT INTO refresh_tokens (token_hash, user_id, device_id, expires_at, created_at)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, token.hash(), userId, cleanDeviceId(deviceId), token.expiresAt());
    }

    @Transactional
    public Optional<AuthUserDto> consumeRefreshToken(String tokenHash, String deviceId) {
        List<AuthUserDto> users = jdbc.query("""
                SELECT u.id, u.nickname, u.profile_image_url, u.preferred_language,
                       COALESCE(MIN(s.provider), '') AS provider
                  FROM refresh_tokens r
                  JOIN users u ON u.id = r.user_id
             LEFT JOIN social_accounts s ON s.user_id = u.id
                 WHERE r.token_hash = ? AND r.device_id = ?
                   AND r.revoked_at IS NULL AND r.expires_at > UTC_TIMESTAMP(6)
                   AND u.status = 'ACTIVE'
              GROUP BY u.id, u.nickname, u.profile_image_url, u.preferred_language
                """, (rs, row) -> mapUser(rs), tokenHash, cleanDeviceId(deviceId));
        if (users.isEmpty()) return Optional.empty();
        jdbc.update("UPDATE refresh_tokens SET revoked_at = UTC_TIMESTAMP(6) WHERE token_hash = ?", tokenHash);
        return Optional.of(users.getFirst());
    }

    public void revokeRefreshToken(String tokenHash) {
        jdbc.update("""
                UPDATE refresh_tokens
                   SET revoked_at = COALESCE(revoked_at, UTC_TIMESTAMP(6))
                 WHERE token_hash = ?
                """, tokenHash);
    }

    @Transactional
    public boolean deleteUser(long userId) {
        return jdbc.update("DELETE FROM users WHERE id = ?", userId) > 0;
    }

    private AuthUserDto mapUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuthUserDto(
                rs.getLong("id"),
                rs.getString("nickname"),
                value(rs.getString("profile_image_url")),
                rs.getString("preferred_language"),
                value(rs.getString("provider"))
        );
    }

    private String normalizeLanguage(String value) {
        String language = value == null ? "ko" : value.trim().toLowerCase(Locale.ROOT);
        return Set.of("ko", "en", "ja", "zh-cn").contains(language) ? language : "ko";
    }

    private String cleanDeviceId(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank()) throw new IllegalArgumentException("deviceId is required");
        return cleaned.length() <= 191 ? cleaned : cleaned.substring(0, 191);
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
