package com.checkbook.common.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 로그 형식을 지킨다. logging.pattern.correlation은 Boot 문서가 트레이싱 맥락에서만 설명하는 사용법이라 시험으로 고정한다.
 * 한도 0이면 요청이 인터셉터에서 거절되어 로그 한 줄을 남긴다(외부 호출 없음).
 */
@SpringBootTest(properties = "rate-limit.client.daily-limit=0")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
class TraceIdLoggingIT {

    private static final Pattern TRACE_ID = Pattern.compile("\\[traceId=([0-9a-f]{12})\\] ");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 인터셉터가_거절한_요청의_로그에도_ID가_붙고_레벨_추출이_그대로이며_응답에는_없다(CapturedOutput output) throws Exception {
        // IP별 첫 거절만 로그를 남기므로 다른 시험과 겹치지 않는 주소를 쓴다
        MockHttpServletResponse response = mockMvc.perform(get("/api/search").param("q", "9788936434120").with(request -> {
                    request.setRemoteAddr("203.0.113.77");
                    return request;
                }))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse();

        String line = lineContaining(output, "사용자 하루 요청 한도");
        Matcher traceId = TRACE_ID.matcher(line);
        assertThat(traceId.find()).as(line).isTrue();
        assertThat(alloyLevelOf(line)).isEqualTo("WARN");

        String id = traceId.group(1);
        assertThat(response.getContentAsString()).doesNotContain(id);
        assertThat(response.getHeaderNames()).allSatisfy(
                name -> assertThat(response.getHeaders(name)).noneMatch(value -> value.contains(id)));
    }

    @Test
    void 요청_밖에서_찍은_로그는_ID_칸이_비어_있다(CapturedOutput output) throws Exception {
        LoggerFactory.getLogger(TraceIdLoggingIT.class).error("요청 밖 로그");

        String line = lineContaining(output, "요청 밖 로그");
        assertThat(line).contains("[traceId=] ");
        assertThat(alloyLevelOf(line)).isEqualTo("ERROR");
    }

    // 실제 수집 설정의 식을 읽어 돌린다. 어긋나면 level 라벨이 빠져 ERROR 로그 알림이 조용히 멈춘다
    private static String alloyLevelOf(String line) throws IOException {
        Matcher expression = Pattern.compile("expression = \"(\\^[^\"]*\\?P<level>[^\"]*)\"")
                .matcher(Files.readString(Path.of("deploy/alloy/config.alloy")));
        assertThat(expression.find()).as("config.alloy에서 level 추출 식을 못 찾음").isTrue();
        String javaRegex = expression.group(1).replace("\\\\", "\\").replace("(?P<", "(?<");

        Matcher level = Pattern.compile(javaRegex).matcher(line);
        assertThat(level.find()).as(line).isTrue();
        return level.group("level");
    }

    private static String lineContaining(CapturedOutput output, String text) {
        return output.getOut().lines().filter(line -> line.contains(text)).findFirst()
                .orElseThrow(() -> new AssertionError("로그 줄 없음: " + text + "\n" + output.getOut()));
    }
}
