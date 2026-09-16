package com.kmj.ansik.web;

import com.kmj.ansik.service.AuthException;
import com.kmj.ansik.service.AuthTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AuthTokenFilter extends OncePerRequestFilter {

    private final AuthTokenService tokenService;

    public AuthTokenFilter(AuthTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                long userId = tokenService.verifyAccessToken(authorization.substring(7).trim());
                request.setAttribute("authUserId", userId);
            } catch (AuthException invalidToken) {
                // Public map/image endpoints must keep working even when an old app
                // session still sends an expired token. Only /auth/me requires a
                // valid access token at filter level.
                if (request.getRequestURI().endsWith("/api/auth/me")) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"status\":\"UNAUTHORIZED\",\"message\":\"로그인이 만료되었습니다.\"}");
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
