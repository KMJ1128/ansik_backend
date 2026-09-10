package com.kmj.ansik.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] jwtSecret;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public AuthTokenService(
            @Value("${auth.jwt.secret-base64:}") String secretBase64,
            @Value("${auth.jwt.access-token-minutes:30}") long accessMinutes,
            @Value("${auth.jwt.refresh-token-days:30}") long refreshDays
    ) {
        try {
            this.jwtSecret = Base64.getDecoder().decode(secretBase64);
        } catch (Exception e) {
            throw new IllegalStateException("AUTH JWT secret must be Base64 encoded", e);
        }
        if (jwtSecret.length < 32) {
            throw new IllegalStateException("AUTH JWT secret must contain at least 32 bytes");
        }
        this.accessTtl = Duration.ofMinutes(Math.max(5, accessMinutes));
        this.refreshTtl = Duration.ofDays(Math.max(1, refreshDays));
    }

    public String createAccessToken(long userId) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("ansik-api")
                    .subject(Long.toString(userId))
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(accessTtl)))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("type", "access")
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(jwtSecret));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Access token creation failed", e);
        }
    }

    public long verifyAccessToken(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            boolean valid = jwt.verify(new MACVerifier(jwtSecret))
                    && "ansik-api".equals(claims.getIssuer())
                    && "access".equals(claims.getStringClaim("type"))
                    && claims.getExpirationTime() != null
                    && claims.getExpirationTime().toInstant().isAfter(Instant.now());
            if (!valid) throw new IllegalArgumentException("invalid token");
            return Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다.");
        }
    }

    public RefreshToken createRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new RefreshToken(raw, hash(raw), Instant.now().plus(refreshTtl));
    }

    public String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            throw new IllegalStateException("Token hash failed", e);
        }
    }

    public long accessTokenSeconds() {
        return accessTtl.toSeconds();
    }

    public record RefreshToken(String raw, String hash, Instant expiresAt) {
    }
}
