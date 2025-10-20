package com.mybaselink.app.controller;

import com.mybaselink.app.service.SimilarStockAdvancedService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/krx")
public class SimilarStockAdvancedController {

    private final SimilarStockAdvancedService service;

    public SimilarStockAdvancedController(SimilarStockAdvancedService service) {
        this.service = service;
    }

    /**
     * 유사 종목 분석
     */
    @GetMapping("/similar-advanced")
    public ResponseEntity<Map<String, Object>> getSimilarStocks(
            @RequestParam String companyCode,
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(defaultValue = "10") int nSimilarStocks
    ) {
        try {
            List<Map<String,Object>> results = service.fetchSimilar(companyCode, start, end, nSimilarStocks);

            // ✅ JS와 파이썬 결과 구조를 동일하게 맞춤
            Map<String, Object> responseBody = Map.of(
                "base_symbol", companyCode,
                "similar_stocks", results
            );

            return new ResponseEntity<>(responseBody, HttpStatus.OK);

        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> errorBody = Map.of(
                "error", e.getMessage()
            );
            return new ResponseEntity<>(errorBody, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 유사 종목 차트
     */
    @GetMapping("/similar-advanced/chart")
    public ResponseEntity<Map<String, Object>> getChart(
            @RequestParam String baseSymbol,
            @RequestParam String compareSymbol,
            @RequestParam String start,
            @RequestParam String end
    ) {
        try {
            String base64Image = service.fetchChart(baseSymbol, compareSymbol, start, end);
            if (base64Image != null) {
                // ✅ 프론트가 chartData.image_data 로 접근 가능하게
                return ResponseEntity.ok(Map.of("image_data", base64Image));
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "차트 생성 실패"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "차트 조회 중 오류 발생: " + e.getMessage()));
        }
    }
}