package com.checkbook.publiclibrary.snapshot.repository;

import com.checkbook.publiclibrary.snapshot.domain.LibraryAvailabilitySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface LibraryAvailabilitySnapshotRepository extends JpaRepository<LibraryAvailabilitySnapshot, Long> {

    Optional<LibraryAvailabilitySnapshot> findByIsbn13AndLibCode(String isbn13, String libCode);

    /**
     * 없으면 넣고, 있으면 새 값으로 덮어쓴다 — 한 문장이라 같은 키가 동시에 와도 넣기와 갱신 중 하나로 끝난다.
     * 같은 키를 다른 트랜잭션이 넣는 중이면 그 트랜잭션이 끝날 때까지 기다렸다가 갱신한다(유일 제약 충돌 없음).
     * PostgreSQL 전용 문법이라 H2 시험에서는 돌지 않는다(PostgreSQL 시험 묶음에서 확인)
     */
    @Modifying
    @Query(value = """
            INSERT INTO library_availability_snapshot
                (isbn13, lib_code, has_book, loan_available, source_status, last_fetched_at, expires_at)
            VALUES (:isbn13, :libCode, :hasBook, :loanAvailable, :sourceStatus, :lastFetchedAt, :expiresAt)
            ON CONFLICT (isbn13, lib_code) DO UPDATE SET
                has_book = EXCLUDED.has_book,
                loan_available = EXCLUDED.loan_available,
                source_status = EXCLUDED.source_status,
                last_fetched_at = EXCLUDED.last_fetched_at,
                expires_at = EXCLUDED.expires_at
            """, nativeQuery = true)
    void upsert(@Param("isbn13") String isbn13,
                @Param("libCode") String libCode,
                @Param("hasBook") boolean hasBook,
                @Param("loanAvailable") boolean loanAvailable,
                @Param("sourceStatus") String sourceStatus,
                @Param("lastFetchedAt") Instant lastFetchedAt,
                @Param("expiresAt") Instant expiresAt);

    @Transactional
    void deleteByExpiresAtBefore(Instant cutoff);
}
