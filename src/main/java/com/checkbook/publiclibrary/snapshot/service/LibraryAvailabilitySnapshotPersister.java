package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.repository.LibraryAvailabilitySnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
public class LibraryAvailabilitySnapshotPersister {

    static final long TTL_HOURS = 24;

    private final LibraryAvailabilitySnapshotRepository repository;

    /**
     * 전에는 "찾고 없으면 save"였다 — 같은 책·같은 도서관의 첫 조회가 겹치면 둘 다 '없음'을 읽고 둘 다 넣어
     * 한쪽이 유일 제약(23505)에 걸렸다. 같은 트랜잭션에서 잡고 이어 가거나(스프링·Hibernate·PostgreSQL이 각각 막음)
     * 찾을 때 잠그는 것(없는 행은 못 잠금)으로는 못 고쳐 DB 한 문장(ON CONFLICT)으로 바꿨다(T4 결정 카드 7, 재현 시험
     * LibraryAvailabilitySnapshotPersisterRaceTest)
     */
    @Transactional
    public void upsert(String isbn13, String libCode, boolean hasBook, boolean loanAvailable) {
        Instant now = Instant.now();
        repository.upsert(isbn13, libCode, hasBook, loanAvailable, SnapshotSourceStatus.SUCCESS.name(),
                now, now.plus(TTL_HOURS, ChronoUnit.HOURS));
    }
}
