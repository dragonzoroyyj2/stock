package com.mybaselink.app.controller;

import com.mybaselink.app.service.AuthService;
import com.mybaselink.app.security.jwt.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthService authService;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtTokenProvider jwtTokenProvider,
                          AuthService authService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authService = authService;
    }

    // 로그인 요청 DTO (내부 클래스)
    private static class LoginRequest {
        public String username;
        public String password;
    }

    /**
     * ✅ 로그인 처리
     * - 인증 성공 시 JWT를 HttpOnly 쿠키에 담아 반환
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest, HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.username, loginRequest.password));

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();

            // JWT 토큰 생성
            String jwt = jwtTokenProvider.generateAccessToken(userDetails.getUsername(), userDetails.getAuthorities());
            Instant expiresAt = Instant.now().plusMillis(jwtTokenProvider.accessExpirationMillis);

            // DB에 토큰 저장
            authService.login(userDetails, jwt, expiresAt);

            // HttpOnly 쿠키에 JWT 추가
            setJwtCookie(response, "jwt", jwt, (int) jwtTokenProvider.accessExpirationMillis / 1000);

            return ResponseEntity.ok(Map.of("message", "로그인 성공"));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "로그인 실패: 잘못된 사용자명 또는 비밀번호"));
        }
    }

    /**
     * ✅ 토큰 갱신
     * - HttpOnly 쿠키에서 기존 토큰을 읽어 유효하면 새 토큰 발급
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(HttpServletRequest request, HttpServletResponse response) {
        String oldToken = resolveTokenFromCookie(request, "jwt");

        if (oldToken == null || !authService.isTokenValid(oldToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "유효하지 않거나 만료된 토큰입니다."));
        }

        try {
            String username = jwtTokenProvider.getUsername(oldToken);
            UserDetails userDetails = (UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

            // 새 토큰 발급
            String newToken = jwtTokenProvider.generateAccessToken(username, userDetails.getAuthorities());
            Instant newExpiresAt = Instant.now().plusMillis(jwtTokenProvider.accessExpirationMillis);

            // DB에 토큰 갱신 (기존 토큰 폐기)
            authService.refreshToken(oldToken, newToken, newExpiresAt);

            // HttpOnly 쿠키에 새 JWT 추가
            setJwtCookie(response, "jwt", newToken, (int) jwtTokenProvider.accessExpirationMillis / 1000);

            // 클라이언트에게 새 만료 시간 반환 (선택사항)
            Map<String, Object> responseBody = Map.of(
                "message", "토큰 갱신 성공",
                "newSessionDurationMillis", jwtTokenProvider.accessExpirationMillis
            );
            return ResponseEntity.ok(responseBody);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "토큰 갱신 실패"));
        }
    }

    /**
     * ✅ 로그아웃 처리
     * - SecurityConfig의 CustomLogoutHandler가 쿠키를 무효화하고 DB 토큰을 폐기함
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("message", "로그아웃 처리됨"));
    }

    /**
     * ✅ JWT 쿠키 생성 및 추가
     */
    private void setJwtCookie(HttpServletResponse response, String name, String value, int maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(false) // 개발 환경에서는 false, HTTPS 환경에서는 true
                .path("/")
                .maxAge(Duration.ofSeconds(maxAge))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * ✅ 쿠키에서 토큰 추출
     */
    private String resolveTokenFromCookie(HttpServletRequest request, String name) {
        if (request.getCookies() != null) {
            Optional<Cookie> cookie = Arrays.stream(request.getCookies())
                    .filter(c -> c.getName().equals(name))
                    .findFirst();
            return cookie.map(Cookie::getValue).orElse(null);
        }
        return null;
    }
}
