package com.checkbook.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 키별 하루 호출 수. KST 날짜가 바뀌면 전부 0부터 다시 센다(YES24·정보나루의 일 한도 경계와 같다).
 * 인메모리라 재시작·블루그린 전환 때 초기화된다(요청 제한 결정 카드 2). 공급자 예산은
 * {@link ExternalCallBudgetSyncer}가 DB와 맞춰 이어서 세고, IP별 한도는 그대로 초기화된다.
 */
public class DailyCounter {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Clock clock;
    private final int maxKeys;
    // 날짜와 그날의 카운터를 한 묶음으로 바꿔 끼운다 — 날짜만 먼저 바뀌고 지난 카운터가 남는 순간이 없게
    private final AtomicReference<Day> current;

    public DailyCounter(Clock clock, int maxKeys) {
        this.clock = clock;
        this.maxKeys = maxKeys;
        this.current = new AtomicReference<>(new Day(today()));
    }

    /** 오늘 사용량이 limit 미만이면 1 올리고 true. 거절한 요청은 세지 않는다. */
    public boolean tryAcquire(String key, int limit) {
        return tryAcquireOn(key, limit) != null;
    }

    /**
     * {@link #tryAcquire}와 같지만 1을 올린 카운터의 날짜를 돌려준다(거절이면 null).
     * 올리기와 날짜를 같은 Day에서 정한다 — 올린 뒤 날짜를 따로 읽으면 그 사이 자정이 지나 다른 날짜가 붙는다.
     */
    public LocalDate tryAcquireOn(String key, int limit) {
        Day day = day();
        AtomicInteger count = counterFor(day, key);
        if (count == null) {
            return day.date; // 키 상한: 세지 않고 통과(fail-open)
        }
        while (true) {
            int used = count.get();
            if (used >= limit) {
                return null;
            }
            if (count.compareAndSet(used, used + 1)) {
                return day.date;
            }
        }
    }

    /** 한도와 상관없이 1 올린다(막지 않고 세기만 하는 호출). */
    public void increment(String key) {
        incrementOn(key);
    }

    /** {@link #increment}와 같지만 1을 올린 카운터의 날짜를 돌려준다. */
    public LocalDate incrementOn(String key) {
        Day day = day();
        AtomicInteger count = counterFor(day, key);
        if (count != null) {
            count.incrementAndGet();
        }
        return day.date;
    }

    /** 더 큰 값일 때만 사용량을 올린다(공급자가 알려 준 실제 사용량 반영). */
    public void raiseTo(String key, int used) {
        AtomicInteger count = counterFor(key);
        if (count != null) {
            count.accumulateAndGet(used, Math::max);
        }
    }

    /**
     * date가 지금 카운터의 날짜일 때만 raiseTo. 날짜 확인과 갱신을 같은 Day 묶음에서 해서,
     * 확인 직후 자정이 지나 전날 값이 새 날 카운터에 들어가는 틈을 없앤다.
     */
    public void raiseToIfDate(String key, int used, LocalDate date) {
        Day day = day();
        if (!day.date.equals(date)) {
            return;
        }
        AtomicInteger count = counterFor(day, key);
        if (count != null) {
            count.accumulateAndGet(used, Math::max);
        }
    }

    public int used(String key) {
        AtomicInteger count = day().counts.get(key);
        return count == null ? 0 : count.get();
    }

    /** 오늘 추적 키 수 상한에 닿아 새 키를 세지 못한 적이 있는지. */
    public boolean overflowed() {
        return day().overflowed.get();
    }

    /** 상한에 처음 닿은 뒤 한 번만 true(하루 단위). 알림·로그를 한 번만 남기는 데 쓴다. */
    public boolean takeOverflowNotice() {
        Day day = day();
        return day.overflowed.get() && day.overflowNoticed.compareAndSet(false, true);
    }

    /**
     * 오늘 이 키를 처음 표시하면 true. 상한에 닿아 표시할 수 없으면 false다 —
     * fail-open으로 true를 돌려주면 '한 번만 남길 로그'가 매번 남는다.
     */
    public boolean markOnce(String key) {
        AtomicInteger count = counterFor(key);
        return count != null && count.compareAndSet(0, 1);
    }

    /** 지금 세고 있는 날짜(KST) */
    public LocalDate currentDate() {
        return day().date;
    }

    public Duration untilReset() {
        ZonedDateTime now = clock.instant().atZone(KST);
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(KST);
        return Duration.between(now, nextMidnight);
    }

    // 상한에 닿으면 새 키는 세지 않는다(null). 막으면 공격 중에 새 정상 사용자가 막히므로 통과시키는 쪽을 고른다
    private AtomicInteger counterFor(String key) {
        return counterFor(day(), key);
    }

    private AtomicInteger counterFor(Day day, String key) {
        AtomicInteger existing = day.counts.get(key);
        if (existing != null) {
            return existing;
        }
        if (day.counts.size() >= maxKeys) {
            day.overflowed.set(true);
            return null;
        }
        return day.counts.computeIfAbsent(key, k -> new AtomicInteger());
    }

    // 앞으로만 바꾼다. 자정 직전에 날짜를 계산하고 멈춘 스레드가 이미 새 날로 바뀐 카운터를 보면
    // 그대로 쓴다(과거 날짜로 교체하면 새 날에 센 사용량이 사라진다). 그 스레드의 1회는 새 날로 세진다
    private Day day() {
        LocalDate today = today();
        while (true) {
            Day day = current.get();
            if (!day.date.isBefore(today)) {
                return day;
            }
            Day next = new Day(today);
            if (current.compareAndSet(day, next)) {
                return next;
            }
        }
    }

    private LocalDate today() {
        // clock.withZone에 기대지 않는다(구현마다 시간대 처리가 다를 수 있다)
        return clock.instant().atZone(KST).toLocalDate();
    }

    private static final class Day {
        final LocalDate date;
        final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        final AtomicBoolean overflowed = new AtomicBoolean();
        final AtomicBoolean overflowNoticed = new AtomicBoolean();

        Day(LocalDate date) {
            this.date = date;
        }
    }
}
