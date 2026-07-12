package com.checkbook.featured.snapshot.repository;

import com.checkbook.featured.snapshot.domain.FeaturedBook;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FeaturedBookRepository extends JpaRepository<FeaturedBook, Long> {

    List<FeaturedBook> findBySectionTypeOrderByRankAsc(FeaturedSectionType sectionType);

    /**
     * 벌크 삭제 필수 — 파생 delete는 REMOVE가 flush까지 지연되는 반면 신규 행은
     * IDENTITY 전략이라 saveAll 시점에 즉시 INSERT되어, 같은 (section_type, rank)의
     * 기존 행과 uq_featured_book_section_rank 충돌이 난다 (replaceSection 경로).
     *
     * 영속성 컨텍스트 계약: 벌크 JPQL은 1차 캐시를 우회하므로, 같은 트랜잭션에서
     * FeaturedBook을 미리 로드해 들고 있는 호출자는 삭제된 행의 stale 참조를 갖게 된다.
     * clearAutomatically는 의도적으로 false — true로 바꾸면 replaceSection이 먼저 로드한
     * snapshot 엔티티가 detach되어 markSuccess의 dirty checking이 조용히 소실된다.
     */
    @Modifying
    @Query("delete from FeaturedBook b where b.sectionType = :sectionType")
    void deleteBySectionType(@Param("sectionType") FeaturedSectionType sectionType);
}
