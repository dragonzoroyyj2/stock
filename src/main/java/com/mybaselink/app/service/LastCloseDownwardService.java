package com.mybaselink.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Python 스크립트를 호출하여 연속 하락 종목 조회 및 차트 반환 서비스
 */
@Service
public class LastCloseDownwardService {

    private final ObjectMapper mapper = new ObjectMapper();

    // Python 실행 경로
    private final String pythonExe = "C:\\Users\\User\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
    // Python 스크립트 경로
    private final String scriptPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\find_last_close_downward.py";

    /**
     * Python 호출하여 상위 N 연속 하락 종목 리스트 조회
     */
    public List<Map<String, Object>> fetchLastCloseDownward(String start, String end, int topN) {
        try {
            String[] command = {
                    pythonExe,
                    "-u",
                    scriptPath,
                    "--start_date", start,
                    "--end_date", end,
                    "--topN", String.valueOf(topN)
            };

            JsonNode pythonResult = executePythonScript(command);

            if (pythonResult != null) {
                if (pythonResult.has("error")) {
                    throw new RuntimeException(pythonResult.get("error").asText());
                }
                return mapper.convertValue(pythonResult.get("results"), new TypeReference<List<Map<String, Object>>>(){});
            } else {
                throw new RuntimeException("Python 스크립트 결과가 null입니다.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Python 스크립트 호출 실패: " + e.getMessage());
        }
    }

    /**
     * Python 호출하여 개별 종목 차트 Base64 반환
     */
    public String fetchChart(String baseSymbol, String start, String end) {
        try {
            String[] command = {
                    pythonExe,
                    "-u",
                    scriptPath,
                    "--base_symbol", baseSymbol,
                    "--start_date", start,
                    "--end_date", end
            };

            JsonNode pythonResult = executePythonScript(command);

            if (pythonResult != null) {
                if (pythonResult.has("error")) {
                    throw new RuntimeException(pythonResult.get("error").asText());
                }
                return pythonResult.get("image_data").asText();
            } else {
                throw new RuntimeException("Python 스크립트 결과가 null입니다.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("차트 생성 실패: " + e.getMessage());
        }
    }

    /**
     * Python 스크립트 실행 및 JSON 파싱
     */
    private JsonNode executePythonScript(String[] command) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        File scriptDir = new File("C:\\LocBootProject\\workspace\\MyBaseLink\\python");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(scriptDir);
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        Process process = pb.start();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 표준 출력 읽기 (JSON 결과)
        Future<String> outputFuture = executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                return "";
            }
        });

        // 표준 에러 읽기 (로그)
        Future<Void> errorFuture = executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(line -> System.err.println("[Python ERR] " + line));
            } catch (IOException e) {
                // 무시
            }
            return null;
        });

        String pythonOutput = null;
        try {
            pythonOutput = outputFuture.get(180, TimeUnit.SECONDS);
            errorFuture.get(180, TimeUnit.SECONDS);
            boolean finished = process.waitFor(180, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TimeoutException("Python 프로세스가 시간 내 종료되지 않았습니다.");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Python 스크립트 종료 코드: " + exitCode + ", 출력: " + pythonOutput);
            }
        } finally {
            executor.shutdown();
        }

        if (pythonOutput == null || pythonOutput.trim().isEmpty()) {
            throw new RuntimeException("Python 스크립트 출력이 없습니다.");
        }

        System.out.println(pythonOutput); // ✅ 콘솔에도 출력

        return mapper.readTree(pythonOutput);
    }
}
