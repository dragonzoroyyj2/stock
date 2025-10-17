package com.mybaselink.app.jwt;

import java.security.Key;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;

/**
 * �뵍 JwtUtil - JWT �좏겙 �좏떥由ы떚 �대옒��
 *
 * �� 二쇱슂 湲곕뒫
 * 1截뤴깵 �좏겙 �앹꽦 (generateToken)
 * 2截뤴깵 �좏겙 寃�利� (validateToken)
 * 3截뤴깵 �좏겙�먯꽌 �ъ슜�먮챸 異붿텧 (getUsername)
 * 4截뤴깵 �붿껌 �ㅻ뜑�먯꽌 �좏겙 異붿텧 (resolveToken)
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secretKey; // �뵺 application.properties �� �ㅼ젙

    @Value("${jwt.expiration}")
    private long expiration;  // �뵺 �좏겙 �좏슚�쒓컙 (ms �⑥쐞)

    /**
     * �� JWT �앹꽦
     */
    public String generateToken(UserDetails userDetails) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration);

        Key key = Keys.hmacShaKeyFor(secretKey.getBytes());

        return Jwts.builder()
                .setSubject(userDetails.getUsername())
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * �� �좏겙�먯꽌 �ъ슜�먮챸 異붿텧
     */
    public String getUsername(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey.getBytes())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    /**
     * �� �좏겙 �좏슚�� 寃�利�
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(secretKey.getBytes())
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * �� �붿껌 �ㅻ뜑�먯꽌 �좏겙 異붿텧
     */
    public String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    /**
     * �� 留뚮즺 �쒓컙 諛섑솚 (ms)
     */
    public long getExpiration() {
        return expiration;
    }
}
