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

@Service
public class SimilarStockAdvancedService {

    private final ObjectMapper mapper = new ObjectMapper();
    //private final String pythonExe = "C:\\Users\\dragon\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
   // private final String scriptPath = "D:\\project\\dev_boot_project\\workspace\\MyBaseLink\\python\\find_similar_full.py";

    
    // ▶ Python 실행 경로
    private final String pythonExe = "C:\\Users\\User\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";

    // ▶ 실행할 Python 스크립트 경로
    private final String scriptPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\find_similar_full.py";

    // ▶ Python에서 생성하는 JSON 결과 경로
    //private final String jsonPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\data\\similarity_result.json";
    
    /**
     * 파이썬 스크립트를 호출하여 유사 종목 리스트를 조회합니다.
     */
    public List<Map<String, Object>> fetchSimilar(String companyCode, String start, String end, int nSimilarStocks) {
        try {
            String[] command = {
                pythonExe,
                "-u",
                scriptPath,
                "--base_symbol", companyCode,
                "--start_date", start,
                "--end_date", end,
                "--n_similar", String.valueOf(nSimilarStocks)
            };

            JsonNode pythonResult = executePythonScript(command);

            if (pythonResult != null) {
                if (pythonResult.has("error")) {
                    System.err.println("파이썬 스크립트 실행 오류: " + pythonResult.get("error").asText());
                    return List.of();
                }
                return mapper.convertValue(pythonResult.get("similar_stocks"), new TypeReference<List<Map<String, Object>>>(){});
            } else {
                System.err.println("파이썬 스크립트 실행 실패: 결과가 null입니다.");
                return List.of();
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("유사 종목 조회 중 오류 발생: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 파이썬 스크립트를 호출하여 개별 종목 차트 이미지를 Base64 문자열로 조회합니다.
     */
    public String fetchChart(String baseSymbol, String compareSymbol, String start, String end) {
        try {
            String[] command = {
                pythonExe,
                "-u",
                scriptPath,
                "--base_symbol", baseSymbol,
                "--start_date", start,
                "--end_date", end,
                "--compare_symbol", compareSymbol
            };

            JsonNode pythonResult = executePythonScript(command);

            if (pythonResult != null) {
                if (pythonResult.has("error")) {
                    System.err.println("파이썬 스크립트 실행 오류: " + pythonResult.get("error").asText());
                    return null;
                }
                return pythonResult.get("image_data").asText();
            } else {
                System.err.println("파이썬 스크립트 실행 실패: 결과가 null입니다.");
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("차트 조회 중 오류 발생: " + e.getMessage());
            return null;
        }
    }

    /**
     * 파이썬 스크립트를 실행하고 표준 출력을 JSON으로 파싱합니다.
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
                throw new TimeoutException("Python process did not finish in time.");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Python script execution failed with exit code: " + exitCode + " Output: " + pythonOutput);
            }
        } finally {
            executor.shutdown();
        }

        if (pythonOutput == null || pythonOutput.trim().isEmpty()) {
            throw new RuntimeException("Python script produced no output.");
        }

        // ✅ 순수 JSON만 출력
        System.out.println(pythonOutput);

        return mapper.readTree(pythonOutput);
    }
}