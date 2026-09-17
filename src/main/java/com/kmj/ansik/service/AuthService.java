package com.kmj.ansik.service;

import com.kmj.ansik.dto.AuthResponse;
import com.kmj.ansik.dto.AuthUserDto;
import com.kmj.ansik.dto.SocialLoginRequest;
import com.kmj.ansik.dto.TokenRefreshRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final ProviderIdentityService providerIdentityService;
    private final AuthRepository repository;
    private final AuthTokenService tokenService;

    public AuthService(
            ProviderIdentityService providerIdentityService,
            AuthRepository repository,
            AuthTokenService tokenService
    ) {
        this.providerIdentityService = providerIdentityService;
        this.repository = repository;
        this.tokenService = tokenService;
    }

    @Transactional
    public AuthResponse socialLogin(SocialLoginRequest request) {
        ProviderIdentityService.ProviderIdentity identity = providerIdentityService.verify(
                request.getProvider(), request.getProviderToken()
        );
        AuthUserDto user = repository.upsertSocialUser(identity, request.getLanguage());
        AuthResponse response = issueSession(user, request.getDeviceId());
        log.info("[AUTH] 소셜 로그인 성공 - userId={}, provider={}, consentVersion={}",
                user.id(), identity.provider(), request.getConsentVersion());
        return response;
    }

    @Transactional
    public AuthResponse refresh(TokenRefreshRequest request) {
        String oldHash = tokenService.hash(request.refreshToken());
        AuthUserDto user = repository.consumeRefreshToken(oldHash, request.deviceId())
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다."));
        AuthResponse response = issueSession(user, request.deviceId());
        log.info("[AUTH] 로그인 갱신 성공 - userId={}", user.id());
        return response;
    }

    public AuthUserDto currentUser(long userId) {
        return repository.findById(userId)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
    }

    public void logout(String refreshToken) {
        repository.revokeRefreshToken(tokenService.hash(refreshToken));
    }

    @Transactional
    public void deleteAccount(long userId) {
        if (!repository.deleteUser(userId)) {
            throw new AuthException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        log.info("[AUTH] 계정 삭제 완료 - userId={}", userId);
    }

    private AuthResponse issueSession(AuthUserDto user, String deviceId) {
        AuthTokenService.RefreshToken refresh = tokenService.createRefreshToken();
        repository.saveRefreshToken(user.id(), deviceId, refresh);
        return new AuthResponse(
                tokenService.createAccessToken(user.id()),
                refresh.raw(),
                tokenService.accessTokenSeconds(),
                user
        );
    }
}
