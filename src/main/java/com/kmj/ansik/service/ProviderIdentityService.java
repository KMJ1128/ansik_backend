package com.kmj.ansik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URL;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

@Service
public class ProviderIdentityService {

    private static final Set<String> GOOGLE_ISSUERS = Set.of(
            "https://accounts.google.com", "accounts.google.com"
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final ConfigurableJWTProcessor<SecurityContext> googleJwtProcessor;
    private final String googleClientId;

    public ProviderIdentityService(
            @Value("${auth.google.web-client-id:}") String googleClientId
    ) throws Exception {
        this.googleClientId = googleClientId == null ? "" : googleClientId.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(4_000);
        factory.setReadTimeout(6_000);
        this.restTemplate = new RestTemplate(factory);

        JWKSource<SecurityContext> googleKeys = new RemoteJWKSet<>(
                new URL("https://www.googleapis.com/oauth2/v3/certs")
        );
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, googleKeys));
        this.googleJwtProcessor = processor;
    }

    public ProviderIdentity verify(String providerText, String providerToken) {
        String provider = providerText == null ? "" : providerText.trim().toUpperCase(Locale.ROOT);
        String token = providerToken == null ? "" : providerToken.trim();
        if (token.isBlank()) throw unauthorized("소셜 로그인 토큰이 없습니다.");

        return switch (provider) {
            case "KAKAO" -> verifyKakao(token);
            case "NAVER" -> verifyNaver(token);
            case "GOOGLE" -> verifyGoogle(token);
            default -> throw new AuthException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "지원하지 않는 로그인 제공자입니다.");
        };
    }

    private ProviderIdentity verifyKakao(String token) {
        try {
            JsonNode root = getJson("https://kapi.kakao.com/v2/user/me", token);
            String id = root.path("id").asText("");
            JsonNode account = root.path("kakao_account");
            JsonNode profile = account.path("profile");
            return requiredIdentity(
                    "KAKAO", id,
                    account.path("email").asText(""),
                    profile.path("nickname").asText(""),
                    profile.path("profile_image_url").asText("")
            );
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw unauthorized("카카오 로그인 정보를 확인하지 못했습니다.");
        }
    }

    private ProviderIdentity verifyNaver(String token) {
        try {
            JsonNode response = getJson("https://openapi.naver.com/v1/nid/me", token).path("response");
            return requiredIdentity(
                    "NAVER",
                    response.path("id").asText(""),
                    response.path("email").asText(""),
                    response.path("nickname").asText(response.path("name").asText("")),
                    response.path("profile_image").asText("")
            );
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw unauthorized("네이버 로그인 정보를 확인하지 못했습니다.");
        }
    }

    private ProviderIdentity verifyGoogle(String idToken) {
        if (googleClientId.isBlank()) {
            throw new AuthException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Google 로그인 설정이 완료되지 않았습니다.");
        }
        try {
            JWTClaimsSet claims = googleJwtProcessor.process(idToken, null);
            Instant now = Instant.now();
            if (!GOOGLE_ISSUERS.contains(claims.getIssuer())
                    || !claims.getAudience().contains(googleClientId)
                    || claims.getExpirationTime() == null
                    || !claims.getExpirationTime().toInstant().isAfter(now)) {
                throw unauthorized("유효하지 않은 Google 로그인입니다.");
            }
            return requiredIdentity(
                    "GOOGLE",
                    claims.getSubject(),
                    stringClaim(claims, "email"),
                    stringClaim(claims, "name"),
                    stringClaim(claims, "picture")
            );
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw unauthorized("Google 로그인 정보를 확인하지 못했습니다.");
        }
    }

    private JsonNode getJson(String url, String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class
        );
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw unauthorized("소셜 로그인 검증에 실패했습니다.");
        }
        return mapper.readTree(response.getBody());
    }

    private ProviderIdentity requiredIdentity(
            String provider, String id, String email, String nickname, String profileImageUrl
    ) {
        if (id == null || id.isBlank()) throw unauthorized("소셜 사용자 번호를 확인하지 못했습니다.");
        String safeName = nickname == null || nickname.isBlank()
                ? provider.substring(0, 1) + provider.substring(1).toLowerCase(Locale.ROOT) + " user"
                : nickname.trim();
        return new ProviderIdentity(
                provider, id.trim(), clean(email, 320), clean(safeName, 80), clean(profileImageUrl, 2048)
        );
    }

    private String stringClaim(JWTClaimsSet claims, String name) {
        try {
            String value = claims.getStringClaim(name);
            return value == null ? "" : value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String clean(String value, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private AuthException unauthorized(String message) {
        return new AuthException(org.springframework.http.HttpStatus.UNAUTHORIZED, message);
    }

    public record ProviderIdentity(
            String provider,
            String providerUserId,
            String email,
            String nickname,
            String profileImageUrl
    ) {
    }
}
