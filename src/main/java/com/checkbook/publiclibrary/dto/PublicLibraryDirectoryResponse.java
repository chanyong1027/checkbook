package com.checkbook.publiclibrary.dto;

import com.checkbook.publiclibrary.domain.PublicLibrary;

import java.util.List;

/** 기관 선택용 검색 결과(자체 DB). 내 도서관 저장과 기준 도서관 선택에 쓴다. */
public record PublicLibraryDirectoryResponse(List<Item> items, boolean hasNext) {

    public record Item(String libCode, String name, String address, double latitude, double longitude) {

        public static Item from(PublicLibrary library) {
            return new Item(library.getLibCode(), library.getName(), library.getAddress(),
                    library.getLat(), library.getLon());
        }
    }
}
