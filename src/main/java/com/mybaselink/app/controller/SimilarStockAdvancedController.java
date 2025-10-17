package com.mybaselink.app.controller;

import com.mybaselink.app.service.SimilarStockAdvancedService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/krx")
public class SimilarStockAdvancedController {

    private final SimilarStockAdvancedService service;

    // ✅ 수동 생성자 주입 (Lombok 사용 안 함)
    public SimilarStockAdvancedController(SimilarStockAdvancedService service) {
        this.service = service;
    }

    @GetMapping("/similar-advanced")
    public Map<String, Object> getSimilarStocks( 
            @RequestParam String company,
            @RequestParam String start,
            @RequestParam String end
    ) {
        try {
            List<Map<String, Object>> results = service.fetchSimilar(company, start, end);

            if (results == null || results.isEmpty()) {
                return Map.of(
                        "status", "empty",
                        "message", "분석 결과가 없습니다.",
                        "results", List.of()
                );
            }

            return Map.of(
                    "status", "success",
                    "count", results.size(),
                    "results", results
            );

        } catch (Exception e) {
            e.printStackTrace();
            return Map.of(
                    "status", "error",
                    "message", "분석 중 오류가 발생했습니다: " + e.getMessage(),
                    "results", List.of()
            );
        }
    }
}
