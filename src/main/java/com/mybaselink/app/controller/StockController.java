package com.mybaselink.app.controller;

import com.mybaselink.app.service.StockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/api/krx")
    public List<Map<String, String>> getKrxList() {
        return stockService.fetchKrxList();
    }
}