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
public class BookcubeELibClient implements ELibClient {

    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept-Language", "ko,en-US;q=0.9",
            "Accept", "text/html,application/xhtml+xml"
    );

    private static final Pattern AVAILABILITY_PATTERN = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    @Override
    public VendorType getVendorType() {
        return VendorType.BOOKCUBE;
    }

    @Override
    public List<ELibrarySearchResponse.ELibraryBook> search(String baseUrl, String keyword) {
        String searchUrl = baseUrl + "/product/list/?searchType=search&keyword="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8);

        log.info("북큐브 전자도서관 검색: {}", baseUrl);

        try {
            Document document = Jsoup.connect(searchUrl)
                    .headers(HEADERS)
                    .timeout(15_000)
                    .get();

            return parseResults(document, baseUrl);
        } catch (IOException e) {
            log.error("북큐브 전자도서관 검색 실패: {}", baseUrl, e);
            throw new ELibraryClientException("북큐브 전자도서관 접속 실패", e);
        }
    }

    private List<ELibrarySearchResponse.ELibraryBook> parseResults(Document document, String baseUrl) {
        Element resultList = document.selectFirst("ul.list.typelist");
        if (resultList == null) {
            throw new ELibraryClientException("북큐브 검색 결과 DOM을 찾을 수 없습니다.");
        }

        List<ELibrarySearchResponse.ELibraryBook> results = new ArrayList<>();
        Elements books = resultList.select("> li.item");

        for (Element book : books) {
            try {
                results.add(parseBook(book, baseUrl));
            } catch (Exception e) {
                log.warn("북큐브 도서 파싱 실패, 건너뜀", e);
            }
        }

        if (!books.isEmpty() && results.isEmpty()) {
            throw new ELibraryClientException("북큐브 검색 결과를 파싱하지 못했습니다.");
        }

        return results;
    }

    private ELibrarySearchResponse.ELibraryBook parseBook(Element book, String baseUrl) {
        Element titleLink = book.selectFirst("div.subject > a");
        String title = titleLink != null ? titleLink.text().trim() : "";
        if (title.isBlank()) {
            throw new ELibraryClientException("북큐브 도서 제목 파싱 실패");
        }
        String detailUrl = titleLink == null ? null : normalizeUrl(baseUrl, titleLink.attr("href"));

        List<String> infoItems = book.select("ul.i1 li").eachText();
        String author = infoItems.size() > 0 ? infoItems.get(0).trim() : null;
        String publisher = infoItems.size() > 1 ? infoItems.get(1).trim() : null;

        String coverUrl = normalizeUrl(baseUrl, book.select("img").attr("src").trim());
        boolean available = extractAvailability(book.select("ul.i2 li > strong").eachText(), book.text());

        return new ELibrarySearchResponse.ELibraryBook(
                title,
                blankToNull(author),
                blankToNull(publisher),
                blankToNull(coverUrl),
                available,
                blankToNull(detailUrl)
        );
    }

    private boolean extractAvailability(List<String> strongTexts, String fallbackText) {
        for (String text : strongTexts) {
            Matcher matcher = AVAILABILITY_PATTERN.matcher(text);
            if (matcher.find()) {
                int borrowed = Integer.parseInt(matcher.group(1));
                int total = Integer.parseInt(matcher.group(2));
                return borrowed < total;
            }
        }

        Matcher matcher = AVAILABILITY_PATTERN.matcher(fallbackText);
        if (matcher.find()) {
            int borrowed = Integer.parseInt(matcher.group(1));
            int total = Integer.parseInt(matcher.group(2));
            return borrowed < total;
        }

        return false;
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
