package com.kmj.ansik.controller;

import com.kmj.ansik.dto.AuthResponse;
import com.kmj.ansik.dto.AuthUserDto;
import com.kmj.ansik.dto.LogoutRequest;
import com.kmj.ansik.dto.SocialLoginRequest;
import com.kmj.ansik.dto.TokenRefreshRequest;
import com.kmj.ansik.service.AuthException;
import com.kmj.ansik.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/social")
    public AuthResponse socialLogin(@Valid @RequestBody SocialLoginRequest request) {
        return authService.socialLogin(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthUserDto me(@RequestAttribute(name = "authUserId", required = false) Long userId) {
        if (userId == null) throw new AuthException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        return authService.currentUser(userId);
    }
}
