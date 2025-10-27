// src/main/java/com/mybaselink/app/security/jwt/JwtAuthenticationFilter.java
package com.mybaselink.app.security.jwt;

import com.mybaselink.app.service.AuthService;
import com.mybaselink.app.service.CustomUserDetailsService;
import com.mybaselink.app.util.HttpServletRequestUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatchers;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final AuthService authService;

    private static final String[] PERMIT_ALL_URLS = {
            "/login", "/auth/**", "/error", "/",
            "/common/**", "/css/**", "/js/**", "/images/**", "/favicon/**",
            "/favicon.ico", "/apple-icon-*.png", "/android-icon-*.png", "/manifest.json",
            "/api/krx/**", "/chart/**", "/pages/**",
            "/api/profile"
    };

    private final RequestMatcher skipMatcher;

    @SuppressWarnings("deprecation")
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   CustomUserDetailsService userDetailsService,
                                   AuthService authService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
        this.authService = authService;

        RequestMatcher[] permitAllMatchers = Arrays.stream(PERMIT_ALL_URLS)
                .map(path -> new AntPathRequestMatcher(path, null))
                .toArray(RequestMatcher[]::new);

        this.skipMatcher = RequestMatchers.anyOf(permitAllMatchers);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = HttpServletRequestUtils.resolveTokenFromCookie(request, "jwt");
        String username = null;

        if (token != null) {
            if (jwtTokenProvider.validateToken(token) && authService.isTokenValid(token)) {
                username = jwtTokenProvider.getUsername(token);
            }
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return skipMatcher.matches(request);
    }
}
