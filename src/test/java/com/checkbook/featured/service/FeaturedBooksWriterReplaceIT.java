package com.checkbook.featured.service;

import com.checkbook.featured.snapshot.domain.FeaturedBook;
import com.checkbook.featured.snapshot.domain.FeaturedSectionSnapshot;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.FeaturedSource;
import com.checkbook.featured.snapshot.domain.SnapshotStatus;
import com.checkbook.featured.snapshot.repository.FeaturedBookRepository;
import com.checkbook.featured.snapshot.repository.FeaturedSectionSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 실 DB(H2)로 replaceSection의 "교체" 경로를 검증한다.
 *
 * 기존 FeaturedBooksWriterTest(Mockito)는 delete/saveAll 호출 여부만 검증해서
 * JPA 실행 순서 문제를 잡지 못했다 — 운영에서 첫 적재는 성공하고 이후 모든 교체가
 * uq_featured_book_section_rank 위반으로 실패한 장애(2026-05~07)의 재현 테스트.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(FeaturedBooksWriter.class)
class FeaturedBooksWriterReplaceIT {

    @Autowired FeaturedBooksWriter writer;
    @Autowired FeaturedBookRepository bookRepository;
    @Autowired FeaturedSectionSnapshotRepository snapshotRepository;
    @Autowired TestEntityManager entityManager;

    @BeforeEach
    void seedSnapshotRow() {
        // 운영에서는 Flyway 시드가 담당하는 행 (테스트 프로파일은 flyway off)
        snapshotRepository.save(FeaturedSectionSnapshot.builder()
                .sectionType(FeaturedSectionType.BESTSELLER)
                .source(FeaturedSource.ALADIN)
                .status(SnapshotStatus.NEVER_FETCHED)
                .build());
    }

    @Test
    void replaceSection_calledTwice_replacesBooksWithoutUniqueViolation() {
        writer.replaceSection(FeaturedSectionType.BESTSELLER, FeaturedSource.ALADIN,
                books("first"), Duration.ofDays(7));

        // 같은 (section_type, rank) 조합으로 교체 — 운영의 매주 cron 갱신과 동일한 경로
        assertThatCode(() ->
                writer.replaceSection(FeaturedSectionType.BESTSELLER, FeaturedSource.ALADIN,
                        books("second"), Duration.ofDays(7))
        ).doesNotThrowAnyException();

        List<FeaturedBook> remaining =
                bookRepository.findBySectionTypeOrderByRankAsc(FeaturedSectionType.BESTSELLER);
        assertThat(remaining).hasSize(3);
        assertThat(remaining).allMatch(b -> b.getTitle().startsWith("second"));

        // 스냅샷 dirty checking이 벌크 삭제(@Modifying) 이후에도 살아있는지 —
        // clearAutomatically=true가 추가되면 markSuccess 갱신이 소실되어 여기서 잡힌다
        FeaturedSectionSnapshot snapshot = snapshotRepository
                .findById(FeaturedSectionType.BESTSELLER).orElseThrow();
        assertThat(snapshot.getStatus()).isEqualTo(SnapshotStatus.SUCCESS);
        assertThat(snapshot.getLastFetchedAt()).isNotNull();
        assertThat(snapshot.getExpiresAt()).isAfter(snapshot.getLastFetchedAt());
    }

    @Test
    void replaceSection_newSource_replacesBooksAndPersistsSource() {
        writer.replaceSection(FeaturedSectionType.BESTSELLER, FeaturedSource.ALADIN,
                books("aladin"), Duration.ofDays(7));

        // 공급자 전환 후 첫 교체 — 시드·기존 행의 source(ALADIN)와 달라도 교체가 커밋돼야 한다
        writer.replaceSection(FeaturedSectionType.BESTSELLER, FeaturedSource.YES24,
                books("yes24"), Duration.ofDays(7));
        // 테스트 전체가 한 트랜잭션이라 1차 캐시가 같은 객체를 돌려준다 — DB에 쓴 값을 다시 읽어 확인
        entityManager.flush();
        entityManager.clear();

        assertThat(bookRepository.findBySectionTypeOrderByRankAsc(FeaturedSectionType.BESTSELLER))
                .hasSize(3)
                .allMatch(b -> b.getTitle().startsWith("yes24"));
        FeaturedSectionSnapshot snapshot = snapshotRepository
                .findById(FeaturedSectionType.BESTSELLER).orElseThrow();
        assertThat(snapshot.getSource()).isEqualTo(FeaturedSource.YES24);
        assertThat(snapshot.getStatus()).isEqualTo(SnapshotStatus.SUCCESS);
    }

    private List<FeaturedBook> books(String titlePrefix) {
        return List.of(book(titlePrefix, 1), book(titlePrefix, 2), book(titlePrefix, 3));
    }

    private FeaturedBook book(String titlePrefix, int rank) {
        return FeaturedBook.builder()
                .sectionType(FeaturedSectionType.BESTSELLER)
                .rank(rank)
                .isbn13("978000000000" + rank)
                .title(titlePrefix + " " + rank)
                .build();
    }
}
