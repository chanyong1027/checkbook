package com.checkbook.search.service;

import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.search.dto.SubscriptionSearchLink;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 밀리 외 구독 서비스의 제목 검색 링크를 만든다. 주소만 조립하고 외부 호출은 하지 않으므로 밀리 조회 결과와 무관하다.
 * 검색어는 밀리 검색·도서관 링크와 같은 규칙({@link BookMetadataNormalizer#searchKeyword})으로 만든다.
 *
 * <p>주소 형식은 2026-11-05에 확인했다. 만권당은 {@code searchWord}만 동작하고({@code query}는 검색되지 않음),
 * 크레마클럽은 {@code /Search}가 robots 금지라 {@code /BookClub/Search}를 쓴다.
 */
@Component
public class SubscriptionSearchLinks {

    private final BookMetadataNormalizer normalizer;

    public SubscriptionSearchLinks(BookMetadataNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /** 검색어를 만들 수 없으면(제목이 없거나 ':부제'처럼 부제뿐이면) 빈 목록이다. 빈 검색 화면으로 보내지 않는다. */
    public List<SubscriptionSearchLink> forTitle(String title) {
        if (title == null) {
            return List.of();
        }
        String normalized = normalizer.searchKeyword(title).strip();
        if (normalized.isEmpty()) {
            return List.of();
        }
        // 공백을 +가 아닌 %20으로 인코딩한다(UriUtils). 쿼리 밖에서도 공백으로 읽히는 쪽이 사이트별 해석 차이가 없다
        String keyword = UriUtils.encode(normalized, StandardCharsets.UTF_8);
        return List.of(
                // 일반 리디 검색은 구매 전용 책이 섞이므로 구독 서비스인 셀렉트 검색으로 보낸다
                new SubscriptionSearchLink("리디셀렉트", "https://select.ridibooks.com/search?q=" + keyword + "&type=Books"),
                new SubscriptionSearchLink("만권당", "https://max.aladin.co.kr/search?searchWord=" + keyword),
                new SubscriptionSearchLink("크레마클럽", "https://cremaclub.yes24.com/BookClub/Search?query=" + keyword));
    }
}
