package checkbook.loadtest.wiremock;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.http.UniformDistribution;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 가짜 외부 API 지연에 실측 꼬리를 붙인다.
 *
 * 왜 필요한가:
 * - 오사카 실측은 몸통이 lognormal에 맞지만 꼬리가 훨씬 두껍다(예: YES24 1,002건 중 4건이 1초 초과).
 *   WireMock의 delayDistribution은 lognormal·uniform 한 종류만 받고 혼합 분포가 없다.
 * - 꼬리는 호출마다 독립으로 뽑는다. 책(ISBN)으로 정하면 같은 책 재시도도 늘 느려지는데,
 *   실제로는 재시도가 대개 빠르게 성공한다.
 *
 * 동작: 매핑의 transformerParameters 중 tailProbability 확률로 지연을 균등 [tailMinMs, tailMaxMs]로 바꾼다.
 * WireMock 3.13은 고정 지연과 분포 지연을 더하므로(Response.Builder.configureDelay) 꼬리일 때는
 * 고정 지연을 지우고 분포만 바꿔 몸통이 더해지지 않게 한다. 꼬리가 아니면 매핑을 그대로 둔다.
 */
public class TailDelayTransformer implements ResponseDefinitionTransformerV2 {

    @Override
    public String getName() {
        return "tail-delay";
    }

    @Override
    public boolean applyGlobally() {
        return false; // 매핑에 "transformers": ["tail-delay"]가 있을 때만
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
        ResponseDefinition definition = serveEvent.getResponseDefinition();
        Parameters parameters = serveEvent.getTransformerParameters();
        double probability = required(parameters, "tailProbability").doubleValue();
        int minMs = required(parameters, "tailMinMs").intValue();
        int maxMs = required(parameters, "tailMaxMs").intValue();
        if (ThreadLocalRandom.current().nextDouble() >= probability) {
            return definition;
        }
        return ResponseDefinitionBuilder.like(definition)
                .withFixedDelay(null)
                .withRandomDelay(new UniformDistribution(minMs, maxMs))
                .build();
    }

    // 값이 빠지면 조용히 몸통만 쓰지 않도록 요청을 실패시킨다(WireMock이 500으로 돌려준다).
    // 메시지는 영어로 둔다 — 500 HTML이 ISO-8859-1이라 한글이 깨진다
    private static Number required(Parameters parameters, String key) {
        Object value = parameters == null ? null : parameters.get(key);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("tail-delay: transformerParameters." + key + " must be a number, got " + value);
        }
        return (Number) value;
    }
}
