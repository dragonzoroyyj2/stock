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
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChartPatternService {

    private static final Logger logger = LoggerFactory.getLogger(ChartPatternService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskStatusService taskStatusService;
    private final NewsDisclosureService newsDisclosureService;

    private final String pythonExe = "C:\\Users\\User\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
    private final String scriptPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\find_chart_patterns.py";
    private static final long PYTHON_TIMEOUT_SECONDS = 300; // 파이썬 스크립트 타임아웃 5분 설정

    // 파이썬 실행에 대한 전역 락
    private static final ReentrantLock pythonLock = new ReentrantLock();

    public ChartPatternService(TaskStatusService taskStatusService, NewsDisclosureService newsDisclosureService) {
        this.taskStatusService = taskStatusService;
        this.newsDisclosureService = newsDisclosureService;
    }

    @Async
    public CompletableFuture<Void> startChartPatternTask(String taskId, String start, String end, String pattern, int topN) {
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", null, null));
        try {
            List<Map<String, Object>> results = getCachedChartPatterns(start, end, pattern, topN);
            //taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("COMPLETED", results, null));
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

    @Async
    public CompletableFuture<Void> startFetchCombinedChartAndDataTask(String taskId, String baseSymbol, String start, String end) {
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", null, null));
        
        CompletableFuture<String> chartFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return fetchChart(baseSymbol, start, end);
            } catch (Exception e) {
                logger.error("차트 데이터 조회 실패: {}", baseSymbol, e);
                return null;
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
        if (!pythonLock.tryLock()) {
            throw new IllegalStateException("이미 다른 사용자가 차트 패턴 분석 작업을 진행 중입니다. 잠시 후 다시 시도해주세요.");
        }
        try {
            String[] command = {
                    pythonExe, "-u", scriptPath,
                    "--start_date", start,
                    "--end_date", end,
                    "--pattern", pattern,
                    "--topN", String.valueOf(topN),
                    "--parallel"
            };
            logger.info("Python 스크립트 실행 시작: 차트 패턴 조회. 커맨드: {}", Arrays.toString(command));
            JsonNode pythonResult = executePythonScript(command);
            if (pythonResult != null) {
                if (pythonResult.has("error")) {
                    String errorMsg = pythonResult.get("error").asText();
                    logger.error("Python 스크립트 실행 오류: {}", errorMsg);
                    throw new RuntimeException("Python 스크립트 실행 오류: " + errorMsg);
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
        } finally {
            pythonLock.unlock();
        }
    }

    private String executePythonForChart(String baseSymbol, String start, String end) {
        if (!pythonLock.tryLock()) {
            throw new IllegalStateException("이미 다른 사용자가 차트 이미지 생성 작업을 진행 중입니다. 잠시 후 다시 시도해주세요.");
        }
        try {
            String[] command = {
                    pythonExe, "-u", scriptPath,
                    "--base_symbol", baseSymbol,
                    "--start_date", start,
                    "--end_date", end
            };
            logger.info("Python 스크립트 실행 시작: 차트 이미지 조회. 커맨드: {}", Arrays.toString(command));
            JsonNode pythonResult = executePythonScript(command);
            if (pythonResult != null) {
                if (pythonResult.has("error")) {
                    String errorMsg = pythonResult.get("error").asText();
                    logger.error("Python 스크립트 실행 오류: {}", errorMsg);
                    throw new RuntimeException("Python 스크립트 실행 오류: " + errorMsg);
                }
                return pythonResult.get("image_data").asText();
            } else {
                throw new RuntimeException("Python 스크립트 결과가 null입니다. 파이썬 스크립트 실행 중 오류 발생 가능.");
            }
        } catch (Exception e) {
            logger.error("Python 스크립트 호출 실패", e);
            throw new RuntimeException("Python 스크립트 호출 실패: " + e.getMessage());
        } finally {
            pythonLock.unlock();
        }
    }

    private JsonNode executePythonScript(String[] command) throws IOException, InterruptedException {
        Process process = null;
        JsonNode resultJson = null;
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            logger.info("Executing command: {}", Arrays.toString(command));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            try {
                process = pb.start();
            } catch (IOException e) {
                throw new IOException("Python 프로세스 시작 중 오류 발생. 실행 파일 또는 스크립트 경로를 확인하세요: " + e.getMessage(), e);
            }

            Process finalProcess = process; // Effectively final 변수 생성
            Future<StringBuilder> outputFuture = executor.submit(() -> {
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (IOException e) {
                    // 스트림 읽기 중 오류 발생 시 로깅
                    logger.error("Error reading Python process output stream", e);
                }
                return output;
            });

            if (!process.waitFor(PYTHON_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Python 스크립트가 타임아웃되었습니다.");
            }

            int exitCode = process.exitValue();
            String errorOutput = outputFuture.get().toString();

            if (exitCode != 0) {
                if (errorOutput.contains("SSL: CERTIFICATE_VERIFY_FAILED")) {
                    process.destroyForcibly();
                    throw new IOException("Python 스크립트 실행 중 SSL 통신 오류 발생. 프로세스 강제 종료.");
                }
                throw new IOException("Python 스크립트 종료 코드: " + exitCode + ". 에러 메시지: " + errorOutput);
            }

            String outputString = errorOutput.trim();
            if (!outputString.isEmpty()) {
                resultJson = mapper.readTree(outputString);
            }
        } catch (ExecutionException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IOException("비동기 작업 처리 중 오류: " + e.getCause().getMessage(), e);
        } finally {
            if (process != null) {
                try {
                    process.getInputStream().close();
                    process.getErrorStream().close();
                } catch (IOException e) {
                    logger.error("프로세스 스트림 종료 중 오류 발생", e);
                }
            }
            executor.shutdown();
        }
        return resultJson;
    }
}
