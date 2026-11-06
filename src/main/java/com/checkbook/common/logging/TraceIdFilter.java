package com.checkbook.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 요청마다 ID를 만들어 MDC에 넣는다. 이 요청의 로그(풀로 넘긴 작업 포함, AsyncSubmit)를 Loki에서 이 ID로 묶어 본다.
 * 들어온 헤더는 읽지 않는다(로그 위조 경로를 만들지 않으려고). 응답으로도 내보내지 않는다.
 * 결정: notes/decisions/2026-11-06-traceId-결정카드.md
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "traceId";
    private static final int ID_LENGTH = 12;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        MDC.put(MDC_KEY, newId());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // 요청 스레드도 다음 요청이 다시 쓴다
        }
    }

    // UUID 앞 12자(48비트 난수). 하이픈을 먼저 빼야 12자가 모두 16진수다
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, ID_LENGTH);
    }
}
