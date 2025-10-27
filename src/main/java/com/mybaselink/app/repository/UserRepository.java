package com.mybaselink.app.repository;

import com.mybaselink.app.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 🔑 UserRepository - 사용자 조회/관리용 Repository
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    /**
     * username 으로 사용자 조회 (로그인에 사용)
     */
    UserEntity findByUsername(String username);
}
