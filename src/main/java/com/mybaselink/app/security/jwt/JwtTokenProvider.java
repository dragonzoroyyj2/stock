// src/main/java/com/mybaselink/app/security/jwt/JwtTokenProvider.java
package com.mybaselink.app.security.jwt;

import com.mybaselink.app.entity.JwtTokenEntity;
import com.mybaselink.app.repository.JwtTokenRepository;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class JwtTokenProvider {

    private final JwtTokenRepository jwtTokenRepository;

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.expiration}")
    public long accessExpirationMillis;

    public JwtTokenProvider(JwtTokenRepository jwtTokenRepository) {
        this.jwtTokenRepository = jwtTokenRepository;
    }

    public String generateAccessToken(String username, Collection<? extends GrantedAuthority> roles) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(accessExpirationMillis);

        List<String> roleList = roles.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        return Jwts.builder()
                .setSubject(username)
                .claim("roles", roleList)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(expiresAt))
                .signWith(Keys.hmacShaKeyFor(secretKey.getBytes()))
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(secretKey.getBytes())
                    .build()
                    .parseClaimsJws(token);

            Optional<JwtTokenEntity> entity = jwtTokenRepository.findByToken(token);
            return entity.isPresent() && !entity.get().isRevoked() &&
                   entity.get().getExpiresAt().isAfter(Instant.now());

        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getUsername(String token) {
        return getClaims(token).getSubject();
    }

    public List<String> getRoles(String token) {
        Object roles = getClaims(token).get("roles");
        if (roles instanceof List<?>) {
            return ((List<?>) roles).stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey.getBytes())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public Date extractExpiration(String token) {
        return getClaims(token).getExpiration();
    }

    public long getRemainingMillis(String token) {
        Date expiration = extractExpiration(token);
        return expiration.getTime() - System.currentTimeMillis();
    }

    public Authentication getAuthentication(String token) {
        List<GrantedAuthority> authorities = getRoles(token).stream()
                .map(role -> (GrantedAuthority) () -> role)
                .collect(Collectors.toList());

        User principal = new User(getUsername(token), "", authorities);
        return new UsernamePasswordAuthenticationToken(principal, token, authorities);
    }
}
