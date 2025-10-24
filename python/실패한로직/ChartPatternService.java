// C:\LocBootProject\workspace\MyBaseLink\src\main\java\com\mybaselink\app\service\ChartPatternService.java
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
public class ChartPatternService {

    private static final Logger logger = LoggerFactory.getLogger(ChartPatternService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskStatusService taskStatusService;
    private final NewsDisclosureService newsDisclosureService;

    private final String pythonExe = "C:\\Users\\User\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
    private final String scriptPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\find_chart_patterns.py";

    public ChartPatternService(TaskStatusService taskStatusService, NewsDisclosureService newsDisclosureService) {
        this.taskStatusService = taskStatusService;
        this.newsDisclosureService = newsDisclosureService;
    }

    @Async
    public CompletableFuture<Void> startChartPatternTask(String taskId, String start, String end, String pattern, int topN) {
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", null, null));
        try {
            List<Map<String, Object>> results = getCachedChartPatterns(start, end, pattern, topN);
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("COMPLETED", results, null));
        } catch (Exception e) {
            String errorMsg = "비동기 작업 처리 중 오류: " + e.getMessage();
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, errorMsg));
            logger.error("비동기 작업 실패: taskId={}", taskId, e);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Async
    public CompletableFuture<Void> startFetchChartTask(String taskId, String baseSymbol, String start, String end) {
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
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 차트 이미지와 뉴스/공시 정보를 병렬로 가져오는 새로운 비동기 작업.
     */
    @Async
    public CompletableFuture<Void> startFetchCombinedChartAndDataTask(String taskId, String baseSymbol, String start, String end) {
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", null, null));

        CompletableFuture<String> chartFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return fetchChart(baseSymbol, start, end);
            } catch (Exception e) {
                logger.error("차트 데이터 조회 실패: {}", baseSymbol, e);
                throw new CompletionException(e);
            }
        });
        CompletableFuture<List<Map<String, String>>> newsDisclosureFuture = newsDisclosureService.getNewsAndDisclosures(baseSymbol);

        CompletableFuture.allOf(chartFuture, newsDisclosureFuture)
                .thenAcceptAsync(v -> {
                    try {
                        String base64Image = chartFuture.get();
                        List<Map<String, String>> newsData = newsDisclosureFuture.get();

                        Map<String, Object> resultMap = new HashMap<>();
                        resultMap.put("image_data", base64Image);
                        resultMap.put("news_data", newsData);

                        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("COMPLETED", resultMap, null));
                        logger.info("종목 {}의 차트와 뉴스/공시 정보 병렬 조회 완료.", baseSymbol);

                    } catch (InterruptedException | ExecutionException e) {
                        String errorMsg = "비동기 작업 결합 중 오류: " + e.getMessage();
                        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, errorMsg));
                        logger.error("병렬 작업 결합 실패: taskId={}", taskId, e);
                    }
                })
                .exceptionally(e -> {
                    String errorMsg = "비동기 작업 결합 중 예상치 못한 오류: " + e.getMessage();
                    taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, errorMsg));
                    logger.error("병렬 작업 결합 실패: taskId={}", taskId, e);
                    return null;
                });

        return CompletableFuture.completedFuture(null);
    }

    @Cacheable(value = "chartPatternCache", key = "#start + '-' + #end + '-' + #pattern + '-' + #topN", sync = true)
    public List<Map<String, Object>> getCachedChartPatterns(String start, String end, String pattern, int topN) {
        return executePythonForChartPatternList(start, end, pattern, topN);
    }

    @Cacheable(value = "chartImageCache", key = "#baseSymbol + '-' + #start + '-' + #end", sync = true)
    public String fetchChart(String baseSymbol, String start, String end) {
        return executePythonForChart(baseSymbol, start, end);
    }

    private List<Map<String, Object>> executePythonForChartPatternList(String start, String end, String pattern, int topN) {
        try {
            String[] command = {
                    pythonExe, "-u", scriptPath,
                    "--start_date", start,
                    "--end_date", end,
                    "--pattern", pattern,
                    "--topN", String.valueOf(topN),
                    "--workers", String.valueOf(Runtime.getRuntime().availableProcessors())
            };
            logger.info("Python 스크립트 실행 시작: 차트 패턴 조회. 커맨드: {}", Arrays.toString(command));
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
                throw new RuntimeException("Python 스크립트 결과가 null입니다. 파이썬 스크립트 실행 중 오류 발생 가능.");
            }
        } catch (Exception e) {
            logger.error("Python 스크립트 호출 실패", e);
            throw new RuntimeException("Python 스크립트 호출 실패: " + e.getMessage());
        }
    }

    private String executePythonForChart(String baseSymbol, String start, String end) {
        try {
            String[] command = {
                    pythonExe, "-u", scriptPath,
                    "--base_symbol", baseSymbol,
                    "--start_date", start,
                    "--end_date", end
            };
            logger.info("Python 스크립트 실행 시작: 단일 종목 차트. 커맨드: {}", Arrays.toString(command));
            JsonNode pythonResult = executePythonScript(command);
            if (pythonResult != null && pythonResult.has("image_data")) {
                return pythonResult.get("image_data").asText();
            } else if (pythonResult != null && pythonResult.has("error")) {
                String errorMsg = pythonResult.get("error").asText();
                logger.error("Python 스크립트 실행 오류: {}", errorMsg);
                throw new RuntimeException(errorMsg);
            } else {
                throw new RuntimeException("Python 스크립트 결과에 image_data가 없거나 오류가 발생했습니다.");
            }
        } catch (Exception e) {
            logger.error("Python 스크립트 호출 실패", e);
            throw new RuntimeException("Python 스크립트 호출 실패: " + e.getMessage());
        }
    }

    private JsonNode executePythonScript(String[] command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<Void> outputReader = executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            return null;
        });

        Future<Void> errorReader = executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line).append("\n");
                }
            }
            return null;
        });

        executor.shutdown();

        try {
            outputReader.get(60, TimeUnit.SECONDS);
            errorReader.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException e) {
            logger.error("Python 프로세스 실행 중 예외 발생", e);
        } catch (TimeoutException e) {
            logger.error("Python 프로세스 실행 시간 초과", e);
            process.destroyForcibly();
            throw new IOException("Python 프로세스 실행 시간 초과: " + e.getMessage());
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String errorMsg = "Python 스크립트 실행 실패, 종료 코드: " + exitCode + ", 에러 메시지: " + error.toString();
            logger.error(errorMsg);
            try {
                JsonNode errorJson = mapper.readTree(error.toString());
                return errorJson;
            } catch (IOException e) {
                throw new IOException(errorMsg);
            }
        }
        
        try {
            return mapper.readTree(output.toString());
        } catch (IOException e) {
            logger.error("Python 스크립트 결과 JSON 파싱 실패: {}", output.toString());
            throw new IOException("Python 스크립트 결과 JSON 파싱 실패: " + e.getMessage());
        }
    }
}
