package com.mybaselink.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StockService {

    private final ObjectMapper mapper = new ObjectMapper();
    //private final String pythonExe = "C:\\Users\\dragon\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
    //private final String scriptPath = "D:\\project\\dev_boot_project\\workspace\\MyBaseLink\\python\\krx_list_fetch.py";
    //private final String jsonPath = "D:\\project\\dev_boot_project\\workspace\\MyBaseLink\\python\\krx_list_full.json";
    
    private final String pythonExe = "C:\\Users\\User\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
    private final String scriptPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\krx_list_fetch.py";
    private final String jsonPath = "C:\\LocBootProject\\workspace\\MyBaseLink\\python\\krx_list_full.json";

    public List<Map<String, String>> fetchKrxList() { 
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExe, scriptPath);
            pb.directory(new File("C:\\LocBootProject\\workspace\\MyBaseLink\\python"));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            process.waitFor();

            File file = new File(jsonPath);
            if (!file.exists()) return List.of();

            return mapper.readValue(file, new TypeReference<>(){});
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    public List<Map<String, String>> searchKrxList(String keyword, int page, int size) {
        List<Map<String, String>> fullList = fetchKrxList();
        List<Map<String, String>> filtered = fullList.stream()
                .filter(m -> keyword == null || keyword.isEmpty()
                        || m.get("code").contains(keyword)
                        || m.get("name").contains(keyword))
                .collect(Collectors.toList());

        int fromIndex = Math.min((page - 1) * size, filtered.size());
        int toIndex = Math.min(fromIndex + size, filtered.size());

        return filtered.subList(fromIndex, toIndex);
    }

    public int countKrxList(String keyword) {
        List<Map<String, String>> fullList = fetchKrxList();
        return (int) fullList.stream()
                .filter(m -> keyword == null || keyword.isEmpty()
                        || m.get("code").contains(keyword)
                        || m.get("name").contains(keyword))
                .count();
    }
}