package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryAvailabilitySnapshotRefresher {

    private final DatanaruClient datanaruClient;
    private final LibraryAvailabilitySnapshotPersister persister;
    private final LibraryAvailabilitySnapshotReader reader;

    /**
     * 외부 API 호출 후 persister로 upsert.
     * @Transactional 없음: HTTP 호출(최대 2초) 동안 DB 커넥션 점유 시
     * fan-out 20개 × 2초 = HikariCP 기본 풀(10) 고갈 위험.
     */
    @CircuitBreaker(name = "datanaru", fallbackMethod = "refreshFallback")
    public LibraryAvailabilityResult refresh(String isbn13, String libCode) {
        DatanaruBookExistResult apiResult = datanaruClient.bookExist(isbn13, libCode);
        persister.upsert(isbn13, libCode, apiResult.hasBook(), apiResult.loanAvailable());
        return new LibraryAvailabilityResult(
                libCode,
                apiResult.hasBook(),
                apiResult.loanAvailable(),
                SnapshotSourceStatus.SUCCESS
        );
    }

    /** @CircuitBreaker 폴백: 파라미터 목록 동일 + Throwable 추가, 반환 타입 동일 필수 */
    LibraryAvailabilityResult refreshFallback(String isbn13, String libCode, Throwable t) {
        log.warn("Datanaru 서킷브레이커 폴백: isbn13={}, libCode={}, cause={}",
                isbn13, libCode, t.getMessage());
        return reader.findStale(isbn13, libCode)
                .orElseGet(() -> LibraryAvailabilityResult.failed(libCode));
    }
}
