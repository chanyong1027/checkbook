package com.checkbook.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동기화 전용 실행기를 빈으로 만들면 Boot 기본 스케줄러가 물러나(@ConditionalOnMissingBean) 추천 배치·스냅샷 정리까지
 * 그 스레드로 몰린다. 다른 테스트는 그래도 통과하므로 여기서 막는다: Boot 기본 스케줄러가 유일한 TaskScheduler로 남아 있어야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgetSyncSchedulerIsolationIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void Boot_기본_스케줄러가_그대로_있고_동기화_실행기는_빈이_아니다() {
        assertThat(context.getBeansOfType(TaskScheduler.class)).hasSize(1);
        TaskScheduler scheduler = context.getBean(TaskScheduler.class);
        assertThat(scheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        assertThat(((ThreadPoolTaskScheduler) scheduler).getThreadNamePrefix()).isEqualTo("scheduling-");
        assertThat(context.getBeansOfType(ScheduledExecutorService.class)).isEmpty();
    }
}
