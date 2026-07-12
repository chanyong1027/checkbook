package com.checkbook.featured.snapshot.repository;

import com.checkbook.featured.snapshot.domain.FeaturedSectionSnapshot;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FeaturedSectionSnapshotRepository
        extends JpaRepository<FeaturedSectionSnapshot, FeaturedSectionType> {

    /**
     * SELECT FOR UPDATE — 섹션당 1행인 스냅샷 행을 락으로 사용해 같은 섹션의
     * 동시 갱신(워밍업 @Async ↔ cron, 향후 다중 인스턴스)을 직렬화한다.
     * 락 보유는 Writer의 짧은 DB 트랜잭션 동안뿐 (외부 API 호출은 트랜잭션 밖).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FeaturedSectionSnapshot s where s.sectionType = :sectionType")
    Optional<FeaturedSectionSnapshot> findByIdForUpdate(
            @Param("sectionType") FeaturedSectionType sectionType);
}
