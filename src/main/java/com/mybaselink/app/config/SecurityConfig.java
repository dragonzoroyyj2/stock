// src/main/java/com/mybaselink/app/config/SecurityConfig.java
package com.mybaselink.app.config;

import com.mybaselink.app.jwt.CustomLogoutHandler;
import com.mybaselink.app.security.jwt.JwtAuthenticationFilter;
import com.mybaselink.app.security.jwt.JwtTokenProvider;
import com.mybaselink.app.service.AuthService;
import com.mybaselink.app.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableAsync
@EnableWebSecurity
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final CustomLogoutHandler customLogoutHandler;
    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                          CustomLogoutHandler customLogoutHandler,
                          AuthService authService,
                          JwtTokenProvider jwtTokenProvider) {
        this.userDetailsService = userDetailsService;
        this.customLogoutHandler = customLogoutHandler;
        this.authService = authService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        String[] staticResources = {
            "/common/**", "/css/**", "/js/**", "/images/**", "/favicon/**",
            "/favicon.ico", "/apple-icon-*.png", "/android-icon-*.png", "/manifest.json"
        };
        
        JwtAuthenticationFilter jwtAuthenticationFilter = 
            new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService, authService);

        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(staticResources).permitAll()
                .requestMatchers("/", "/login", "/error", "/auth/**").permitAll()
                .requestMatchers("/api/stock/**", "/api/p01a05/**").permitAll()
                .requestMatchers("/api/krx/**", "/chart/**", "/pages/**", "/api/profile").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, excep) -> {
                    res.setStatus(401);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"error\":\"인증이 필요합니다.\"}");
                })
            )
            .logout(logout -> logout
                .logoutUrl("/auth/logout")
                .addLogoutHandler(customLogoutHandler)
                .logoutSuccessHandler((req, res, auth) -> {
                    res.setStatus(200);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"status\":\"로그아웃 성공\"}");
                })
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .userDetailsService(userDetailsService);

        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
