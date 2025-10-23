// src/main/java/com/mybaselink/app/controller/LastCloseDownwardController.java
package com.mybaselink.app.controller;

import com.mybaselink.app.service.LastCloseDownwardService;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 비동기 작업 처리를 위한 컨트롤러
 */
@RestController
@RequestMapping("/api/krx")
public class LastCloseDownwardController {

    private static final Logger logger = LoggerFactory.getLogger(LastCloseDownwardController.class);
    private final LastCloseDownwardService lastCloseDownwardService;
    private final TaskStatusService taskStatusService;
    
    // 서비스의 잠금 변수를 직접 사용하지 않고, 서비스 메서드를 통해 잠금을 제어
    // 컨트롤러는 요청을 전달하고 응답을 처리하는 역할에 집중
    private final AtomicBoolean pythonScriptLock;

    @Autowired
    public LastCloseDownwardController(LastCloseDownwardService lastCloseDownwardService, TaskStatusService taskStatusService) {
        this.lastCloseDownwardService = lastCloseDownwardService;
        this.taskStatusService = taskStatusService;
        this.pythonScriptLock = lastCloseDownwardService.getPythonScriptLock(); // 서비스의 잠금 객체 주입
    }
    
    // LastCloseDownwardService에 getPythonScriptLock() 메서드 추가 필요

    /**
     * 상위 N 연속 하락 종목 비동기 조회 요청
     * GET /api/krx/last-close-downward/request?start=2023-01-01&end=2024-01-01&topN=10
     */
    @GetMapping("/last-close-downward/request")
    public ResponseEntity<?> requestLastCloseDownward(
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(defaultValue = "10") int topN
    ) {
        if (pythonScriptLock.get()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "다른 분석 작업이 진행 중입니다. 잠시 후 다시 시도해주세요."));
        }
        
        String newTaskId = UUID.randomUUID().toString();
        lastCloseDownwardService.startLastCloseDownwardTask(newTaskId, start, end, topN);
        return ResponseEntity.accepted().body(Map.of("taskId", newTaskId));
    }

    /**
     * 개별 종목 차트 비동기 생성 요청
     * GET /api/krx/last-close-downward/chart/request?baseSymbol=005930&start=2023-01-01&end=2024-01-01
     */
    @GetMapping("/last-close-downward/chart/request")
    public ResponseEntity<?> requestChart(
            @RequestParam String baseSymbol,
            @RequestParam String start,
            @RequestParam String end
    ) {
        if (pythonScriptLock.get()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "다른 분석 작업이 진행 중입니다. 잠시 후 다시 시도해주세요."));
        }
        
        String newTaskId = UUID.randomUUID().toString();
        lastCloseDownwardService.startFetchChartTask(newTaskId, baseSymbol, start, end);
        return ResponseEntity.accepted().body(Map.of("taskId", newTaskId));
    }

    /**
     * 작업 상태 조회 및 결과 반환
     * GET /api/krx/task/status?taskId=...
     */
    @GetMapping("/task/status")
    public ResponseEntity<?> getTaskStatus(@RequestParam String taskId) {
        TaskStatusService.TaskStatus status = taskStatusService.getTaskStatus(taskId);

        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("taskId", taskId);
        responseMap.put("status", status.getStatus());
        responseMap.put("result", status.getResult());
        responseMap.put("error", status.getError());

        return ResponseEntity.ok(responseMap);
    }
}
