// src/main/java/com/mybaselink/app/controller/ChartPatternController.java
package com.mybaselink.app.controller;

import com.mybaselink.app.service.ChartPatternService;
import com.mybaselink.app.service.TaskStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 차트 패턴 분석을 위한 컨트롤러
 */
@RestController
@RequestMapping("/api/krx/pattern")
public class ChartPatternController {

    private static final Logger logger = LoggerFactory.getLogger(ChartPatternController.class);
    private final ChartPatternService chartPatternService;
    private final TaskStatusService taskStatusService;

    @Autowired
    public ChartPatternController(ChartPatternService chartPatternService, TaskStatusService taskStatusService) {
        this.chartPatternService = chartPatternService;
        this.taskStatusService = taskStatusService;
    }

    /**
     * 차트 패턴 분석 비동기 조회 요청
     * GET /api/krx/pattern/request?start=2023-01-01&end=2024-01-01&pattern=double_bottom&topN=10
     */
    @GetMapping("/request")
    public ResponseEntity<?> requestChartPattern(
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam String pattern,
            @RequestParam(defaultValue = "10") int topN
    ) {
        String newTaskId = UUID.randomUUID().toString();
        if (chartPatternService.tryLock(newTaskId)) {
            chartPatternService.startChartPatternTask(newTaskId, start, end, pattern, topN);
            return ResponseEntity.accepted().body(Map.of("taskId", newTaskId));
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "다른 분석 작업이 진행 중입니다. 잠시 후 다시 시도해주세요."));
        }
    }

    /**
     * 개별 종목 차트 비동기 생성 요청
     * GET /api/krx/pattern/chart/request?baseSymbol=005930&start=2023-01-01&end=2024-01-01
     */
    @GetMapping("/chart/request")
    public ResponseEntity<?> requestChart(
            @RequestParam String baseSymbol,
            @RequestParam String start,
            @RequestParam String end
    ) {
        String newTaskId = UUID.randomUUID().toString();
        if (chartPatternService.tryLock(newTaskId)) {
            chartPatternService.startFetchChartTask(newTaskId, baseSymbol, start, end);
            return ResponseEntity.accepted().body(Map.of("taskId", newTaskId));
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "다른 분석 작업이 진행 중입니다. 잠시 후 다시 시도해주세요."));
        }
    }

    /**
     * 작업 상태 조회 및 결과 반환
     * GET /api/krx/pattern/task/status?taskId=...
     */
    @GetMapping("/task/status")
    public ResponseEntity<?> getTaskStatus(@RequestParam String taskId) {
        TaskStatusService.TaskStatus status = taskStatusService.getTaskStatus(taskId);
        if ("COMPLETED".equals(status.getStatus()) || "FAILED".equals(status.getStatus())) {
            chartPatternService.unlock(taskId);
        }

        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("taskId", taskId);
        responseMap.put("status", status.getStatus());
        responseMap.put("result", status.getResult());
        responseMap.put("error", status.getError());

        return ResponseEntity.ok(responseMap);
    }
}
