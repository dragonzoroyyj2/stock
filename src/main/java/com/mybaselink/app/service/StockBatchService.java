package com.mybaselink.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutionException;

@Service
public class StockBatchService {

    private static final Logger logger = LoggerFactory.getLogger(StockBatchService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskStatusService taskStatusService;

    private final String pythonExe = "C:\\Users\\User\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
    private final String stockUpdateScriptPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\update_stock_listing.py";

    public StockBatchService(TaskStatusService taskStatusService) {
        this.taskStatusService = taskStatusService;
    }

    @Async
    public CompletableFuture<Void> startUpdateStockListingTask(String taskId) {
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", null, null));
        Process process = null;
        try {
            logger.info("[{}] 전체 종목 업데이트 작업 시작", taskId);
            
            String[] command = { pythonExe, "-u", stockUpdateScriptPath };
            logger.info("[{}] Python 스크립트 실행 시작: 전체 종목 업데이트. 커맨드: {}", taskId, Arrays.toString(command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true); // 에러 스트림을 출력 스트림과 병합
            
            process = pb.start();
            
            // 파이썬 스크립트의 출력을 실시간으로 로깅
            StreamGobbler gobbler = new StreamGobbler(process.getInputStream(), logger, taskId);
            Thread gobblerThread = new Thread(gobbler);
            gobblerThread.start();
            
            // 프로세스가 5분 안에 종료되기를 기다림
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            
            if (!finished) {
                logger.error("[{}] Python 프로세스 타임아웃 발생. 프로세스를 강제 종료합니다.", taskId);
                process.destroyForcibly();
                gobblerThread.join(1000); // 로깅 스레드 종료 기다림
                throw new IOException("Python 프로세스 타임아웃");
            }
            
            gobblerThread.join(); // 로깅 스레드가 종료되기를 기다림

            int exitCode = process.exitValue();
            
            if (exitCode != 0) {
                logger.error("[{}] Python 스크립트 비정상 종료. 종료 코드: {}. 출력: {}", taskId, exitCode, gobbler.getOutput().toString());
                taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, "Python 스크립트 비정상 종료"));
                return CompletableFuture.completedFuture(null);
            }

            JsonNode pythonResult = null;
            try {
                // 파이썬 스크립트 출력의 마지막 JSON만 파싱
                Pattern jsonPattern = Pattern.compile("\\{.*\\}");
                Matcher matcher = jsonPattern.matcher(gobbler.getOutput().toString());
                if (matcher.find()) {
                    pythonResult = mapper.readTree(matcher.group());
                }
            } catch (JsonProcessingException e) {
                logger.error("[{}] Python 출력 JSON 파싱 오류: {}", taskId, gobbler.getOutput().toString(), e);
                taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, "Python 출력 JSON 파싱 오류"));
                return CompletableFuture.completedFuture(null);
            }
            
            if (pythonResult != null && pythonResult.has("status") && pythonResult.get("status").asText().equals("success")) {
                taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("COMPLETED", pythonResult, null));
                logger.info("[{}] 전체 종목 업데이트 작업 완료", taskId);
            } else {
                String errorMsg = (pythonResult != null && pythonResult.has("error")) ? pythonResult.get("error").asText() : "Python 스크립트 실행 실패";
                taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, errorMsg));
                logger.error("[{}] 전체 종목 업데이트 작업 실패: {}", taskId, errorMsg);
            }
        } catch (Exception e) {
            String errorMsg = "비동기 종목 업데이트 작업 처리 중 오류: " + e.getMessage();
            if (process != null && process.isAlive()) {
                logger.error("[{}] 오류 발생으로 Python 프로세스를 강제 종료합니다.", taskId);
                process.destroyForcibly();
            }
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, errorMsg));
            logger.error("[{}] 비동기 종목 업데이트 작업 실패", taskId, e);
        }
        return CompletableFuture.completedFuture(null);
    }
}
