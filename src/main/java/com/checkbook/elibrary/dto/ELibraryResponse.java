package com.checkbook.elibrary.dto;

import com.checkbook.elibrary.domain.ELibrary;

public record ELibraryResponse(

        Long libraryId,
        String name,
        String vendorType,
        String region
) {

    public static ELibraryResponse from(ELibrary library) {
        return new ELibraryResponse(
                library.getId(),
                library.getName(),
                library.getVendorType().name(),
                library.getRegion()
        );
    }
}
