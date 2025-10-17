package com.mybaselink.app.controller;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 📋 P01A04ApiController - 공용 리스트 페이지용 REST API  
 *
 * ✅ 역할:
 *   - /api/p01a04 → 목록 조회 / 등록 / 수정 / 삭제 / 엑셀 다운로드
 * ✅ JS 연동:
 *   commonUnifiedList.js 의 initUnifiedList() 와 1:1 매칭됨
 */
@RestController
@RequestMapping("/api/p01a04")
public class P01A04ApiController {

    private final List<Map<String, Object>> mockList = new ArrayList<>();

    public P01A04ApiController() {
        for (int i = 1; i <= 100; i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", i);
            item.put("title", "보고서ㅈㄷㄱㄷㅈㄱㄷㅈㄱㅈㄷㄱㅈㄷㄱㄷㅈㄷㅈㄱㄷㅈㄱㄷㅈㄱㄷㄱ " + i);
            item.put("owner", "홍길동");
            item.put("regDate", "2025-10-06");
            mockList.add(item);
        }
    }

    @GetMapping
    public Map<String, Object> getList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search
    ) {
        List<Map<String, Object>> filtered = new ArrayList<>(mockList);

        if (search != null && !search.isEmpty()) {
            filtered.removeIf(row ->
                    !row.get("title").toString().contains(search) &&
                    !row.get("owner").toString().contains(search)
            );
        }

        int start = page * size;
        int end = Math.min(start + size, filtered.size());
        List<Map<String, Object>> paged = filtered.subList(Math.min(start, end), end);

        Map<String, Object> result = new HashMap<>();
        result.put("content", paged);
        result.put("page", page);
        result.put("totalPages", (int) Math.ceil((double) filtered.size() / size));
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getDetail(@PathVariable int id) {
        Optional<Map<String, Object>> found = mockList.stream()
                .filter(m -> (int) m.get("id") == id)
                .findFirst();

        if (found.isPresent()) {
            return ResponseEntity.ok(found.get());
        } else {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "해당 ID의 데이터가 존재하지 않습니다."));
        }
    }

    @PostMapping
    public Map<String, Object> addItem(@RequestBody Map<String, Object> request) {
        int newId = mockList.stream()
                .mapToInt(m -> (int) m.get("id"))
                .max()
                .orElse(0) + 1;

        request.put("id", newId);
        request.putIfAbsent("regDate", "2025-10-06");
        mockList.add(request);

        return Map.of("status", "success", "id", newId);
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateItem(@PathVariable int id, @RequestBody Map<String, Object> request) {
        Optional<Map<String, Object>> found = mockList.stream()
                .filter(m -> (int) m.get("id") == id)
                .findFirst();

        if (found.isPresent()) {
            Map<String, Object> item = found.get();
            item.put("title", request.get("title"));
            item.put("owner", request.get("owner"));
            return Map.of("status", "updated");
        }

        return Map.of("status", "not_found");
    }

    @DeleteMapping
    public Map<String, Object> deleteItems(@RequestBody List<Integer> ids) {
        mockList.removeIf(m -> ids.contains(m.get("id")));
        return Map.of("status", "deleted", "count", ids.size());
    }

    @GetMapping("/excel")
    public ResponseEntity<byte[]> downloadExcel(@RequestParam(required = false) String search) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("리스트");

            // 헤더 스타일
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            // 헤더 작성
            String[] headers = {"ID", "제목", "작성자", "등록일"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 데이터 필터링
            List<Map<String, Object>> filtered = mockList.stream()
                    .filter(item -> search == null || search.isBlank()
                            || item.get("title").toString().contains(search)
                            || item.get("owner").toString().contains(search))
                    .collect(Collectors.toList());

            // 데이터 작성
            int rowIdx = 1;
            for (Map<String, Object> item : filtered) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(item.get("id").toString());
                row.createCell(1).setCellValue(item.get("title").toString());
                row.createCell(2).setCellValue(item.get("owner").toString());
                row.createCell(3).setCellValue(item.get("regDate").toString());
            }

            // 자동 열 너비 조정
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            // 워크북 → 바이트 변환
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            byte[] bytes = out.toByteArray();

            // 파일명 인코딩
            String filename = "리스트_" + LocalDate.now() + ".xlsx";
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replaceAll("\\+", "%20");

            String contentDisposition = "attachment; filename=\"" + filename + "\"";
            contentDisposition += "; filename*=UTF-8''" + encodedFilename;

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                    .header(HttpHeaders.CONTENT_TYPE,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet; charset=UTF-8")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .body(bytes);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
                    .body(("엑셀 생성 중 오류 발생: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
