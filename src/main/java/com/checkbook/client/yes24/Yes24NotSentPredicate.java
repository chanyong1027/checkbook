package com.checkbook.client.yes24;

import java.util.function.Predicate;

/**
 * 우리가 YES24로 보내지 않은 호출(선제한 거절, 일일 중단, 하루 예산 차단선)인가. 서킷이 이것을 무시한다 —
 * YES24 건강과 무관해 실패로 세면 서킷이 오판하고, 성공으로 세면 실패율이 묽어진다(결정 카드 규칙 3).
 * application.yaml의 {@code ignore-exception-predicate}가 클래스 이름으로 만들므로 인자 없는 생성자가 있어야 한다.
 */
public class Yes24NotSentPredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        return throwable instanceof Yes24Exception e && !e.sent();
    }
}
