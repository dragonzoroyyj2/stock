package com.mybaselink.app.controller;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 📋 P01A05ApiController - 공용 리스트 페이지용 REST API  
 *
 * ✅ 역할:
 *   - /api/p01a05 → 목록 조회 / 등록 / 수정 / 삭제 / 엑셀 다운로드
 * ✅ JS 연동:
 *   commonUnifiedList_op.js 의 initUnifiedList() 와 1:1 매칭됨
 */
@RestController
@RequestMapping("/api/p01a05")
public class P01A05ApiController {

    /**
     * ✅ 임시 데이터 저장용 (테스트용)
     * 실제로는 Service/DB 연동으로 교체 예정
     */
    private final List<Map<String, Object>> mockList = new ArrayList<>();

    public P01A05ApiController() {
        // 더미 데이터 생성
        for (int i = 1; i <= 600; i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", i);
            item.put("title", "보고서 " + i);
            item.put("owner", "홍길동");
            item.put("regDate", "2025-10-06");
            mockList.add(item);
        }
    }

	 // ===============================
	 // 🔍 리스트 조회 (검색 + 페이징)
	 // ===============================
    @GetMapping
    public Map<String,Object> getList(
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="10") int size,
            @RequestParam(required=false) String search,
            @RequestParam(defaultValue="server") String mode,
            @RequestParam(defaultValue="true") boolean pagination
    ) {
        List<Map<String,Object>> filtered = new ArrayList<>(mockList);

        // 검색 필터링
        if(search != null && !search.isEmpty()){
            String s = search.toLowerCase();
            filtered.removeIf(item -> !safeStr(item.get("title")).toLowerCase().contains(s) &&
                                      !safeStr(item.get("owner")).toLowerCase().contains(s));
        }

        Map<String,Object> result = new HashMap<>();

        if(!pagination || "client".equals(mode)){
            // 클라이언트 모드 or 페이징 false -> 전체 반환
            result.put("content", filtered);
            result.put("page", 0);
            result.put("totalPages", 1);
            result.put("totalElements", filtered.size());
            return result;
        }

        // 서버 모드 + 페이징
        int totalElements = filtered.size();
        int totalPages = (int)Math.ceil((double)totalElements / size);
        int start = page*size;
        int end = Math.min(start+size, totalElements);
        List<Map<String,Object>> paged = filtered.subList(Math.min(start,end), end);

        result.put("content", paged);
        result.put("page", page);
        result.put("totalPages", totalPages);
        result.put("totalElements", totalElements);

        return result;
    }





    // ===============================
    // 🔎 단건 조회 (상세 보기)
    // ===============================
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

    // ===============================
    // ➕ 등록
    // ===============================
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

    // ===============================
    // ✏️ 수정
    // ===============================
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

    // ===============================
    // ❌ 삭제 (다중 삭제)
    // ===============================
    @DeleteMapping
    public Map<String, Object> deleteItems(@RequestBody List<Integer> ids) {
        mockList.removeIf(m -> ids.contains(m.get("id")));
        return Map.of("status", "deleted", "count", ids.size());
    }

    // ===============================
    // 📊 엑셀(XLSX) 다운로드
    // ===============================
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

            // 검색 필터링
            List<Map<String, Object>> filtered = mockList.stream()
                    .filter(item -> search == null || search.isBlank()
                            || safeStr(item.get("title")).contains(search)
                            || safeStr(item.get("owner")).contains(search))
                    .collect(Collectors.toList());

            // 데이터 행 작성
            int rowIdx = 1;
            for (Map<String, Object> item : filtered) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(safeStr(item.get("id")));
                row.createCell(1).setCellValue(safeStr(item.get("title")));
                row.createCell(2).setCellValue(safeStr(item.get("owner")));
                row.createCell(3).setCellValue(safeStr(item.get("regDate")));
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
            String contentDisposition = "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename;

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

    // ===============================
    // 🔹 유틸: null 안전 변환
    // ===============================
    private String safeStr(Object obj) {
        return obj != null ? obj.toString() : "";
    }
}