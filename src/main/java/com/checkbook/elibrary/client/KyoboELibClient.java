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

    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
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
    public List<ELibrarySearchResponse.ELibraryBook> search(String baseUrl, String keyword) {
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        log.info("교보 전자도서관 검색: {}", baseUrl);

        List<String> candidates = buildCandidates(baseUrl);
        IOException lastException = null;

        for (String candidate : candidates) {
            try {
                return doSearch(candidate, encoded);
            } catch (HttpStatusException e) {
                if (e.getStatusCode() == 404) {
                    log.debug("교보 전자도서관 404, 다음 경로 시도: {}", candidate);
                    lastException = e;
                } else {
                    log.error("교보 전자도서관 검색 실패: {}", candidate, e);
                    throw new ELibraryClientException("교보 전자도서관 접속 실패", e);
                }
            } catch (IOException e) {
                log.error("교보 전자도서관 검색 실패: {}", candidate, e);
                throw new ELibraryClientException("교보 전자도서관 접속 실패", e);
            }
        }

        log.error("교보 전자도서관 모든 경로 실패: {}", baseUrl);
        throw new ELibraryClientException("교보 전자도서관 접속 실패", lastException);
    }

    private List<String> buildCandidates(String baseUrl) {
        List<String> candidates = new ArrayList<>();
        candidates.add(baseUrl);
        candidates.add(baseUrl + "/elibrary-front");
        if (baseUrl.startsWith("http://")) {
            String httpsBase = "https" + baseUrl.substring(4);
            candidates.add(httpsBase + "/elibrary-front");
        }
        return candidates;
    }

    private List<ELibrarySearchResponse.ELibraryBook> doSearch(String searchBase, String encodedKeyword) throws IOException {
        String searchUrl = searchBase + "/search/searchList.ink"
                + "?schClst=all"
                + "&schDvsn=000"
                + "&schTxt=" + encodedKeyword;

        Document document = Jsoup.connect(searchUrl)
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
            throw new ELibraryClientException("교보 검색 결과 DOM을 찾을 수 없습니다.");
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
            throw new ELibraryClientException("교보 검색 결과를 파싱하지 못했습니다.");
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
            throw new ELibraryClientException("교보 도서 제목 파싱 실패");
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
