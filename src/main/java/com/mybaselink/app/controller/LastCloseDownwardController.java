package com.mybaselink.app.controller;

import com.mybaselink.app.service.LastCloseDownwardService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * 연속 하락 종목 조회 및 차트 제공 컨트롤러
 */
@RestController
@RequestMapping("/api/krx")
public class LastCloseDownwardController {

    private final LastCloseDownwardService service;

    public LastCloseDownwardController(LastCloseDownwardService service) {
        this.service = service;
    }

    /**
     * 상위 N 연속 하락 종목 조회
     */
    @GetMapping("/last-close-downward")
    public ResponseEntity<Map<String, Object>> getLastCloseDownward(
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(defaultValue = "10") int topN
    ) {
        try {
            List<Map<String,Object>> results = service.fetchLastCloseDownward(start, end, topN);
            return ResponseEntity.ok(Map.of("results", results));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "연속 하락 종목 조회 실패: " + e.getMessage()));
        }
    }

    /**
     * 개별 종목 차트 Base64 반환
     */
    @GetMapping("/last-close-downward/chart")
    public ResponseEntity<Map<String, Object>> getChart(
            @RequestParam String baseSymbol,
            @RequestParam String start,
            @RequestParam String end
    ) {
        try {
            String base64Image = service.fetchChart(baseSymbol, start, end);
            if (base64Image != null) {
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
