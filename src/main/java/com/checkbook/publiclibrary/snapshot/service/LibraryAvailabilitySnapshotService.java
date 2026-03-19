package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LibraryAvailabilitySnapshotService {

    private final LibraryAvailabilitySnapshotReader reader;
    private final LibraryAvailabilitySnapshotRefresher refresher;

    public LibraryAvailabilityResult getAvailability(String isbn13, String libCode) {
        return reader.find(isbn13, libCode)
                .orElseGet(() -> refresher.refresh(isbn13, libCode));
    }
}
