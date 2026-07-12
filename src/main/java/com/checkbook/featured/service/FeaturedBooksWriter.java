package com.checkbook.featured.service;

import com.checkbook.featured.snapshot.domain.FeaturedBook;
import com.checkbook.featured.snapshot.domain.FeaturedSectionSnapshot;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.FeaturedSource;
import com.checkbook.featured.snapshot.repository.FeaturedBookRepository;
import com.checkbook.featured.snapshot.repository.FeaturedSectionSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class FeaturedBooksWriter {

    private final FeaturedBookRepository bookRepository;
    private final FeaturedSectionSnapshotRepository snapshotRepository;

    @Transactional
    public void replaceSection(
            FeaturedSectionType sectionType,
            FeaturedSource source,
            List<FeaturedBook> books,
            Duration ttl
    ) {
        // FOR UPDATE: 동시 replaceSection(워밍업 ↔ cron)이 같은 섹션의 delete-insert를
        // 겹쳐 실행하면 uq_featured_book_section_rank 충돌 — 스냅샷 행 락으로 직렬화.
        // 진 쪽은 이긴 쪽 커밋 후 자기 데이터로 정상 교체한다 (둘 다 방금 수집한 데이터).
        FeaturedSectionSnapshot snapshot = snapshotRepository.findByIdForUpdate(sectionType)
                .orElseThrow(() -> new IllegalStateException(
                        "featured_section_snapshot 시드 행 없음: " + sectionType));

        bookRepository.deleteBySectionType(sectionType);
        bookRepository.saveAll(books);

        Instant now = Instant.now();
        snapshot.markSuccess(now, now.plus(ttl));
        // source가 바뀌었을 가능성에 대비 (현재는 고정이지만 향후 확장 여지)
        if (snapshot.getSource() != source) {
            // reflection 없이 처리하려면 setter 필요 — 현재 정책상 source는 고정이므로
            // 불일치 시 명시적 예외로 처리. 향후 source 변경이 필요해지면 도메인 메서드 추가.
            throw new IllegalStateException(
                    "source 불일치: snapshot=" + snapshot.getSource() + ", input=" + source);
        }
    }

    @Transactional
    public void markFailed(FeaturedSectionType sectionType, String reason) {
        // replaceSection과 같은 락 — 성공 트랜잭션 도중에 FAILED가 끼어들지 않도록
        FeaturedSectionSnapshot snapshot = snapshotRepository.findByIdForUpdate(sectionType)
                .orElseThrow(() -> new IllegalStateException(
                        "featured_section_snapshot 시드 행 없음: " + sectionType));
        snapshot.markFailed(reason);
    }
}
