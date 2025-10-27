// src/main/java/com/mybaselink/app/controller/ProfileController.java

package com.mybaselink.app.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProfileController {

    @GetMapping("/profile")
    public ResponseEntity<Map<String, String>> getProfile() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            // 이 코드는 authenticated() 설정 시 사용되므로, permitAll() 시에는 실행되지 않음
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        Map<String, String> profile = new HashMap<>();
        profile.put("name", authentication.getName());
        profile.put("email", "user@example.com"); // 임시 이메일

        return ResponseEntity.ok(profile);
    }
}
