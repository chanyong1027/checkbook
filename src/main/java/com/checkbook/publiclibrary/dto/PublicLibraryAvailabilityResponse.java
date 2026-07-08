package com.checkbook.publiclibrary.dto;

import java.util.List;

public record PublicLibraryAvailabilityResponse(
        List<PublicLibraryInfo> libraries,
        int offset,
        int total,
        Integer nextOffset,
        boolean hasMoreLibraries,
        int failedCount
) {
    public static PublicLibraryAvailabilityResponse from(PublicLibraryAvailabilityPage page) {
        return new PublicLibraryAvailabilityResponse(
                page.libraries(),
                page.offset(),
                page.total(),
                page.nextOffset(),
                page.hasMoreLibraries(),
                page.failedCount());
    }
}
