package com.checkbook.elibrary.contract;

import com.checkbook.common.ratelimit.ELibraryConcurrencyLimiter;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.common.ratelimit.RateLimitProperties;
import com.checkbook.elibrary.client.ELibraryClientException;
import com.checkbook.elibrary.client.KyoboELibClient;
import com.checkbook.elibrary.domain.ELibrary;
import com.checkbook.elibrary.domain.ELibraryStatus;
import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.repository.ELibraryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ELibraryContractCheckerTest {

    private static final RobotsTxtFetcher.Result ALLOWED = RobotsTxtFetcher.Result.found(RobotsTxt.ALLOW_ALL);

    private final ELibraryRepository repository = mock(ELibraryRepository.class);
    private final KyoboELibClient client = mock(KyoboELibClient.class);
    private final RobotsTxtFetcher fetcher = mock(RobotsTxtFetcher.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Map<String, RobotsTxtFetcher.Result> robotsByOrigin = new HashMap<>();
    private ExternalCallBudget budget = budget(1000);
    private ELibraryContractChecker checker;

    private static ExternalCallBudget budget(int perLibrary) {
        return new ExternalCallBudget(new RateLimitProperties(true, null, Map.of(), perLibrary, perLibrary, 2),
                new SimpleMeterRegistry(), Clock.systemUTC());
    }

    @BeforeEach
    void setUp() {
        when(fetcher.fetch(anyString()))
                .thenAnswer(invocation -> robotsByOrigin.getOrDefault(invocation.<String>getArgument(0), ALLOWED));
        checker = newChecker();
    }

    // 시험에서는 섞지 않는다 — 목록 순서대로 뽑는다
    private ELibraryContractChecker newChecker() {
        return new ELibraryContractChecker(repository, client, fetcher, budget,
                new ELibraryConcurrencyLimiter(new RateLimitProperties(true, null, Map.of(), 1000, 200, 2)),
                meterRegistry, list -> { });
    }

    private void pool(ELibrary... libraries) {
        when(repository.findAllByStatusAndVendorTypeAndLoginRequiredFalseAndRobotsDisallowedFalse(
                ELibraryStatus.ACTIVE, VendorType.KYOBO)).thenReturn(new ArrayList<>(List.of(libraries)));
    }

    private static ELibrary library(long id, String baseUrl) {
        ELibrary library = ELibrary.builder()
                .name("도서관" + id).vendorType(VendorType.KYOBO).baseUrl(baseUrl)
                .status(ELibraryStatus.ACTIVE).loginRequired(false).build();
        ReflectionTestUtils.setField(library, "id", id);
        return library;
    }

    private static ELibrary library(long id) {
        return library(id, "http://lib" + id + ".example.kr");
    }

    private void fails(ELibrary library, ELibraryClientException.Kind kind) {
        when(client.search(eq(library.getBaseUrl()), anyString(), any()))
                .thenThrow(new ELibraryClientException(kind, "시험"));
    }

    private double counted(String result) {
        return meterRegistry.get("elibrary.contract.check").tag("result", result).counter().count();
    }

    private double failedSites() {
        return meterRegistry.get("elibrary.contract.site.failed").counter().count();
    }

    @Test
    void 결과_종류는_돌기_전에_0으로_있다() {
        // 알림 규칙이 increase()로 보므로 첫 사건을 놓치지 않게
        for (String result : List.of("pass", "site_failure", "vendor_failure", "undetermined")) {
            assertThat(counted(result)).isZero();
        }
        assertThat(failedSites()).isZero();
    }

    @Test
    void 세_곳이_모두_정상이면_통과하고_빈_결과도_정상이다() {
        pool(library(1), library(2), library(3));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());

        ELibraryContractChecker.Report report = checker.run();

        assertThat(report.result()).isEqualTo("pass");
        assertThat(report.checks()).hasSize(3);
        assertThat(counted("pass")).isEqualTo(1);
        verify(client).search(eq("http://lib1.example.kr"), eq(ELibraryContractChecker.KEYWORD), any());
    }

    @Test
    void 한_곳만_실패하면_그_사이트가_고장_후보다() {
        ELibrary broken = library(2);
        pool(library(1), broken, library(3));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());
        fails(broken, ELibraryClientException.Kind.TIMEOUT);

        ELibraryContractChecker.Report report = checker.run();

        assertThat(report.result()).isEqualTo("site_failure");
        assertThat(report.failedSites()).extracting(c -> c.library().getId()).containsExactly(2L);
        assertThat(report.failedSites().get(0).verdict()).isEqualTo(ELibraryContractChecker.Verdict.UNREACHABLE);
        assertThat(failedSites()).isEqualTo(1);
        verify(client, times(3)).search(anyString(), anyString(), any()); // 다시 뽑지 않는다
    }

    @Test
    void 두_곳이_실패해도_다시_뽑은_세_곳이_괜찮으면_업체가_아니라_그_두_곳이_고장_후보다() {
        ELibrary first = library(1);
        ELibrary second = library(2);
        pool(first, second, library(3), library(4), library(5), library(6));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());
        fails(first, ELibraryClientException.Kind.STRUCTURE_CHANGED);
        fails(second, ELibraryClientException.Kind.CONNECTION_FAILED);

        ELibraryContractChecker.Report report = checker.run();

        assertThat(report.result()).isEqualTo("site_failure");
        assertThat(report.failedSites()).extracting(c -> c.library().getId()).containsExactly(1L, 2L);
        assertThat(report.failedSites()).extracting(ELibraryContractChecker.SiteCheck::verdict)
                .containsExactly(ELibraryContractChecker.Verdict.TEMPLATE_SUSPECT, ELibraryContractChecker.Verdict.UNREACHABLE);
        assertThat(counted("vendor_failure")).isZero();
        assertThat(failedSites()).isEqualTo(2);
        verify(client, times(6)).search(anyString(), anyString(), any());
    }

    @Test
    void 다시_뽑은_세_곳도_두_곳_이상_실패하면_업체_공통_장애다() {
        List<ELibrary> libraries = IntStream.rangeClosed(1, 6).mapToObj(ELibraryContractCheckerTest::library).toList();
        pool(libraries.toArray(ELibrary[]::new));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());
        fails(libraries.get(0), ELibraryClientException.Kind.STRUCTURE_CHANGED);
        fails(libraries.get(1), ELibraryClientException.Kind.PARSE_FAILED);
        fails(libraries.get(3), ELibraryClientException.Kind.ENDPOINT_NOT_FOUND);
        fails(libraries.get(4), ELibraryClientException.Kind.STRUCTURE_CHANGED);

        ELibraryContractChecker.Report report = checker.run();

        assertThat(report.result()).isEqualTo("vendor_failure");
        assertThat(counted("vendor_failure")).isEqualTo(1);
        assertThat(failedSites()).isZero(); // 사이트 탓이 아니라 업체 탓이다
    }

    @Test
    void robots가_막은_사이트는_검색하지_않고_다른_곳을_뽑는다() {
        ELibrary blocked = library(1);
        pool(blocked, library(2), library(3), library(4));
        robotsByOrigin.put("http://lib1.example.kr",
                RobotsTxtFetcher.Result.found(RobotsTxt.parse("User-agent: *\nDisallow: /")));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());

        ELibraryContractChecker.Report report = checker.run();

        assertThat(report.result()).isEqualTo("pass");
        assertThat(report.checks()).extracting(c -> c.library().getId()).containsExactly(2L, 3L, 4L);
        verify(client, never()).search(eq("http://lib1.example.kr"), anyString(), any());
    }

    @Test
    void 뒤_갈래만_막혀도_건너뛴다() {
        // 앱은 앞 갈래가 404면 /elibrary-front를 부른다 — 그 경로가 막혔으면 자동 수집에서는 쓰지 않는다
        pool(library(1), library(2), library(3), library(4));
        robotsByOrigin.put("http://lib1.example.kr",
                RobotsTxtFetcher.Result.found(RobotsTxt.parse("User-agent: *\nDisallow: /elibrary-front/")));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(checker.run().checks()).extracting(c -> c.library().getId()).containsExactly(2L, 3L, 4L);
    }

    @Test
    void 첫_갈래의_robots에_닿지_않으면_닿지_않음으로_세고_검색하지_않는다() {
        pool(library(1), library(2), library(3));
        robotsByOrigin.put("http://lib1.example.kr", RobotsTxtFetcher.Result.unreachable("timeout", false));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());

        ELibraryContractChecker.Report report = checker.run();

        assertThat(report.result()).isEqualTo("site_failure");
        assertThat(report.failedSites().get(0).verdict()).isEqualTo(ELibraryContractChecker.Verdict.UNREACHABLE);
        assertThat(report.failedSites().get(0).detail()).contains("robots");
        verify(client, never()).search(eq("http://lib1.example.kr"), anyString(), any());
    }

    @Test
    void https_갈래의_서버가_없으면_무시하고_5xx나_시간_초과면_건너뛴다() {
        // 앱이 http 주소에 붙여 보는 https 갈래는 추측이다 — 서버가 없으면(DNS 실패·접속 거부) 앱 요청도 닿지 않으니
        // 막을 대상이 없다. 5xx·시간 초과는 서버가 있을 수 있어 RFC대로 막힌 것으로 본다
        pool(library(1), library(2), library(3), library(4), library(5));
        robotsByOrigin.put("https://lib1.example.kr", RobotsTxtFetcher.Result.unreachable("refused", true));
        robotsByOrigin.put("https://lib2.example.kr", RobotsTxtFetcher.Result.unreachable("http 503", false));
        robotsByOrigin.put("https://lib3.example.kr", RobotsTxtFetcher.Result.unreachable("SocketTimeoutException", false));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(checker.run().checks()).extracting(c -> c.library().getId()).containsExactly(1L, 4L, 5L);
    }

    @Test
    void 검색이_robots가_막은_주소로_리다이렉트되면_따라가지_않고_건너뛴다() {
        // 갈래 주소는 확인했어도 리다이렉트된 곳(포털·SSO 등)은 따로 확인해야 한다
        pool(library(1), library(2), library(3), library(4));
        robotsByOrigin.put("http://portal.example.kr",
                RobotsTxtFetcher.Result.found(RobotsTxt.parse("User-agent: *\nDisallow: /")));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());
        when(client.search(eq("http://lib1.example.kr"), anyString(), any())).thenAnswer(invocation -> {
            Predicate<String> guard = invocation.getArgument(2);
            String location = "http://portal.example.kr/sso?next=/search";
            if (!guard.test(location)) {
                throw new KyoboELibClient.RedirectNotAllowedException(location);
            }
            return List.of();
        });

        assertThat(checker.run().checks()).extracting(c -> c.library().getId()).containsExactly(2L, 3L, 4L);
    }

    @Test
    void robots가_허용한_곳으로의_리다이렉트는_따라간다() {
        pool(library(1), library(2), library(3));
        when(client.search(anyString(), anyString(), any())).thenAnswer(invocation -> {
            Predicate<String> guard = invocation.getArgument(2);
            assertThat(guard.test("https://lib1.example.kr/elibrary-front/search/searchList.ink?schTxt=x")).isTrue();
            assertThat(guard.test("not a url")).isFalse();
            return List.of();
        });

        assertThat(checker.run().result()).isEqualTo("pass");
    }

    @Test
    void 앱_종료로_인터럽트되면_판정하지_않고_기록하지_않는다() {
        // 종료 중에는 자리 대기가 곧바로 끝나 '상한'으로 잘못 세어져 판정 불가가 기록될 수 있다
        pool(library(1), library(2), library(3));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());
        Thread.currentThread().interrupt();
        try {
            assertThat(checker.run().result()).isEqualTo("interrupted");
        } finally {
            Thread.interrupted();
        }
        for (String result : List.of("pass", "site_failure", "vendor_failure", "undetermined")) {
            assertThat(counted(result)).isZero();
        }
    }

    @Test
    void 같은_서버는_한_번만_본다() {
        // 경남 전자도서관처럼 한 호스트에 포트만 다른 사이트가 여럿이다 — 한 서버가 둘로 세지면 과반 판정이 기운다
        pool(library(1, "https://elib.example.kr:8114"), library(2, "https://elib.example.kr:8115"),
                library(3), library(4));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(checker.run().checks()).extracting(c -> c.library().getId()).containsExactly(1L, 3L, 4L);
    }

    @Test
    void 하루_상한에_닿은_사이트는_건너뛴다() {
        budget = budget(1);
        checker = newChecker();
        budget.acquireELibrary(1L, false);
        pool(library(1), library(2), library(3), library(4));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(checker.run().checks()).extracting(c -> c.library().getId()).containsExactly(2L, 3L, 4L);
    }

    @Test
    void 세_곳을_못_채우면_판정_불가다() {
        pool(library(1), library(2));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(checker.run().result()).isEqualTo("undetermined");
        assertThat(counted("undetermined")).isEqualTo(1);
    }

    @Test
    void 다시_볼_세_곳을_못_채워도_판정_불가다() {
        ELibrary first = library(1);
        ELibrary second = library(2);
        pool(first, second, library(3), library(4));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());
        fails(first, ELibraryClientException.Kind.STRUCTURE_CHANGED);
        fails(second, ELibraryClientException.Kind.STRUCTURE_CHANGED);

        assertThat(checker.run().result()).isEqualTo("undetermined");
        assertThat(failedSites()).isZero();
    }

    @Test
    void 사이트를_찾으려고_robots를_보는_곳은_한_번에_30곳까지다() {
        // robots_disallowed를 채우기 전에는 대부분 막혀 있다 — 끝없이 찔러 보지 않는다
        List<ELibrary> libraries = IntStream.rangeClosed(1, 50).mapToObj(ELibraryContractCheckerTest::library).toList();
        pool(libraries.toArray(ELibrary[]::new));
        when(fetcher.fetch(anyString()))
                .thenReturn(RobotsTxtFetcher.Result.found(RobotsTxt.parse("User-agent: *\nDisallow: /")));

        assertThat(checker.run().result()).isEqualTo("undetermined");
        verify(fetcher, times(ELibraryContractChecker.MAX_SITE_ATTEMPTS)).fetch(anyString());
    }

    @Test
    void 예상_못_한_오류는_틀_변경_의심으로_본다() {
        // 클라이언트가 분류하지 못한 예외는 우리 파서가 처음 보는 화면에서 넘어진 경우가 대부분이다
        ELibrary odd = library(1);
        pool(odd, library(2), library(3));
        when(client.search(anyString(), anyString(), any())).thenReturn(List.of());
        when(client.search(eq(odd.getBaseUrl()), anyString(), any())).thenThrow(new IllegalStateException("처음 보는 화면"));

        ELibraryContractChecker.Report report = checker.run();

        assertThat(report.failedSites().get(0).verdict()).isEqualTo(ELibraryContractChecker.Verdict.TEMPLATE_SUSPECT);
    }
}
