package com.checkbook.elibrary.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ELibClientTest {

    private static final long DEADLINE = 1_000_000_000_000L;

    // 1ms를 넘기면 Jsoup이 읽기 상한을 1 / 2 = 0으로 두는데, 자바에서 0은 '무제한'이다(CodeRabbit #56)
    @Test
    void 남은_시간이_2ms_미만이면_TIMEOUT() {
        assertThatThrownBy(() -> ELibClient.remainingMillis(DEADLINE, DEADLINE - 1_999_999))
                .isInstanceOfSatisfying(ELibraryClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(ELibraryClientException.Kind.TIMEOUT));
    }

    @Test
    void 남은_시간이_2ms면_그대로_넘긴다() {
        assertThat(ELibClient.remainingMillis(DEADLINE, DEADLINE - 2_000_000)).isEqualTo(2);
    }
}
