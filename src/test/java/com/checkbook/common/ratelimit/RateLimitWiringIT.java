package com.checkbook.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어느 경로를 세는지 확인한다. 한도 0이면 대상 경로는 컨트롤러에 닿기 전에 429다(외부 호출 없음).
 */
@SpringBootTest(properties = "rate-limit.client.daily-limit=0")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitWiringIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitProperties properties;

    @Test
    void application_yaml의_소문자_공급자_키가_enum으로_바인딩된다() {
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.providers().get(ExternalProvider.YES24).blockAt()).isEqualTo(19000);
        assertThat(properties.providers().get(ExternalProvider.DATANARU).blockAt()).isEqualTo(28500);
        assertThat(properties.providers().get(ExternalProvider.MILLIE).blockAt()).isEqualTo(20000);
        assertThat(properties.providers().get(ExternalProvider.KAKAO).blockAt()).isEqualTo(27000);
        assertThat(properties.elibraryPerLibraryLimit()).isEqualTo(1000);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/search?q=9788936434120",
            "/api/public-libraries/availability?isbn13=9788936434120&lat=37.5&lon=127.0",
            "/api/books/search?q=test",
            "/api/elibraries/search?title=test&libraryIds=1"
    })
    void 외부_호출을_부르는_경로는_센다(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isTooManyRequests());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/elibraries",
            "/api/featured?type=BESTSELLER",
            "/api/off-stores?isbn13=9788936434120&lat=37.5&lon=127.0",
            "/actuator/health"
    })
    void 외부_호출이_없는_경로는_세지_않는다(String path) throws Exception {
        int status = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(429);
    }
}
