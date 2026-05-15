package com.checkbook.search.dto;

public record MillieAvailability(
        boolean available,
        String bookSeq,
        String detailUrl,
        Format format
) {

    public enum Format { EBOOK, AUDIOBOOK, EBOOK_AND_AUDIOBOOK }

    public static MillieAvailability unavailable() {
        return new MillieAvailability(false, null, null, null);
    }
}
