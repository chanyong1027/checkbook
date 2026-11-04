package com.checkbook.publiclibrary.controller;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.publiclibrary.dto.PublicLibraryBatchResponse;
import com.checkbook.publiclibrary.dto.PublicLibraryDirectoryResponse;
import com.checkbook.publiclibrary.service.PublicLibraryBatchService;
import com.checkbook.publiclibrary.service.PublicLibraryDirectoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

import java.util.Arrays;
import java.util.List;

/**
 * D7-1 내 도서관·주변 탐색 API. 옛 화면의 더보기({@code /api/public-libraries/availability})는 11-03에 지웠다.
 */
@Validated
@RestController
@RequestMapping("/api/public-libraries")
@RequiredArgsConstructor
public class PublicLibraryController {

    private final PublicLibraryDirectoryService directoryService;
    private final PublicLibraryBatchService batchService;

    /** 기관명·주소 검색. DB만 읽으므로 IP 하루 한도에 세지 않는다(Nginx 초당 제한만). */
    @GetMapping
    public ResponseEntity<PublicLibraryDirectoryResponse> searchLibraries(
            @RequestParam @NotBlank @Size(max = 200) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return ResponseEntity.ok(directoryService.search(keyword, page, size));
    }

    /**
     * 5곳 배치 조회. 화면이 순서대로 이어 부른다(서버는 상태를 갖지 않는다).
     * NEARBY: lat+lon 또는 referenceLibCode 중 하나 + offset. SAVED: libraryCodes(최대 5곳)만.
     */
    @GetMapping("/availability/batch")
    public ResponseEntity<PublicLibraryBatchResponse> checkBatch(
            @RequestParam @NotBlank @Pattern(regexp = "\\d{13}") String isbn13,
            @RequestParam BatchMode mode,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) @Size(max = 20) String referenceLibCode,
            @RequestParam(required = false) @Min(0) Integer offset,
            @RequestParam(required = false) @Size(max = 200) String libraryCodes,
            // 도서관 사이트 검색 링크를 만들 책 제목(선택). 없으면 링크 없이 지금처럼 응답한다
            @RequestParam(required = false) @Size(max = 200) String title
    ) {
        if (!InputNormalizer.isValidIsbn13(isbn13)) {
            throw new BusinessException(ErrorCode.NON_STANDARD_ISBN);
        }
        boolean hasCoordinates = lat != null || lon != null;
        boolean hasReference = referenceLibCode != null && !referenceLibCode.isBlank();
        PublicLibraryBatchResponse response = switch (mode) {
            case NEARBY -> {
                // 좌표 쌍과 기준 기관 중 정확히 하나. 저장 코드를 섞으면 어느 뜻인지 모호하다
                if (libraryCodes != null || hasCoordinates == hasReference
                        || (hasCoordinates && (lat == null || lon == null))) {
                    throw new BusinessException(ErrorCode.INVALID_PUBLIC_LIBRARY_QUERY);
                }
                int start = offset == null ? 0 : offset;
                yield hasReference
                        ? batchService.nearbyFromLibrary(isbn13, referenceLibCode.strip(), start, title)
                        : batchService.nearby(isbn13, lat, lon, start, title);
            }
            case SAVED -> {
                if (hasCoordinates || hasReference || offset != null) {
                    throw new BusinessException(ErrorCode.INVALID_PUBLIC_LIBRARY_QUERY);
                }
                yield batchService.saved(isbn13, parseCodes(libraryCodes), title);
            }
        };
        return ResponseEntity.ok(response);
    }

    private static List<String> parseCodes(String raw) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.INVALID_PUBLIC_LIBRARY_QUERY);
        }
        List<String> codes = Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(code -> !code.isEmpty())
                .toList();
        if (codes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PUBLIC_LIBRARY_QUERY);
        }
        return codes;
    }

    public enum BatchMode {
        NEARBY,
        SAVED
    }
}
