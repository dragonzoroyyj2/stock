package com.mybaselink.app.service;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.mybaselink.app.entity.JwtTokenEntity;
import com.mybaselink.app.repository.JwtTokenRepository;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 🔒 AuthService
 *
 * - 로그인/로그아웃/토큰 갱신/유효성 체크
 * - DB 저장 및 revoked 처리
 */
@Service
public class AuthService {

    private final JwtTokenRepository tokenRepository;

    public AuthService(JwtTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    // 로그인 시 토큰 저장
    @Transactional
    public void login(UserDetails userDetails, String token, Instant expiresAt) {
        JwtTokenEntity entity = new JwtTokenEntity();
        entity.setUsername(userDetails.getUsername());
        entity.setToken(token);
        entity.setIssuedAt(Instant.now());
        entity.setExpiresAt(expiresAt);
        entity.setRevoked(false);
        tokenRepository.save(entity);
    }

    // 로그아웃 시 토큰 revoked 처리
    @Transactional
    public void logout(String token) {
        Optional<JwtTokenEntity> opt = tokenRepository.findByToken(token);
        opt.ifPresent(t -> {
            t.setRevoked(true);
            tokenRepository.save(t);
        });
    }

    // 세션 연장 시 새 토큰 저장
    @Transactional
    public void refreshToken(String oldToken, String newToken, Instant expiresAt) {
        Optional<JwtTokenEntity> optOld = tokenRepository.findByToken(oldToken);
        optOld.ifPresent(t -> t.setRevoked(true));

        JwtTokenEntity newEntity = new JwtTokenEntity();
        newEntity.setToken(newToken);
        newEntity.setUsername(optOld.map(JwtTokenEntity::getUsername).orElse("unknown"));
        newEntity.setIssuedAt(Instant.now());
        newEntity.setExpiresAt(expiresAt);
        newEntity.setRevoked(false);

        tokenRepository.save(newEntity);
    }

    // 토큰 유효성 체크
    public boolean isTokenValid(String token) {
        Optional<JwtTokenEntity> opt = tokenRepository.findByToken(token);
        return opt.isPresent() && !opt.get().isRevoked() && opt.get().getExpiresAt().isAfter(Instant.now());
    }

    // Repository 직접 접근용
    public Optional<JwtTokenEntity> findByToken(String token) {
        return tokenRepository.findByToken(token);
    }
}
