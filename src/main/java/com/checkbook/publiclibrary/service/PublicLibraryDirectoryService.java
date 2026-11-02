package com.checkbook.publiclibrary.service;

import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.dto.PublicLibraryDirectoryResponse;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기관 선택용 검색(D7-1). 자체 DB만 읽는다 — 입력할 때마다 정보나루를 부르지 않는다.
 * 정렬은 이름→libCode로 고정해 페이지가 넘어가도 같은 기관이 두 번 나오거나 빠지지 않게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicLibraryDirectoryService {

    private static final Sort ORDER = Sort.by("name", "libCode");

    private final PublicLibraryRepository repository;

    public PublicLibraryDirectoryResponse search(String keyword, int page, int size) {
        String trimmed = keyword.strip();
        Slice<PublicLibrary> slice = repository.findByNameContainingIgnoreCaseOrAddressContainingIgnoreCase(
                trimmed, trimmed, PageRequest.of(page, size, ORDER));
        return new PublicLibraryDirectoryResponse(
                slice.getContent().stream().map(PublicLibraryDirectoryResponse.Item::from).toList(),
                slice.hasNext());
    }
}
