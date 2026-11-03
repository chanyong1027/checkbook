package com.checkbook.search.controller;

import com.checkbook.search.dto.BookHint;
import com.checkbook.search.dto.OffStoreResponse;
import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.service.AladinBookService;
import com.checkbook.search.service.SearchService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final AladinBookService aladinBookService;

    /**
     * q는 ISBN(키워드는 400). title·author·publisher·cover는 프런트가 이미 가진 후보 정보(힌트)로 선택 사항이다.
     * 힌트가 무효해도 400을 내지 않는다(서비스가 없는 것으로 처리) — 그래서 여기서 길이 제약을 걸지 않는다.
     */
    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam @NotBlank @Size(max = 200) String q,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) String cover
    ) {
        BookHint hint = title == null && author == null && publisher == null && cover == null
                ? null
                : new BookHint(title, author, publisher, cover);
        return ResponseEntity.ok(searchService.search(q, lat, lon, hint));
    }

    @GetMapping("/off-stores")
    public ResponseEntity<OffStoreResponse> getOffStores(
            @RequestParam @NotBlank @Pattern(regexp = "\\d{13}") String isbn13,
            @RequestParam Double lat,
            @RequestParam Double lon
    ) {
        return ResponseEntity.ok(aladinBookService.getOffStoreList(isbn13, lat, lon));
    }
}
