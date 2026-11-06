package com.checkbook.common.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void 요청마다_12자_16진수_ID를_만들고_끝나면_지운다() throws Exception {
        String first = idSeenInChain(new MockHttpServletRequest(), new MockHttpServletResponse());
        String second = idSeenInChain(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(first).matches("[0-9a-f]{12}");
        assertThat(second).matches("[0-9a-f]{12}").isNotEqualTo(first);
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 처리_중_예외가_나도_지운다() {
        FilterChain failing = (request, response) -> {
            throw new IllegalStateException("처리 실패");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 들어온_헤더는_읽지_않고_응답으로도_내보내지_않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "forged\nline");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String id = idSeenInChain(request, response);

        assertThat(id).matches("[0-9a-f]{12}");
        assertThat(response.getHeaderNames()).isEmpty();
    }

    private String idSeenInChain(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(TraceIdFilter.MDC_KEY)));
        return seen.get();
    }
}
