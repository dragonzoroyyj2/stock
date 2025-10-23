package com.mybaselink.app.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.mybaselink.app.jwt.JwtAuthenticationFilter;
import com.mybaselink.app.service.CustomUserDetailsService;

/**
 * 🔐 SecurityConfig - MyNova 통합 인증 설정
 *
 * ✅ 주요 기능
 * 1. JWT 기반 인증 (세션 병행 가능)
 * 2. /auth/** → 인증 관련 API (로그인, 세션연장, 토큰 검증)
 * 3. /api/**  → JWT 인증 필수 (데이터 처리용)
 * 4. 정적 리소스 및 /login, /error 는 모두 접근 허용
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomUserDetailsService userDetailsService;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          CustomUserDetailsService userDetailsService) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.userDetailsService = userDetailsService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        // 정적 리소스 허용 경로
        String[] staticResources = {
                "/common/**", "/css/**", "/js/**", "/images/**","/favicon/**", "/test_report/**", "/favicon.ico", "/apple-icon-*.png", "/android-icon-*.png", "/manifest.json"
        };

        // 로그인 없이 접근 가능한 페이지 및 API
        String[] publicEndpoints = {
                "/", "/login", "/error",
                "/auth/login", "/auth/refresh", "/auth/validate"
        };

        http
            .csrf(csrf -> csrf.disable())

            .authorizeHttpRequests(auth -> auth
                // 정적 리소스 & 공개 엔드포인트 모두 허용
                .requestMatchers(staticResources).permitAll()
                .requestMatchers(publicEndpoints).permitAll()

                // 인증 없이 열고 싶은 API
                .requestMatchers("/api/krx/**").permitAll()
                .requestMatchers("/chart/**").permitAll() // 신규 화면
                
                // 인증 필요한 영역
                .requestMatchers("/auth/**", "/api/**").authenticated()
                .requestMatchers("/pages/main/base**").permitAll()
                .requestMatchers("/pages/**").permitAll()
                
               

                // 나머지는 차단
                .anyRequest().denyAll()
            )

            // 인증 실패 시 동작
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint((request, response, authException) -> {
                    String uri = request.getRequestURI();
                    if (uri.startsWith("/api") || uri.startsWith("/auth")) {
                        response.setStatus(401);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"error\":\"인증이 필요합니다.\"}");
                    } else {
                        response.sendRedirect("/login");
                    }
                })
                .accessDeniedPage("/error")
            )

            // 폼 로그인 (HTML 로그인 페이지)
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/pages/main/base", true)
                .permitAll()
            )

            // 로그아웃 설정
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )

            // 세션 정책
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )

            // UserDetailsService 연결
            .userDetailsService(userDetailsService);

        // JWT 필터 등록
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // 인증 매니저
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // 비밀번호 암호화
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}