package com.mybaselink.app.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mybaselink.app.jwt.JwtUtil;
import com.mybaselink.app.service.CustomUserDetailsService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 🔐 AuthController - 인증 / 세션 연장 / 검증 처리
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtUtil jwtUtil,
                          CustomUserDetailsService userDetailsService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    // ============================================================
    // 🟢 로그인
    // ============================================================
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password)
            );

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String token = jwtUtil.generateToken(userDetails);

            long now = System.currentTimeMillis();
            long sessionMillis = now + jwtUtil.getExpiration();

            Map<String, Object> result = new HashMap<>();
            result.put("token", token);
            result.put("username", username);
            result.put("sessionMillis", sessionMillis);
            result.put("serverTime", now); // ✅ 클라이언트 시간 동기화용 추가
            result.put("message", "로그인 성공");

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "아이디 또는 비밀번호가 올바르지 않습니다.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
    }

    // ============================================================
    // 🔁 세션 연장 (Refresh Token)
    // ============================================================
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshSession(HttpServletRequest request) {
        String token = jwtUtil.resolveToken(request);
        Map<String, Object> response = new HashMap<>();

        if (token == null || !jwtUtil.validateToken(token)) {
            response.put("error", "세션이 만료되었거나 유효하지 않습니다.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }

        String username = jwtUtil.getUsername(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        String newToken = jwtUtil.generateToken(userDetails);

        long now = System.currentTimeMillis();
        long sessionMillis = now + jwtUtil.getExpiration();

        response.put("token", newToken);
        response.put("username", username);
        response.put("sessionMillis", sessionMillis);
        response.put("serverTime", now); // ✅ 세션 연장 시에도 동기화
        response.put("message", "세션이 연장되었습니다.");

        return ResponseEntity.ok(response);
    }

    // ============================================================
    // 🟣 토큰 유효성 검증
    // ============================================================
    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateToken(HttpServletRequest request) {
        String token = jwtUtil.resolveToken(request);
        Map<String, Object> result = new HashMap<>();

        if (token != null && jwtUtil.validateToken(token)) {
            result.put("valid", true);
            result.put("username", jwtUtil.getUsername(token));
            return ResponseEntity.ok(result);
        } else {
            result.put("valid", false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }
    }
}