// C:\LocBootProject\workspace\MyBaseLink\src\main\java\com\mybaselink\app\service\NewsDisclosureService.java
package com.mybaselink.app.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class NewsDisclosureService {

    /**
     * 종목에 대한 뉴스 및 공시 정보를 비동기적으로 가져옵니다.
     * 캐싱을 적용하여 동일 종목에 대한 반복 요청 시 성능을 향상시킵니다.
     *
     * @param symbol 종목 코드
     * @return 뉴스 및 공시 정보를 담은 CompletableFuture
     */
    @Async
    @Cacheable(value = "newsDisclosureCache", key = "#symbol")
    public CompletableFuture<List<Map<String, String>>> getNewsAndDisclosures(String symbol) {
        // 실제로는 외부 API(예: DART, 네이버 금융)를 호출하거나 웹 스크래핑하는 로직이 들어갑니다.
        // 현재는 예시로 가짜 데이터를 반환합니다.
        List<Map<String, String>> dummyData = List.of(
            Map.of("date", "2025-10-22", "title", "뉴스 제목 1", "source", "뉴스 통신사"),
            Map.of("date", "2025-10-21", "title", "공시 제목 1", "source", "금융감독원(DART)")
        );
        return CompletableFuture.completedFuture(dummyData);
    }
}
