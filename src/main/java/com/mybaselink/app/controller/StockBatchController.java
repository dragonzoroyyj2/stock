// C:\LocBootProject\workspace\MyBaseLink\src\main\java\com\mybaselink\app\controller\StockBatchController.java
package com.mybaselink.app.controller;

import com.mybaselink.app.service.StockBatchService;
import com.mybaselink.app.service.TaskStatusService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.IOException;
import java.util.List;

@Controller
@RequestMapping("/batch")
public class StockBatchController {

    private static final Logger logger = LoggerFactory.getLogger(StockBatchController.class);

    private final StockBatchService stockBatchService;
    private final TaskStatusService taskStatusService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StockBatchController(StockBatchService stockBatchService, TaskStatusService taskStatusService) {
        this.stockBatchService = stockBatchService;
        this.taskStatusService = taskStatusService;
    }

    @PostMapping("/stock/update/start")
    public ResponseEntity<Map<String, Object>> startStockListingUpdateTask() {
        String taskId = UUID.randomUUID().toString();
        logger.info("전체 종목 목록 업데이트 요청 접수. taskId={}", taskId);
        try {
            stockBatchService.startUpdateStockListingTask(taskId);
            Map<String, Object> response = new HashMap<>();
            response.put("taskId", taskId);
            response.put("message", "전체 종목 목록 업데이트 작업을 시작했습니다.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("종목 목록 업데이트 작업 시작 실패: taskId={}", taskId, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("message", "종목 목록 업데이트 작업 시작 중 오류 발생");
            return ResponseEntity.status(500).body(errorResponse);
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
    
    @GetMapping("/stock-list")
    public ResponseEntity<Map<String, Object>> getStockList() {
        try {
            // 파이썬 스크립트 실행 경로를 기준으로 stock_data/stock_listing.json 파일 경로를 찾음
            String pythonDir = "C:\\LocBootProject\\workspace\\MyBaseLink\\python";
            String dataDir = pythonDir + File.separator + "stock_data";
            String cachePath = dataDir + File.separator + "stock_listing.json";
            File cacheFile = new File(cachePath);

            if (cacheFile.exists()) {
                List<Map<String, Object>> stockList = objectMapper.readValue(cacheFile, new TypeReference<List<Map<String, Object>>>() {});
                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("stock_list", stockList);
                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("status", "error");
                errorResponse.put("error", "캐시된 종목 목록 파일이 없습니다. 업데이트를 먼저 실행하세요.");
                return ResponseEntity.status(404).body(errorResponse);
            }
        } catch (Exception e) {
            logger.error("종목 목록 조회 중 오류 발생", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("error", "종목 목록 조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
}
