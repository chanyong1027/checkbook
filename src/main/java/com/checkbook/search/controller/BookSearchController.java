package com.checkbook.search.controller;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.search.dto.BookCandidateResponse;
import com.checkbook.search.service.BookSearchService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookSearchController {

    private final BookSearchService bookSearchService;

    @GetMapping("/search")
    public ResponseEntity<BookCandidateResponse> searchBooks(
            @RequestParam @NotBlank @Size(max = 200) String q,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size
    ) {
        if (q.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SEARCH_KEYWORD);
        }

        return ResponseEntity.ok(bookSearchService.searchCandidates(q, page, size));
    }
}
