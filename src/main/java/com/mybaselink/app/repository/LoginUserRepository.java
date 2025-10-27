package com.mybaselink.app.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.mybaselink.app.entity.LoginUserEntity;

/**
 * 🔑 LoginUserRepository - 로그인용 사용자 조회
 */
@Repository
public interface LoginUserRepository extends JpaRepository<LoginUserEntity, Long> {

    /**
     * 🔹 username 기반 사용자 조회
     */
    LoginUserEntity findByUsername(String username);
}
