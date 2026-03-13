package com.checkbook.elibrary.client;

import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.dto.ELibrarySearchResponse;
import lombok.extern.slf4j.Slf4j;
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

    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
            "Accept-Language", "ko,en-US;q=0.9",
            "Accept", "text/html,application/xhtml+xml"
    );

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    @Override
    public VendorType getVendorType() {
        return VendorType.KYOBO;
    }

    @Override
    public List<ELibrarySearchResponse.ELibraryBook> search(String baseUrl, String keyword) {
        String searchUrl = baseUrl + "/search/searchList.ink"
                + "?schClst=all"
                + "&schDvsn=000"
                + "&schTxt=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);

        log.info("교보 전자도서관 검색: {}", baseUrl);

        try {
            Document document = Jsoup.connect(searchUrl)
                    .headers(HEADERS)
                    .timeout(15_000)
                    .get();

            return parseResults(document, baseUrl);
        } catch (IOException e) {
            log.error("교보 전자도서관 검색 실패: {}", baseUrl, e);
            throw new ELibraryClientException("교보 전자도서관 접속 실패", e);
        }
    }

    private List<ELibrarySearchResponse.ELibraryBook> parseResults(Document document, String baseUrl) {
        Element resultList = document.selectFirst("ul.book_resultList");
        if (resultList == null) {
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
        String detailUrl = titleLink == null ? null : normalizeUrl(baseUrl, titleLink.attr("href"));

        return new ELibrarySearchResponse.ELibraryBook(
                title,
                blankToNull(author),
                blankToNull(publisher),
                blankToNull(coverUrl),
                available,
                blankToNull(detailUrl)
        );
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
            return baseUrl + url;
        }
        return baseUrl + "/" + url;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
