// src/main/java/com/mybaselink/app/service/ChartPatternService.java
package com.mybaselink.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChartPatternService {

    private static final Logger logger = LoggerFactory.getLogger(ChartPatternService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskStatusService taskStatusService;

    private final String pythonExe = "C:\\Users\\dragon\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
    private final String scriptPath = "D:\\project\\dev_boot_project\\workspace\\MyBaseLink\\python\\find_chart_patterns.py";

    private final AtomicBoolean pythonScriptLock = new AtomicBoolean(false);

    public ChartPatternService(TaskStatusService taskStatusService) {
        this.taskStatusService = taskStatusService;
    }

    public boolean tryLock(String taskId) {
        return pythonScriptLock.compareAndSet(false, true);
    }

    public void unlock(String taskId) {
        if (pythonScriptLock.get()) {
            pythonScriptLock.set(false);
        }
    }

    @Async
    public void startChartPatternTask(String taskId, String start, String end, String pattern, int topN) {
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", null, null));
        try {
            List<Map<String, Object>> results = getCachedChartPatterns(start, end, pattern, topN);
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("COMPLETED", results, null));
        } catch (Exception e) {
            String errorMsg = "비동기 작업 처리 중 오류: " + e.getMessage();
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, errorMsg));
            logger.error("비동기 작업 실패: taskId={}", taskId, e);
        } finally {
            unlock(taskId);
        }
    }

    @Async
    public void startFetchChartTask(String taskId, String baseSymbol, String start, String end) {
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", null, null));
        try {
            String base64Image = fetchChart(baseSymbol, start, end);
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("image_data", base64Image);
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("COMPLETED", resultMap, null));
        } catch (Exception e) {
            String errorMsg = "비동기 작업 처리 중 오류: " + e.getMessage();
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, errorMsg));
            logger.error("비동기 작업 실패: taskId={}", taskId, e);
        } finally {
            unlock(taskId);
        }
    }

    @Cacheable(value = "chartPatternCache", key = "#start + '-' + #end + '-' + #pattern + '-' + #topN", sync = true)
    public List<Map<String, Object>> getCachedChartPatterns(String start, String end, String pattern, int topN) {
        return executePythonForChartPatternList(start, end, pattern, topN);
    }

    @Cacheable(value = "chartImageCache", key = "#baseSymbol + '-' + #start + '-' + #end", sync = true)
    public String fetchChart(String baseSymbol, String start, String end) {
        return executePythonForChart(baseSymbol, start, end);
    }

    // Python 호출 로직 (차트 패턴 리스트)
    private List<Map<String, Object>> executePythonForChartPatternList(String start, String end, String pattern, int topN) {
        try {
            String[] command = {
                    pythonExe, "-u", scriptPath,
                    "--start_date", start,
                    "--end_date", end,
                    "--pattern", pattern,
                    "--topN", String.valueOf(topN)
            };
            logger.info("Python 스크립트 실행 시작: 차트 패턴 조회");
            JsonNode pythonResult = executePythonScript(command);
            if (pythonResult != null) {
                if (pythonResult.has("error")) {
                    String errorMsg = pythonResult.get("error").asText();
                    logger.error("Python 스크립트 실행 오류: {}", errorMsg);
                    throw new RuntimeException(errorMsg);
                }
                List<Map<String, Object>> results = mapper.convertValue(
                        pythonResult,
                        new TypeReference<List<Map<String, Object>>>() {}
                );
                logger.info("Python 스크립트 실행 완료: 차트 패턴 조회 (총 {}건)", results.size());
                return results;
            } else {
                throw new RuntimeException("Python 스크립트 결과가 null입니다.");
            }
        } catch (Exception e) {
            logger.error("Python 스크립트 호출 실패", e);
            throw new RuntimeException("Python 스크립트 호출 실패: " + e.getMessage());
        }
    }

    // Python 호출 로직 (차트 생성)
    private String executePythonForChart(String baseSymbol, String start, String end) {
        try {
            String[] command = {
                    pythonExe, "-u", scriptPath,
                    "--base_symbol", baseSymbol,
                    "--start_date", start,
                    "--end_date", end,
                    "--chart",
                    "--pattern", "None" // 차트 생성 시 패턴은 무의미하므로 기본값 전달
            };
            logger.info("종목 {} 차트 생성 시작. 기간: {} ~ {}", baseSymbol, start, end);
            JsonNode pythonResult = executePythonScript(command);
            if (pythonResult != null) {
                if (pythonResult.has("error")) {
                    String errorMsg = pythonResult.get("error").asText();
                    logger.error("Python 스크립트 차트 생성 오류 ({}): {}", baseSymbol, errorMsg);
                    throw new RuntimeException(errorMsg);
                }
                JsonNode imageDataNode = pythonResult.get("image_data");
                if (imageDataNode == null || imageDataNode.isNull()) {
                    return null;
                }
                String imageData = imageDataNode.asText();
                logger.info("종목 {} 차트 생성 완료.", baseSymbol);
                return imageData;
            } else {
                throw new RuntimeException("Python 스크립트 결과가 null입니다.");
            }
        } catch (Exception e) {
            logger.error("차트 생성 실패 ({}).", baseSymbol, e);
            throw new RuntimeException("차트 생성 실패: " + e.getMessage());
        }
    }

    // Python 스크립트 실행 및 JSON 파싱
    private JsonNode executePythonScript(String[] command) throws IOException, InterruptedException, TimeoutException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<JsonNode> future = executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
                String jsonOutput = output.toString();
                if (jsonOutput.isEmpty()) {
                    throw new IOException("Python 스크립트가 출력을 생성하지 않았습니다.");
                }
                return mapper.readTree(jsonOutput);
            }
        });
        JsonNode result = null;
        try {
            result = future.get(300, TimeUnit.SECONDS);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                if (result != null && result.has("error")) {
                    throw new RuntimeException("Python 스크립트가 오류와 함께 종료됨: " + result.get("error").asText());
                } else {
                    throw new RuntimeException("Python 스크립트가 비정상적으로 종료되었습니다. (종료 코드: " + exitCode + ")");
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else {
                throw new RuntimeException("파이썬 스크립트 실행 중 예외 발생", e);
            }
        } finally {
            executor.shutdown();
        }
        return result;
    }
}
