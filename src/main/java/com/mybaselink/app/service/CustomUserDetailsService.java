package com.mybaselink.app.service;


import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 🧩 사용자 정보 서비스
 * 
 * - 실제 환경에서는 DB 연동
 * - 현재는 메모리 기반 테스트 계정 제공
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        // ✅ 테스트용 계정 (DB 대신 하드코딩)
        if ("admin".equals(username)) {
            return User.builder()
                    .username("admin")
                    .password(encoder.encode("1234")) // 🔒 암호화된 비밀번호
                    .authorities(Collections.singleton(() -> "ROLE_ADMIN"))
                    .build();
        }

        if ("test".equals(username)) {
            return User.builder()
                    .username("test")
                    .password(encoder.encode("1234"))
                    //.authorities(Collections.singleton(() -> "ROLE_USER"))
                    .authorities(Collections.singleton(() -> "ROLE_ADMIN"))
                    .build();
        }

        throw new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username);
    }
}