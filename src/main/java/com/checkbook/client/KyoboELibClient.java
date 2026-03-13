package com.checkbook.client;

import com.checkbook.dto.EBookSearchResult;
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

/**
 * 교보 전자도서관 (dkyobobook.co.kr / mhebook.co.kr) HTML 스크래핑 클라이언트
 *
 * 검색 URL 패턴:
 *   https://{기관}.dkyobobook.co.kr/search/searchList.ink
 *     ?schClst=all&schDvsn=000&schTxt={keyword}
 *
 * 도메인만 다르고 파라미터 구조는 모든 교보 전자도서관에서 동일.
 */
@Slf4j
@Component
public class KyoboELibClient {

    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Accept-Language", "ko,en-US;q=0.9,en;q=0.8",
            "Accept", "text/html,application/xhtml+xml"
    );

    // 날짜 패턴: 2026-03-04 또는 2025-12-10
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    /**
     * 특정 교보 전자도서관에서 키워드 검색
     *
     * @param baseUrl  도서관 base URL (예: "https://duksunguniv.dkyobobook.co.kr")
     * @param keyword  검색어
     * @return 검색 결과 리스트
     */
    public List<EBookSearchResult> search(String baseUrl, String keyword) {
        String searchUrl = baseUrl + "/search/searchList.ink"
                + "?schClst=all"
                + "&schDvsn=000"
                + "&schTxt=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);

        log.info("교보 전자도서관 검색: {} (keyword={})", baseUrl, keyword);

        try {
            Document doc = Jsoup.connect(searchUrl)
                    .headers(HEADERS)
                    .timeout(15_000)
                    .get();

            return parseSearchResults(doc, baseUrl);

        } catch (IOException e) {
            log.error("교보 전자도서관 검색 실패: {}", baseUrl, e);
            return List.of();
        }
    }

    private List<EBookSearchResult> parseSearchResults(Document doc, String baseUrl) {
        List<EBookSearchResult> results = new ArrayList<>();
        Elements books = doc.select("ul.book_resultList > li");

        log.info("검색 결과 건수: {}", books.size());

        for (Element book : books) {
            try {
                results.add(parseBook(book, baseUrl));
            } catch (Exception e) {
                log.warn("도서 파싱 실패, 건너뜀", e);
            }
        }

        return results;
    }

    private EBookSearchResult parseBook(Element book, String baseUrl) {
        // 제목
        String title = book.select("li.tit > a").text().trim();

        // 저자 + 출판사 + 출간일 파싱
        // HTML: <li class="writer">저자명<span>출판사</span>2026-03-04</li>
        Element writerEl = book.selectFirst("li.writer");
        String author = "";
        String publisher = "";
        String publishDate = "";

        if (writerEl != null) {
            // 출판사: span 안의 텍스트
            Element publisherSpan = writerEl.selectFirst("span");
            if (publisherSpan != null) {
                publisher = publisherSpan.text().trim();
            }

            // 전체 텍스트에서 저자와 날짜 추출
            String fullText = writerEl.text().trim(); // "저자명 출판사 2026-03-04"
            String ownText = writerEl.ownText().trim(); // "저자명 2026-03-04" (span 제외)

            // 날짜 추출
            Matcher dateMatcher = DATE_PATTERN.matcher(ownText);
            if (dateMatcher.find()) {
                publishDate = dateMatcher.group(1);
                // 날짜 앞부분이 저자
                author = ownText.substring(0, dateMatcher.start()).trim();
            } else {
                author = ownText;
            }
        }

        // 공급사 (교보문고, 북큐브 등)
        String vendor = book.select("span.store").text().trim();

        // 표지 이미지
        String coverUrl = book.select("div.img img").attr("src").trim();
        if (coverUrl.startsWith("//")) {
            coverUrl = "https:" + coverUrl;
        }

        // 설명
        String description = book.select("li.txt").text().trim();

        // 대출 가능 여부: 대출 버튼이 있으면 가능
        boolean available = !book.select("input[name=brwBtn][value=대출]").isEmpty();

        // 도서관 이름 추출 (title 속성에서)
        String libraryName = "";
        Element titleLink = book.selectFirst("li.tit > a");
        if (titleLink != null) {
            String titleAttr = titleLink.attr("title");
            // "제목 | 도서관명" 패턴
            if (titleAttr.contains("|")) {
                libraryName = titleAttr.substring(titleAttr.lastIndexOf("|") + 1).trim();
            }
        }

        return EBookSearchResult.builder()
                .title(title)
                .author(author)
                .publisher(publisher)
                .publishDate(publishDate)
                .vendor(vendor)
                .coverUrl(coverUrl)
                .description(description.length() > 200
                        ? description.substring(0, 200) + "..." : description)
                .available(available)
                .libraryName(libraryName)
                .source(baseUrl)
                .build();
    }
}
