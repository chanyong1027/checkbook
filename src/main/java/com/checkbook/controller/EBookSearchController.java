package com.checkbook.controller;

import com.checkbook.client.ELibSearchService;
import com.checkbook.dto.EBookSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/ebook")
public class EBookSearchController {

    private final ELibSearchService searchService;

    /**
     * 여러 교보 전자도서관 동시 검색
     * GET /api/ebook/search?q=자바
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam("q") String keyword) {
        long start = System.currentTimeMillis();
        List<EBookSearchResult> results = searchService.search(keyword);
        long elapsed = System.currentTimeMillis() - start;

        return ResponseEntity.ok(Map.of(
                "keyword", keyword,
                "totalCount", results.size(),
                "elapsedMs", elapsed,
                "results", results
        ));
    }

    /**
     * 단일 도서관 검색 (디버깅용)
     * GET /api/ebook/search/single?url=https://duksunguniv.dkyobobook.co.kr&q=자바
     */
    @GetMapping("/search/single")
    public ResponseEntity<Map<String, Object>> searchSingle(
            @RequestParam("url") String baseUrl,
            @RequestParam("q") String keyword) {
        long start = System.currentTimeMillis();
        List<EBookSearchResult> results = searchService.searchSingle(baseUrl, keyword);
        long elapsed = System.currentTimeMillis() - start;

        return ResponseEntity.ok(Map.of(
                "keyword", keyword,
                "library", baseUrl,
                "totalCount", results.size(),
                "elapsedMs", elapsed,
                "results", results
        ));
    }
}
