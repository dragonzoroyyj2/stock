package com.mybaselink.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class SimilarStockAdvancedService {

    private final ObjectMapper mapper = new ObjectMapper();

    // ▶ Python 실행 경로
    private final String pythonExe = "C:\\Users\\User\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";

    // ▶ 실행할 Python 스크립트 경로
    private final String scriptPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\find_similar_full.py";

    // ▶ Python에서 생성하는 JSON 결과 경로
    private final String jsonPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\data\\similarity_result.json";


    /**
     * 유사 종목 분석 실행
     *
     * @param company 기준 종목코드 (예: "005930.KS")
     * @param start   시작일 (예: "2024-01-01")
     * @param end     종료일 (예: "2024-12-31")
     * @return 유사 종목 결과 리스트
     */
    public List<Map<String, Object>> fetchSimilar(String company, String start, String end) {
        try {
            // 🟢 Python 프로세스 실행 시 파라미터 전달
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExe,
                    scriptPath,
                    company,
                    start,
                    end
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 🟢 Python 출력 UTF-8로 읽기 (한글 깨짐 방지)
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[Python] " + line);
                }
            }

            // 🟢 Python 실행 완료 대기
            int exitCode = process.waitFor();
            System.out.println("[Java] Python 프로세스 종료 코드: " + exitCode);

            // 🟢 결과 파일 확인
            File file = new File(jsonPath);
            if (!file.exists()) {
                System.err.println("[Java] 결과 JSON 파일이 존재하지 않습니다: " + jsonPath);
                return List.of();
            }

            // 🟢 JSON 결과 파싱
            List<Map<String, Object>> result = mapper.readValue(file, new TypeReference<>() {});
            System.out.println("[Java] 분석 결과 로드 완료. 총 항목 수: " + result.size());

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }
}
