package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LibraryAvailabilitySnapshotRefresher {

    // 서킷 인스턴스 이름(설정 resilience4j.circuitbreaker.instances.datanaru, 지표·알림의 name 태그)
    static final String CIRCUIT_BREAKER_NAME = "datanaru";

    private final DatanaruClient datanaruClient;
    private final LibraryAvailabilitySnapshotPersister persister;
    private final CircuitBreaker circuitBreaker;
    private final Counter persistSuccess;
    private final Counter persistFailure;

    public LibraryAvailabilitySnapshotRefresher(DatanaruClient datanaruClient, LibraryAvailabilitySnapshotPersister persister,
                                                CircuitBreakerRegistry circuitBreakerRegistry, MeterRegistry meterRegistry) {
        this.datanaruClient = datanaruClient;
        this.persister = persister;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        // 기동 때 0으로 미리 만든다. 실패 순간에 처음 생기면 첫 값이 1이라 increase()가 그 실패를 못 센다
        this.persistSuccess = meterRegistry.counter("library.snapshot.persist", "result", "success");
        this.persistFailure = meterRegistry.counter("library.snapshot.persist", "result", "failure");
    }

    /**
     * 정보나루를 부르고 스냅샷으로 저장한다.
     * <p>
     * 서킷은 정보나루 호출만 감싼다 — 서킷은 그 외부 서비스의 건강만 재고, 저장은 우리 DB 일이다. 전에는 저장까지 서킷 안이라
     * 저장 예외(같은 책 동시 첫 조회의 유일 제약 충돌 등)가 정보나루 실패로 세여 서킷을 열 수 있었고, 이미 받은 결과도 버렸다(A).
     * <p>
     * @Transactional 없음: HTTP 호출(최대 2초) 동안 DB 커넥션 점유 시
     * fan-out 20개 × 2초 = HikariCP 기본 풀(10) 고갈 위험.
     */
    public LibraryAvailabilityResult refresh(String isbn13, String libCode) {
        DatanaruBookExistResult apiResult;
        try {
            apiResult = circuitBreaker.executeSupplier(() -> datanaruClient.bookExist(isbn13, libCode));
        } catch (RuntimeException e) {
            // 만료된 스냅샷(옛 값)을 꺼내지 않고 실패로 돌려준다 — 원천이 이미 전날 기준이라 옛 값은 최소 이틀 전이고,
            // YES24·밀리 서킷도 열리면 바로 실패로 보고한다(D7-0 결정 카드 1). 서킷 열림·예산 소진·호출 실패 모두 여기로 온다.
            // fan-out이 예외를 건너뜀으로 받긴 하지만, 서킷이 열렸을 때 도서관마다 스택 트레이스가 찍히지 않게 한 줄로 남긴다
            log.warn("정보나루 조회 실패 — 이 도서관은 건너뜀: isbn13={}, libCode={}, cause={}", isbn13, libCode, e.toString());
            return LibraryAvailabilityResult.failed(libCode);
        }
        save(isbn13, libCode, apiResult);
        return new LibraryAvailabilityResult(
                libCode,
                apiResult.hasBook(),
                apiResult.loanAvailable(),
                SnapshotSourceStatus.SUCCESS
        );
    }

    /**
     * 저장이 실패해도 정보나루에서 받은 결과는 그대로 쓴다 — 다음 조회가 정보나루를 한 번 더 부를 뿐이다.
     * 실패는 지표로 세어 알림(낮)으로 본다. 커넥션을 못 얻는 경우(트랜잭션 시작 실패)까지 같은 취급이라 RuntimeException으로 받는다
     */
    private void save(String isbn13, String libCode, DatanaruBookExistResult apiResult) {
        try {
            persister.upsert(isbn13, libCode, apiResult.hasBook(), apiResult.loanAvailable());
            persistSuccess.increment();
        } catch (RuntimeException e) {
            persistFailure.increment();
            log.warn("스냅샷 저장 실패 — 받은 결과는 그대로 보여 줌: isbn13={}, libCode={}", isbn13, libCode, e);
        }
    }
}
