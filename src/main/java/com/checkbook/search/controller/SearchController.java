package com.checkbook.search.controller;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.publiclibrary.dto.PublicLibraryAvailabilityResponse;
import com.checkbook.publiclibrary.service.PublicLibraryAvailabilityService;
import com.checkbook.search.dto.BookHint;
import com.checkbook.search.dto.OffStoreResponse;
import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.service.AladinBookService;
import com.checkbook.search.service.SearchService;
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

@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final AladinBookService aladinBookService;
    private final PublicLibraryAvailabilityService publicLibraryAvailabilityService;

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

    @GetMapping("/public-libraries/availability")
    public ResponseEntity<PublicLibraryAvailabilityResponse> getPublicLibraryAvailability(
            @RequestParam @NotBlank @Pattern(regexp = "\\d{13}") String isbn13,
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        // 숫자 13자리여도 978/979·체크섬이 맞지 않으면 도서관 조회가 무의미하다(F1). 정상 화면은 상세 응답이
        // 이미 SKIPPED라 더보기를 보이지 않으므로, 이 검사는 직접 호출 방어용
        if (!InputNormalizer.isValidIsbn13(isbn13)) {
            throw new BusinessException(ErrorCode.NON_STANDARD_ISBN);
        }
        return ResponseEntity.ok(PublicLibraryAvailabilityResponse.from(
                publicLibraryAvailabilityService.fetch(isbn13, lat, lon, offset)));
    }
}
