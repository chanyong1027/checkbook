package com.checkbook.elibrary.controller;

import com.checkbook.elibrary.dto.ELibraryResponse;
import com.checkbook.elibrary.service.ELibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/elibraries")
public class ELibraryController {

    private final ELibraryService eLibraryService;

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
}
