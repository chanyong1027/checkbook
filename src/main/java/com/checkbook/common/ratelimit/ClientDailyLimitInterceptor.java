package com.checkbook.common.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.regex.Pattern;

/**
 * 외부 호출을 부르는 엔드포인트의 요청 수를 IP별로 하루 단위로 센다. 한 사람이 공급자 하루 한도를
 * 혼자 다 써서 그날 다른 사용자 전부의 결과가 나빠지는 것을 막는다(결정 카드 1).
 * Nginx limit_req는 속도만 제한하고 하루 총량은 제한하지 않는다.
 *
 * <p>IP는 {@code request.getRemoteAddr()}다. 앱은 X-Forwarded-For를 믿고(forward-headers-strategy: framework),
 * Nginx가 그 값을 실제 접속 IP로 덮어쓴다(deploy/nginx/proxy-app.conf). 앱 포트는 127.0.0.1에만 열려 있다.
 */
@Slf4j
public class ClientDailyLimitInterceptor implements HandlerInterceptor {

    // IP 문자 표기만 InetAddress로 넘긴다 — 호스트 이름이면 DNS 조회가 일어난다.
    // IPv4는 점 네 개짜리 숫자만, IPv6는 ':'가 있어야 한다(호스트 이름에는 ':'가 올 수 없다)
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

    private final boolean enabled;
    private final int dailyLimit;
    private final DailyCounter counter;
    // IP별 "그날 첫 거절" 표시. IP 카운터와 상한을 나눠 쓰지 않게 따로 둔다
    private final DailyCounter firstRejects;
    private final Counter rejectedCounter;
    private final Counter overflowCounter;

    public ClientDailyLimitInterceptor(RateLimitProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.enabled = properties.enabled();
        this.dailyLimit = properties.client().dailyLimit();
        this.counter = new DailyCounter(clock, properties.client().maxTrackedClients());
        this.firstRejects = new DailyCounter(clock, properties.client().maxTrackedClients());
        this.rejectedCounter = Counter.builder("ratelimit.client.rejected")
                .description("사용자(IP) 하루 요청 한도 초과로 거절한 요청 수")
                .register(meterRegistry);
        this.overflowCounter = Counter.builder("ratelimit.client.overflow")
                .description("하루 추적 IP 수 상한에 닿은 날의 수(닿으면 새 IP는 세지 않음)")
                .register(meterRegistry);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled) {
            return true;
        }
        String key = clientKey(request.getRemoteAddr());
        boolean allowed = counter.tryAcquire(key, dailyLimit);
        if (counter.takeOverflowNotice()) {
            // 이때부터 오늘 새 IP는 세지 않는다(fail-open). 공급자 예산이 2차로 막는다
            overflowCounter.increment();
            log.warn("오늘 추적 IP 수 상한에 닿음 — 새 IP는 자정까지 하루 한도를 세지 않음");
        }
        if (allowed) {
            return true;
        }
        rejectedCounter.increment();
        if (firstRejects.markOnce(key)) {
            log.warn("사용자 하루 요청 한도({}) 초과: client={}, path={}", dailyLimit, masked(key), request.getRequestURI());
        }
        throw new ClientDailyLimitExceededException(counter.untilReset());
    }

    /** IPv4는 주소 그대로, IPv6는 앞 64비트(한 가입자가 보통 /64를 받는다 — 주소를 바꿔 가며 피하는 것 차단). */
    static String clientKey(String remoteAddr) {
        if (remoteAddr == null
                || !(IPV4_LITERAL.matcher(remoteAddr).matches() || IPV6_LITERAL.matcher(remoteAddr).matches())) {
            return String.valueOf(remoteAddr);
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            if (address instanceof Inet4Address) {
                // ::ffff:a.b.c.d 형태도 여기로 온다(JDK가 IPv4로 바꿔 준다)
                return address.getHostAddress();
            }
            if (address instanceof Inet6Address) {
                byte[] bytes = address.getAddress();
                StringBuilder prefix = new StringBuilder();
                for (int i = 0; i < 8; i += 2) {
                    int group = ((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff);
                    prefix.append(Integer.toHexString(group)).append(':');
                }
                return prefix.append(":/64").toString();
            }
        } catch (UnknownHostException ignored) {
            // 형식만 맞고 주소가 아닌 값 — 그대로 키로 쓴다
        }
        return remoteAddr;
    }

    /** 로그에 남길 때 끝 단위를 가린다. */
    static String masked(String key) {
        if (key.endsWith("::/64")) {
            String prefix = key.substring(0, key.length() - "::/64".length());
            int last = prefix.lastIndexOf(':');
            return last < 0 ? "x::/64" : prefix.substring(0, last) + ":x::/64";
        }
        int lastDot = key.lastIndexOf('.');
        return lastDot < 0 ? "x" : key.substring(0, lastDot) + ".x";
    }
}
