package com.checkbook.featured.controller;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.featured.dto.FeaturedBooksResponse;
import com.checkbook.featured.service.FeaturedBooksService;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/featured")
@RequiredArgsConstructor
public class FeaturedBooksController {

    private final FeaturedBooksService service;

    @GetMapping
    public ResponseEntity<FeaturedBooksResponse> getFeatured(
            @RequestParam @NotBlank String type
    ) {
        FeaturedSectionType sectionType = parseType(type);
        return ResponseEntity.ok(service.getSection(sectionType));
    }

    private FeaturedSectionType parseType(String raw) {
        try {
            return FeaturedSectionType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_SEARCH_KEYWORD);
        }
    }
}
