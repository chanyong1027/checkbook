package com.checkbook.elibrary.client;

import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.dto.ELibrarySearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class KyoboELibClient implements ELibClient {

    private static final List<String> EMPTY_RESULT_MARKERS = List.of(
            "검색 결과가 없습니다",
            "검색결과가 없습니다",
            "조회된 결과가 없습니다",
            "조회 결과가 없습니다",
            "검색된 자료가 없습니다",
            "검색된 도서가 없습니다",
            "검색하신 자료가 없습니다"
    );

    /** 브라우저 UA를 그대로 쓴다(결정 2026-10-20). robots.txt 판정은 이 UA의 제품 토큰(mozilla)으로 한다 */
    public static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", USER_AGENT,
            "Accept-Language", "ko,en-US;q=0.9",
            "Accept", "text/html,application/xhtml+xml"
    );

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    // fnContentClick(this, 'cttsDvsnCode', 'brcd', 'ctgrId', 'sntnAuthCode', ...)
    private static final Pattern CONTENT_CLICK_PATTERN = Pattern.compile(
            "fnContentClick\\(this,\\s*'([^']*)',\\s*'([^']*)',\\s*'([^']*)',\\s*'([^']*)'");

    @Override
    public VendorType getVendorType() {
        return VendorType.KYOBO;
    }

    @Override
    public List<ELibrarySearchResponse.ELibraryBook> search(String rawBaseUrl, String keyword) {
        // 끝 '/'가 남은 주소는 '//search'를 부른다(10-20 전수 조사 12곳). 갈래 주소도 이 값으로 만든다
        String baseUrl = stripTrailingSlashes(rawBaseUrl);
        log.info("교보 전자도서관 검색: {}", baseUrl);

        List<String> candidates = searchBases(baseUrl);
        IOException lastException = null;

        // 사이트 하나의 실패는 흔하고(1,964곳) 사람이 고칠 일이 아니라 WARN — ERROR는 알림 대상이라 쓰지 않는다
        for (String candidate : candidates) {
            try {
                return doSearch(candidate, keyword);
            } catch (HttpStatusException e) {
                if (e.getStatusCode() == 404) {
                    log.debug("교보 전자도서관 404, 다음 경로 시도: {}", candidate);
                    lastException = e;
                } else {
                    log.warn("교보 전자도서관 검색 실패: {}", candidate, e);
                    throw ELibraryClientException.ofIo("교보 전자도서관 접속 실패", e);
                }
            } catch (IOException e) {
                // http 주소를 https로 바꿔 본 마지막 후보만 추측이다 — 앞 경로들이 404였다면 그 실패는 '검색 주소 없음'으로 본다.
                // /elibrary-front는 실제로 쓰이는 경로라, 거기서 접속이 끊기면 사이트가 죽은 것이다(CodeRabbit #47)
                if (lastException != null && candidate.startsWith("https://") && baseUrl.startsWith("http://")) {
                    log.debug("교보 전자도서관 https 대체 경로도 실패: {}", candidate, e);
                    continue;
                }
                log.warn("교보 전자도서관 검색 실패: {}", candidate, e);
                throw ELibraryClientException.ofIo("교보 전자도서관 접속 실패", e);
            }
        }

        // 업체가 검색 주소를 바꾸면 모든 사이트가 여기로 온다 — 사이트 하나의 HTTP 오류와 나눈다
        log.warn("교보 전자도서관 모든 경로 실패: {}", baseUrl);
        throw new ELibraryClientException(ELibraryClientException.Kind.ENDPOINT_NOT_FOUND, "교보 전자도서관 접속 실패", lastException);
    }

    private static String stripTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    /**
     * 검색을 시도하는 갈래 주소(차례대로): 기본 → /elibrary-front → http면 https의 /elibrary-front.
     * 앞이 404면 다음으로 간다. 주간 계약 확인이 robots.txt를 볼 때도 이 목록을 쓴다(부르는 주소와 확인한 주소가 같게)
     */
    public static List<String> searchBases(String rawBaseUrl) {
        String baseUrl = stripTrailingSlashes(rawBaseUrl);
        List<String> candidates = new ArrayList<>();
        candidates.add(baseUrl);
        candidates.add(baseUrl + "/elibrary-front");
        if (baseUrl.startsWith("http://")) {
            String httpsBase = "https" + baseUrl.substring(4);
            candidates.add(httpsBase + "/elibrary-front");
        }
        return candidates;
    }

    /** 갈래 주소 하나의 검색 주소 */
    public static String searchUrl(String searchBase, String keyword) {
        return searchBase + "/search/searchList.ink"
                + "?schClst=all"
                + "&schDvsn=000"
                + "&schTxt=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
    }

    private List<ELibrarySearchResponse.ELibraryBook> doSearch(String searchBase, String keyword) throws IOException {
        Document document = Jsoup.connect(searchUrl(searchBase, keyword))
                .headers(HEADERS)
                .timeout(15_000)
                .get();

        return parseResults(document, searchBase);
    }

    private List<ELibrarySearchResponse.ELibraryBook> parseResults(Document document, String baseUrl) {
        Element resultList = document.selectFirst("ul.book_resultList");
        if (resultList == null) {
            if (isEmptyResultPage(document)) {
                return List.of();
            }
            throw new ELibraryClientException(ELibraryClientException.Kind.STRUCTURE_CHANGED, "교보 검색 결과 DOM을 찾을 수 없습니다.");
        }

        List<ELibrarySearchResponse.ELibraryBook> results = new ArrayList<>();
        Elements books = resultList.select("> li");

        for (Element book : books) {
            try {
                results.add(parseBook(book, baseUrl));
            } catch (Exception e) {
                log.warn("교보 도서 파싱 실패, 건너뜀", e);
            }
        }

        if (!books.isEmpty() && results.isEmpty()) {
            throw new ELibraryClientException(ELibraryClientException.Kind.PARSE_FAILED, "교보 검색 결과를 파싱하지 못했습니다.");
        }

        return results;
    }

    private boolean isEmptyResultPage(Document document) {
        String text = document.text();
        return EMPTY_RESULT_MARKERS.stream().anyMatch(text::contains);
    }

    private ELibrarySearchResponse.ELibraryBook parseBook(Element book, String baseUrl) {
        String title = book.select("li.tit > a").text().trim();
        if (title.isBlank()) {
            throw new ELibraryClientException(ELibraryClientException.Kind.PARSE_FAILED, "교보 도서 제목 파싱 실패");
        }
        Element writerElement = book.selectFirst("li.writer");
        String author = "";
        String publisher = "";

        if (writerElement != null) {
            Element publisherSpan = writerElement.selectFirst("span");
            if (publisherSpan != null) {
                publisher = publisherSpan.text().trim();
            }

            String ownText = writerElement.ownText().trim();
            Matcher matcher = DATE_PATTERN.matcher(ownText);
            author = matcher.find() ? ownText.substring(0, matcher.start()).trim() : ownText;
        }

        String coverUrl = normalizeUrl(baseUrl, book.select("div.img img").attr("src").trim());
        boolean available = !book.select("input[name=brwBtn][value=대출]").isEmpty();

        Element titleLink = book.selectFirst("li.tit > a");
        String detailUrl = titleLink == null ? null : buildDetailUrl(baseUrl, titleLink);

        return new ELibrarySearchResponse.ELibraryBook(
                title,
                blankToNull(author),
                blankToNull(publisher),
                blankToNull(coverUrl),
                available,
                blankToNull(detailUrl)
        );
    }

    private String buildDetailUrl(String baseUrl, Element titleLink) {
        String onclick = titleLink.attr("onclick");
        Matcher m = CONTENT_CLICK_PATTERN.matcher(onclick);
        if (m.find()) {
            String cttsDvsnCode = m.group(1);
            String brcd = m.group(2);
            String ctgrId = m.group(3);
            String sntnAuthCode = m.group(4);
            String href = titleLink.attr("href"); // e.g. /elibrary-front/content/contentView.ink
            return normalizeUrl(baseUrl, href)
                    + "?cttsDvsnCode=" + cttsDvsnCode
                    + "&brcd=" + brcd
                    + "&ctgrId=" + ctgrId
                    + "&sntnAuthCode=" + sntnAuthCode;
        }
        // onclick 파싱 실패 시 href 그대로 사용
        return normalizeUrl(baseUrl, titleLink.attr("href"));
    }

    private String normalizeUrl(String baseUrl, String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("/")) {
            // root-relative: scheme+host+port만 사용 (path 제외)
            try {
                java.net.URI uri = java.net.URI.create(baseUrl);
                String origin = uri.getScheme() + "://" + uri.getHost()
                        + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
                return origin + url;
            } catch (IllegalArgumentException e) {
                return baseUrl + url;
            }
        }
        return baseUrl + "/" + url;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
