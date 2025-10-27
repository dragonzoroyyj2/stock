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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutionException;

@Service
public class ChartPatternService2 {

    private static final Logger logger = LoggerFactory.getLogger(ChartPatternService2.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskStatusService taskStatusService;
    private final NewsDisclosureService newsDisclosureService;

    private final String pythonExe = "C:\\Users\\User\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
    private final String scriptPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\find_chart_patterns.py";

    public ChartPatternService2(TaskStatusService taskStatusService, NewsDisclosureService newsDisclosureService) {
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

    /**
     * 차트 이미지와 뉴스/공시 정보를 병렬로 가져오는 새로운 비동기 작업.
     */
    @Async
    public CompletableFuture<Void> startFetchCombinedChartAndDataTask(String taskId, String baseSymbol, String start, String end) {
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", null, null));

        // 차트 이미지와 뉴스/공시 정보를 비동기적으로 가져오는 CompletableFuture를 생성
        CompletableFuture<String> chartFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return fetchChart(baseSymbol, start, end);
            } catch (Exception e) {
                logger.error("차트 데이터 조회 실패: {}", baseSymbol, e);
                return null;
            }
        });
        CompletableFuture<List<Map<String, String>>> newsDisclosureFuture = newsDisclosureService.getNewsAndDisclosures(baseSymbol);

        // 두 작업이 모두 완료되면 결과를 합쳐서 TaskStatus에 저장
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
                    "--parallel"
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
                    "--end_date", end,
                    "--chart"
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
                throw new RuntimeException("Python 스크립트 결과가 null입니다. 파이썬 스크립트 실행 중 오류 발생 가능.");
            }
        } catch (Exception e) {
            logger.error("차트 생성 실패 ({}).", baseSymbol, e);
            throw new RuntimeException("차트 생성 실패: " + e.getMessage());
        }
    }

    private JsonNode executePythonScript(String[] command) throws IOException, InterruptedException, TimeoutException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        long pid = process.pid();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), "OUTPUT", executor);
            StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), "ERROR", executor);

            Future<String> outputFuture = outputGobbler.readFully();
            Future<String> errorFuture = errorGobbler.readFully();

            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                killProcessTree(pid);
                throw new TimeoutException("Python 스크립트 실행 시간 초과");
            }

            int exitCode = process.exitValue();
            String errorOutput = null;
            String output = null;

            try {
                errorOutput = errorFuture.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                logger.error("오류 스트림 읽기 실패", e);
            }

            try {
                output = outputFuture.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                logger.error("출력 스트림 읽기 실패", e);
            }

            if (exitCode != 0) {
                String errorMsg = "Python 스크립트 종료 코드: " + exitCode + ". 에러 메시지: " + errorOutput;
                throw new IOException(errorMsg);
            }

            if (output == null || output.trim().isEmpty()) {
                return null;
            }

            // 첫 번째 JSON 객체만 파싱
            Pattern pattern = Pattern.compile("\\{.*\\}");
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                String jsonStr = matcher.group();
                return mapper.readTree(jsonStr);
            } else {
                return mapper.readTree("[]");
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    logger.warn("ExecutorService가 30초 내에 종료되지 않았습니다. 강제 종료합니다.");
                    executor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void killProcessTree(long pid) {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            try {
                ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid));
                Process process = pb.start();
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.error("Failed to kill process tree on Windows: " + pid, e);
            }
        } else {
            try {
                ProcessBuilder pb = new ProcessBuilder("pkill", "-TERM", "-P", String.valueOf(pid));
                Process process = pb.start();
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.error("Failed to kill process tree on Unix: " + pid, e);
            }
        }
    }

    // StreamGobbler 내부 정적 클래스
    static class StreamGobbler {
        private final InputStream inputStream;
        private final String streamType;
        private final ExecutorService executor;
        private final StringBuilder content = new StringBuilder();

        public StreamGobbler(InputStream inputStream, String streamType, ExecutorService executor) {
            this.inputStream = inputStream;
            this.streamType = streamType;
            this.executor = executor;
        }

        public Future<String> readFully() {
            return executor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append(System.lineSeparator());
                        if ("ERROR".equals(streamType)) {
                            logger.error("Python Error Stream: {}", line);
                        } else {
                            logger.info("Python Output Stream: {}", line);
                        }
                    }
                } catch (IOException e) {
                    logger.error("StreamGobbler error", e);
                }
                return content.toString();
            });
        }
    }
}
