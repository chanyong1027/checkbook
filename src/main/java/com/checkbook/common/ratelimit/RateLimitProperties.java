package com.checkbook.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * 요청 제한 설정 (결정 문서 notes/decisions/2026-10-18-요청제한-결정카드.md).
 *
 * 값이 빠진 프로필도 안전하게 뜨도록 기본값을 둔다(빠지면 제한이 꺼지거나 전자도서관이 전부 막히는 일 방지).
 *
 * @param enabled                 false면 IP 제한·공급자 예산 모두 막지 않는다(부하 측정용). 공급자 예산은 세기는 하고, IP 제한은 세지도 않는다
 * @param client                  IP별 하루 요청 한도
 * @param providers               공급자별 하루 한도와 차단선. 없는 공급자는 세기만 한다
 * @param elibraryPerLibraryLimit 전자도서관 사이트 하나당 하루 수집 상한
 */
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Client client,
        Map<ExternalProvider, Provider> providers,
        @DefaultValue("1000") int elibraryPerLibraryLimit
) {

    public RateLimitProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    /**
     * @param dailyLimit        IP(IPv6는 /64) 하나가 하루에 보낼 수 있는 대상 엔드포인트 요청 수
     * @param maxTrackedClients 하루에 추적하는 IP 수 상한(메모리 보호). 넘으면 새 IP는 세지 않는다.
     *                          거절 로그 표시는 따로 센다(같은 상한을 나눠 쓰지 않음)
     */
    public record Client(@DefaultValue("500") int dailyLimit, @DefaultValue("200000") int maxTrackedClients) {
    }

    /**
     * @param dailyLimit 공급자의 실제 하루 한도. 80% 경고와 잔량 헤더 환산의 기준
     * @param blockAt    이 사용량부터 사용자 요청의 호출을 막는다(배치 몫·재시작 오차 여유). 빠지면 dailyLimit
     */
    public record Provider(int dailyLimit, @DefaultValue("-1") int blockAt) {

        public Provider {
            if (dailyLimit <= 0) {
                throw new IllegalArgumentException("rate-limit 공급자 daily-limit은 1 이상이어야 한다: " + dailyLimit);
            }
            // 빠진 값이 0이 되면 그 공급자 호출이 전부 막힌다 — 빠지면 하루 한도에서 막는다
            if (blockAt < 0) {
                blockAt = dailyLimit;
            }
            if (blockAt > dailyLimit) {
                throw new IllegalArgumentException(
                        "rate-limit 공급자 block-at(" + blockAt + ")이 daily-limit(" + dailyLimit + ")보다 크다");
            }
        }
    }
}
