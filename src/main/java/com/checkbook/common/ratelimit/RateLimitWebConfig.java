package com.checkbook.common.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {

    // 외부 호출을 부르는 엔드포인트만 센다. DB만 읽는 목록·추천·매장 링크와 actuator는 세지 않는다
    static final String[] LIMITED_PATHS = {
            "/api/search",
            "/api/public-libraries/availability",
            "/api/books/search",
            "/api/elibraries/search"
    };

    // @WebMvcTest 슬라이스는 WebMvcConfigurer만 올리고 RateLimitConfig는 올리지 않는다 — 그때는 등록하지 않는다
    private final ObjectProvider<ClientDailyLimitInterceptor> interceptor;

    public RateLimitWebConfig(ObjectProvider<ClientDailyLimitInterceptor> interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        interceptor.ifAvailable(i -> registry.addInterceptor(i).addPathPatterns(LIMITED_PATHS));
    }
}
