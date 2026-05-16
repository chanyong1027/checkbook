package com.checkbook.featured.scheduler;

import com.checkbook.featured.service.FeaturedBooksRefresher;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeaturedBooksScheduler {

    private final FeaturedBooksRefresher refresher;

    @Scheduled(cron = "${featured.bestseller.cron:0 0 4 * * MON}", zone = "Asia/Seoul")
    public void refreshBestseller() {
        log.info("스케줄 트리거: BESTSELLER");
        refresher.refreshSection(FeaturedSectionType.BESTSELLER);
    }

    @Scheduled(cron = "${featured.new.cron:0 0 4 * * MON,THU}", zone = "Asia/Seoul")
    public void refreshNew() {
        log.info("스케줄 트리거: NEW");
        refresher.refreshSection(FeaturedSectionType.NEW);
    }

    @Scheduled(cron = "${featured.loan.cron:0 0 4 * * MON}", zone = "Asia/Seoul")
    public void refreshLoan() {
        log.info("스케줄 트리거: LOAN");
        refresher.refreshSection(FeaturedSectionType.LOAN);
    }
}
