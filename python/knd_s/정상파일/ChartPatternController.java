// C:\LocBootProject\workspace\MyBaseLink\src\main\java\com\mybaselink\app\controller\ChartPatternController.java
package com.mybaselink.app.controller;

import com.mybaselink.app.service.ChartPatternService;
import com.mybaselink.app.service.TaskStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/chart")
public class ChartPatternController {

    private static final Logger logger = LoggerFactory.getLogger(ChartPatternController.class);

    private final ChartPatternService chartPatternService;
    private final TaskStatusService taskStatusService;

    public ChartPatternController(ChartPatternService chartPatternService, TaskStatusService taskStatusService) {
        this.chartPatternService = chartPatternService;
        this.taskStatusService = taskStatusService;
    }

    @PostMapping("/patterns/start")
    public ResponseEntity<Map<String, Object>> startPatternTask(@RequestParam String start,
                                                                 @RequestParam String end,
                                                                 @RequestParam String pattern,
                                                                 @RequestParam(defaultValue = "100") int topN) {
        String taskId = UUID.randomUUID().toString();
        logger.info("차트 패턴 분석 요청 접수. taskId={}", taskId);
        try {
            if (chartPatternService.tryLock(taskId)) {
                chartPatternService.startChartPatternTask(taskId, start, end, pattern, topN);
                Map<String, Object> response = new HashMap<>();
                response.put("taskId", taskId);
                response.put("message", "차트 패턴 분석 작업을 시작했습니다.");
                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> response = new HashMap<>();
                response.put("message", "다른 작업이 진행 중입니다. 잠시 후 다시 시도해주세요.");
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
            }
        } catch (Exception e) {
            chartPatternService.unlock(taskId);
            logger.error("패턴 분석 작업 시작 실패: taskId={}", taskId, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("message", "패턴 분석 작업 시작 중 오류 발생");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @GetMapping("/task/status/{taskId}")
    public ResponseEntity<TaskStatusService.TaskStatus> getTaskStatus(@PathVariable String taskId) {
        TaskStatusService.TaskStatus status = taskStatusService.getTaskStatus(taskId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @PostMapping("/chart/fetch/start")
    public ResponseEntity<Map<String, Object>> startChartTask(@RequestParam String baseSymbol,
                                                              @RequestParam String start,
                                                              @RequestParam String end) {
        String taskId = UUID.randomUUID().toString();
        logger.info("차트 생성 요청 접수. taskId={}, symbol={}", taskId, baseSymbol);
        try {
            if (chartPatternService.tryLock(taskId)) {
                chartPatternService.startFetchChartTask(taskId, baseSymbol, start, end);
                Map<String, Object> response = new HashMap<>();
                response.put("taskId", taskId);
                response.put("message", "차트 생성 작업을 시작했습니다.");
                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> response = new HashMap<>();
                response.put("message", "다른 작업이 진행 중입니다. 잠시 후 다시 시도해주세요.");
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
            }
        } catch (Exception e) {
            chartPatternService.unlock(taskId);
            logger.error("차트 생성 작업 시작 실패: taskId={}", taskId, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("message", "차트 생성 작업 시작 중 오류 발생");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}
