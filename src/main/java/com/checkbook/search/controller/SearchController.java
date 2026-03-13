package com.checkbook.search.controller;

import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.service.SearchService;
import jakarta.validation.constraints.NotBlank;
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

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam @NotBlank @Size(max = 200) String q,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon
    ) {
        return ResponseEntity.ok(searchService.search(q, lat, lon));
    }
}
