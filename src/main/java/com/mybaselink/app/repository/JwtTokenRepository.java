package com.mybaselink.app.repository;

import com.mybaselink.app.entity.JwtTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 🔒 JwtTokenRepository
 *
 * JWT 토큰 DB 저장 및 조회
 */
@Repository
public interface JwtTokenRepository extends JpaRepository<JwtTokenEntity, Long> {

    /**
     * 특정 토큰 조회
     */
    Optional<JwtTokenEntity> findByToken(String token);

    /**
     * 사용자 활성 토큰 조회 (revoked=false)
     */
    List<JwtTokenEntity> findByUsernameAndRevokedFalse(String username);

    /**
     * 만료된 토큰 삭제
     */
    long deleteAllByExpiresAtBefore(Instant now);
}
