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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChartPatternService {

    private static final Logger logger = LoggerFactory.getLogger(ChartPatternService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskStatusService taskStatusService;

    private final String pythonExe = "C:\\Users\\User\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
    private final String scriptPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\find_chart_patterns.py";

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
        StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), "OUTPUT", executor);
        StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), "ERROR", executor);

        Future<String> outputFuture = outputGobbler.readFully();
        Future<String> errorFuture = errorGobbler.readFully();

        try {
            if (!process.waitFor(2, TimeUnit.MINUTES)) { // 타임아웃을 2분으로 확장
                killProcessTree(pid);
                throw new TimeoutException("Python 스크립트 실행 시간 초과");
            }

            int exitCode = process.exitValue();
            String errorOutput = null;
            try {
                errorOutput = errorFuture.get();
            } catch (ExecutionException e) {
                logger.error("오류 스트림 읽기 실패", e);
            }

            String lastErrorLine = getLastJsonFromStream(errorOutput);
            if (lastErrorLine != null) {
                throw new IOException("Python 스크립트에서 에러가 발생했습니다: " + lastErrorLine);
            }
            
            if (exitCode != 0) {
                throw new IOException("Python 스크립트에서 에러가 발생했습니다 (종료 코드: " + exitCode + ", 에러 메시지: " + errorOutput + ")");
            }

            String result = null;
            try {
                result = outputFuture.get();
            } catch (ExecutionException e) {
                throw new IOException("결과 스트림 읽기 실패", e);
            }
            
            if (result == null || result.trim().isEmpty()) {
                return null;
            }
            return mapper.readTree(result);

        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Executor가 종료되지 않았습니다.");
            }
        }
    }

    private static class StreamGobbler implements Runnable {
        private final InputStream inputStream;
        private final String type;
        private final ExecutorService executor;
        private final CompletableFuture<String> resultFuture = new CompletableFuture<>();

        StreamGobbler(InputStream inputStream, String type, ExecutorService executor) {
            this.inputStream = inputStream;
            this.type = type;
            this.executor = executor;
        }

        public Future<String> readFully() {
            executor.execute(this);
            return resultFuture;
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                    logger.debug("[{}] {}", type, line);
                }
                resultFuture.complete(output.toString());
            } catch (IOException e) {
                resultFuture.completeExceptionally(e);
            }
        }
    }

    private String getLastJsonFromStream(String stream) {
        if (stream == null || stream.trim().isEmpty()) {
            return null;
        }
        Pattern pattern = Pattern.compile("\\{[^\\{]*\"error\":[^\\}]*\\}", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(stream);
        String lastMatch = null;
        while (matcher.find()) {
            lastMatch = matcher.group();
        }
        return lastMatch;
    }

    private void killProcessTree(long pid) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            try {
                logger.warn("프로세스 트리 종료 시도: PID {}", pid);
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid)).start().waitFor(10, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException e) {
                logger.error("프로세스 트리 종료 실패", e);
            }
        }
    }
}
