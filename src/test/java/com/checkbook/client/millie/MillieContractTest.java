package com.checkbook.client.millie;

import com.checkbook.client.millie.dto.MillieBookItem;
import com.checkbook.common.matcher.BookMatcher;
import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.search.dto.IdentifiedBook;
import com.checkbook.search.dto.MillieAvailability;
import com.checkbook.search.service.MillieBookMatcher;
import com.checkbook.search.service.MillieBookService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 밀리 계약 확인 — 실제 밀리를 부른다. 기본 test에서 빠지고 주 1회 GitHub Actions(contract-check.yml)가 돌린다.
 * 실패하면 GitHub가 메일을 보내 그 자체가 알림이 된다.
 * <p>
 * 앱과 같은 설정(application.yaml)·같은 클라이언트·같은 매처로 고정 책 한 권을 끝까지 확인한다.
 * <ol>
 *   <li>검색 API가 책 목록을 주고, 매칭에 쓰는 필드가 있다</li>
 *   <li>운영 서비스(MillieBookService)가 그 책을 매칭해 상세 링크를 만든다</li>
 *   <li>그 링크의 페이지 제목에 책 이름이 있다 — 10-18 v3 상세 주소는 200 빈 페이지라 상태 코드로는 못 잡았다</li>
 * </ol>
 * 요청은 주 3회(검색 2 + 상세 1). 단계마다 실패 메시지가 다르니 메일의 첫 실패 줄로 어디가 바뀌었는지 본다.
 */
@Tag("contract")
class MillieContractTest {

    // 운영 입력과 같게 YES24 형식("저/역")으로 넣는다. 판이 여럿이라도 오래 서비스될 고전을 골랐다
    // (10-18 밀리 검색: 민음사판 "헤르만 헤세 지음 / 박병덕 옮김" 구독 가능). 제목 검색 결과에 같은 낱말이 든
    // 다른 책이 섞이는 경우(예: "채식주의자" → 다른 책 4건)가 있어 실제로 밀리에 있는 책인지 확인하고 골라야 한다
    private static final IdentifiedBook BOOK = new IdentifiedBook("싯다르타", "헤르만 헤세 저/박병덕 역", null, "민음사", null);

    private static final String USER_AGENT = "CheckBook/1.0 (+https://github.com/chanyong1027/checkbook)";
    // GitHub 러너는 해외라 운영 상한(2초)보다 넉넉히 둔다. 확인하려는 건 응답 형식이지 지연이 아니다
    private static final int TIMEOUT_MS = 10_000;
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE = Pattern.compile(
            "<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private final BookMetadataNormalizer normalizer = new BookMetadataNormalizer();

    @Test
    void searchMatchAndDetailPageStillWork() {
        MillieProperties properties = propertiesFromApplicationYaml();
        MillieClient client = new MillieClient(
                RestClient.builder(),
                new MillieProperties(properties.baseUrl(), TIMEOUT_MS, properties.searchPath(), properties.detailUrlBase()),
                ExternalCallBudget.unlimited());

        // 검색은 상위 20건만 받는다(contentlimitCount=20, 운영과 같음). 고정 책이 20위 밖으로 밀리면 ②에서 실패한다
        List<MillieBookItem> items = stage("① 검색", () -> client.searchByTitle(normalizer.searchKeyword(BOOK.title())));
        assertThat(items)
                .as("① 검색 결과가 비었다 — 책이 빠졌거나 검색 경로·응답 구조가 바뀌었다 (search-path=%s)",
                        properties.searchPath())
                .isNotEmpty();
        assertThat(items)
                .as("① 매칭에 쓰는 필드(book_seq·content_name·author·book_brand·is_service·is_ebook_rent)를 모두 가진 항목이 없다 — 응답 필드 이름 변경 의심")
                .anySatisfy(item -> {
                    assertThat(item.bookSeq()).isNotBlank();
                    assertThat(item.contentName()).isNotBlank();
                    assertThat(item.author()).isNotBlank();
                    assertThat(item.bookBrand()).isNotBlank();
                    assertThat(item.isService()).isNotNull();
                    assertThat(item.isEbookRent()).isNotNull();
                });

        MillieBookService service = new MillieBookService(client,
                new MillieBookMatcher(new BookMatcher(normalizer), normalizer), normalizer, new SimpleMeterRegistry(),
                properties);
        MillieAvailability availability = stage("② 매칭", () -> service.findAvailability(BOOK, null));
        assertThat(availability.available())
                .as("② 매칭 실패 또는 구독 불가 — 밀리 저자·제목 표기 변경, 매처 회귀, 또는 책의 구독 종료. 후보=%s",
                        items.stream().map(i -> i.contentName() + "/" + i.author() + "/" + i.bookBrand()).toList())
                .isTrue();

        String url = availability.detailUrl();
        HttpResponse<String> page = stage("③ 상세 페이지", () -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofMillis(TIMEOUT_MS))
                                .header("User-Agent", USER_AGENT)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        assertThat(page.statusCode()).as("③ 상세 페이지 상태 코드 (url=%s)", url).isEqualTo(200);
        assertThat(pageTitles(page.body()))
                .as("③ 상세 페이지 제목(<title>·og:title)에 책 이름이 없다 — 상세 주소 버전이 바뀌었을 수 있다(옛 주소는 200 빈 페이지). "
                        + "브라우저로 열어 보고 millie.detail-url-base를 고친다 (url=%s)", url)
                .anyMatch(title -> title.contains(BOOK.title()));
    }

    /**
     * 호출 예외에 단계 이름과 원인 갈래를 붙여 메일에서 바로 갈라 보게 한다.
     * MillieClient는 연결 실패와 응답 이상(구조 누락·오류 코드·HTTP 4xx·파싱 실패)을 모두 MillieClientException으로
     * 던지므로, 원인 사슬에 IOException(연결·시간 초과)이 있을 때만 네트워크로 본다. GitHub 러너는 해외라 차단도 의심한다
     */
    private static <T> T stage(String name, Callable<T> call) {
        try {
            return call.call();
        } catch (Exception e) {
            String kind = causedByIo(e)
                    ? "호출 실패(네트워크·시간 초과·차단 의심 — 먼저 Actions에서 다시 실행해 본다)"
                    : "응답 이상(응답 구조·코드·경로가 바뀌었을 수 있다 — 계약 변경으로 보고 원인을 확인한다)";
            throw new AssertionError(name + " " + kind + ": " + e, e);
        }
    }

    private static boolean causedByIo(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private static MillieProperties propertiesFromApplicationYaml() {
        AtomicReference<MillieProperties> holder = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(MillieClientConfig.class)
                .run(context -> holder.set(context.getBean(MillieProperties.class)));
        return holder.get();
    }

    // <title>은 렌더링 방식에 따라 쪼개지거나 다른 요소(SVG 등)의 것이 먼저 올 수 있어 og:title도 함께 본다
    private static List<String> pageTitles(String html) {
        List<String> titles = new ArrayList<>();
        for (Pattern pattern : List.of(TITLE, OG_TITLE)) {
            Matcher m = pattern.matcher(html);
            while (m.find()) {
                titles.add(m.group(1));
            }
        }
        return titles;
    }
}
