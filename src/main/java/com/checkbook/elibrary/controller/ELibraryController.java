package com.checkbook.elibrary.controller;

import com.checkbook.elibrary.dto.ELibraryResponse;
import com.checkbook.elibrary.dto.ELibrarySearchResponse;
import com.checkbook.elibrary.service.ELibrarySearchService;
import com.checkbook.elibrary.service.ELibraryService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/elibraries")
public class ELibraryController {

    private final ELibraryService eLibraryService;
    private final ELibrarySearchService eLibrarySearchService;

    @GetMapping
    public ResponseEntity<List<ELibraryResponse>> readLibraries(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String vendorType,
            @RequestParam(required = false) String keyword
    ) {
        return ResponseEntity.ok(
                eLibraryService.readLibraries(region, vendorType, keyword)
        );
    }

    @GetMapping("/search")
    public ResponseEntity<ELibrarySearchResponse> searchLibraries(
            @RequestParam("query") @NotBlank @Size(max = 200) String query,
            @RequestParam("libraryIds") @NotBlank String libraryIds,
            @RequestParam(required = false) @Size(max = 200) String fallbackKeyword
    ) {
        return ResponseEntity.ok(
                eLibrarySearchService.search(query, libraryIds, fallbackKeyword)
        );
    }
}
