package com.checkbook.publiclibrary.dto;

public record PublicLibraryInfo(
        String libraryName,
        boolean hasBook,
        boolean loanAvailable,
        String address,
        Double latitude,
        Double longitude,
        Double distance,
        String homepage
) {
}
