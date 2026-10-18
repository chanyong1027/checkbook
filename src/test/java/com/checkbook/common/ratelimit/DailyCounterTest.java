package com.checkbook.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DailyCounterTest {

    // 2026-10-18 23:59:00 KST
    private static final Instant BEFORE_MIDNIGHT_KST = Instant.parse("2026-10-18T14:59:00Z");

    private final MutableClock clock = new MutableClock(BEFORE_MIDNIGHT_KST);

    @Test
    void 한도까지만_허용하고_거절한_요청은_세지_않는다() {
        DailyCounter counter = new DailyCounter(clock, 100);

        assertThat(counter.tryAcquire("a", 2)).isTrue();
        assertThat(counter.tryAcquire("a", 2)).isTrue();
        assertThat(counter.tryAcquire("a", 2)).isFalse();
        assertThat(counter.tryAcquire("a", 2)).isFalse();

        assertThat(counter.used("a")).isEqualTo(2);
    }

    @Test
    void 키마다_따로_센다() {
        DailyCounter counter = new DailyCounter(clock, 100);

        counter.tryAcquire("a", 1);

        assertThat(counter.tryAcquire("b", 1)).isTrue();
        assertThat(counter.used("a")).isEqualTo(1);
        assertThat(counter.used("b")).isEqualTo(1);
    }

    @Test
    void KST_자정이_지나면_모두_0부터_다시_센다() {
        DailyCounter counter = new DailyCounter(clock, 100);
        counter.tryAcquire("a", 1);
        assertThat(counter.tryAcquire("a", 1)).isFalse();

        clock.advance(Duration.ofMinutes(1)); // 10-19 00:00 KST

        assertThat(counter.used("a")).isZero();
        assertThat(counter.tryAcquire("a", 1)).isTrue();
    }

    @Test
    void UTC_자정은_날짜_경계가_아니다() {
        // 2026-10-18 23:59 UTC = 10-19 08:59 KST → 1분 뒤에도 같은 KST 날짜
        MutableClock utcClock = new MutableClock(Instant.parse("2026-10-18T23:59:00Z"));
        DailyCounter counter = new DailyCounter(utcClock, 100);
        counter.tryAcquire("a", 1);

        utcClock.advance(Duration.ofMinutes(1));

        assertThat(counter.tryAcquire("a", 1)).isFalse();
    }

    @Test
    void 다음_KST_자정까지_남은_시간() {
        DailyCounter counter = new DailyCounter(clock, 100);

        assertThat(counter.untilReset()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void 추적_키_수가_상한이면_새_키는_세지_않고_통과시킨다() {
        DailyCounter counter = new DailyCounter(clock, 2);
        counter.tryAcquire("a", 1);
        counter.tryAcquire("b", 1);

        // 상한을 넘는 새 키는 막지 않는다(fail-open) — 메모리를 지키되 정상 사용자를 막지 않는다
        assertThat(counter.tryAcquire("c", 1)).isTrue();
        assertThat(counter.tryAcquire("c", 1)).isTrue();
        assertThat(counter.used("c")).isZero();
        assertThat(counter.overflowed()).isTrue();
        // 이미 있던 키는 계속 센다
        assertThat(counter.tryAcquire("a", 1)).isFalse();
    }

    @Test
    void raiseTo는_더_큰_값으로만_올린다() {
        DailyCounter counter = new DailyCounter(clock, 100);
        for (int i = 0; i < 6; i++) {
            counter.tryAcquire("a", 10);
        }

        counter.raiseTo("a", 5);
        assertThat(counter.used("a")).isEqualTo(6);

        counter.raiseTo("a", 7);
        assertThat(counter.used("a")).isEqualTo(7);
    }

    @Test
    void markOnce는_하루_한_번만_true이고_상한이면_false() {
        DailyCounter counter = new DailyCounter(clock, 2);

        assertThat(counter.markOnce("a")).isTrue();
        assertThat(counter.markOnce("a")).isFalse();
        counter.increment("b");
        // 상한에 닿으면 표시할 수 없으니 '처음'이라고 하지 않는다(로그 폭주 방지, 리뷰 F3)
        assertThat(counter.markOnce("c")).isFalse();
        assertThat(counter.markOnce("c")).isFalse();

        clock.advance(Duration.ofMinutes(1));
        assertThat(counter.markOnce("a")).isTrue();
    }

    @Test
    void 상한_도달_알림은_하루_한_번() {
        DailyCounter counter = new DailyCounter(clock, 1);
        counter.increment("a");
        assertThat(counter.takeOverflowNotice()).isFalse();

        counter.increment("b");

        assertThat(counter.takeOverflowNotice()).isTrue();
        assertThat(counter.takeOverflowNotice()).isFalse();
    }

    @Test
    void raiseToIfDate는_그_날짜의_카운터일_때만_올린다() {
        DailyCounter counter = new DailyCounter(clock, 10);
        java.time.LocalDate today = BEFORE_MIDNIGHT_KST.atZone(DailyCounter.KST).toLocalDate();

        counter.raiseToIfDate("a", 7, today.minusDays(1));
        assertThat(counter.used("a")).isZero();

        counter.raiseToIfDate("a", 7, today);
        assertThat(counter.used("a")).isEqualTo(7);
    }

    @Test
    void 자정_직전에_날짜를_읽은_스레드가_새_날_카운터를_과거로_되돌리지_않는다() throws Exception {
        // 스레드 A는 23:59:59에 날짜를 계산한 뒤 멈추고, 그사이 B가 00:00에 새 날로 바꿔 1을 센다.
        // A가 재개해 전날 Day로 교체하면 B의 사용량이 사라져 한도 1인데 두 번 허용된다(외부 리뷰 P2)
        Instant beforeMidnight = Instant.parse("2026-10-18T14:59:59Z"); // 23:59:59 KST
        Instant afterMidnight = Instant.parse("2026-10-18T15:00:01Z");  // 00:00:01 KST
        CountDownLatch aInsideClock = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        Thread[] slowThread = new Thread[1];
        Clock clock = new Clock() {
            @Override
            public Instant instant() {
                if (Thread.currentThread() == slowThread[0]) {
                    aInsideClock.countDown();
                    try {
                        releaseA.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return beforeMidnight;
                }
                return afterMidnight;
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
        DailyCounter counter = new DailyCounter(clock, 100);

        Thread a = new Thread(() -> counter.tryAcquire("other", 10));
        slowThread[0] = a;
        a.start();
        assertThat(aInsideClock.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(counter.tryAcquire("k", 1)).isTrue(); // B: 새 날 첫 허용
        releaseA.countDown();
        a.join(5000);

        assertThat(counter.used("k")).isEqualTo(1);
        assertThat(counter.tryAcquire("k", 1)).isFalse();
    }

    @Test
    void tryAcquireOn과_incrementOn은_올린_카운터의_날짜를_돌려준다() {
        // 증가분에 붙일 날짜를 올리기와 같은 Day에서 정한다(CodeRabbit: 올린 뒤 따로 읽으면 자정에 다른 날짜가 붙음)
        DailyCounter counter = new DailyCounter(clock, 100);
        LocalDate today = BEFORE_MIDNIGHT_KST.atZone(DailyCounter.KST).toLocalDate();

        assertThat(counter.tryAcquireOn("a", 1)).isEqualTo(today);
        assertThat(counter.tryAcquireOn("a", 1)).isNull();
        assertThat(counter.incrementOn("b")).isEqualTo(today);

        clock.advance(Duration.ofMinutes(1));
        assertThat(counter.incrementOn("b")).isEqualTo(today.plusDays(1));
    }

    @Test
    void increment는_한도와_상관없이_센다() {
        DailyCounter counter = new DailyCounter(clock, 100);

        counter.increment("a");
        counter.increment("a");

        assertThat(counter.used("a")).isEqualTo(2);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
