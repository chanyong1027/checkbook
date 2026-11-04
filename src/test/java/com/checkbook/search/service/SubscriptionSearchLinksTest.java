package com.checkbook.search.service;

import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.search.dto.SubscriptionSearchLink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionSearchLinksTest {

    private final SubscriptionSearchLinks links = new SubscriptionSearchLinks(new BookMetadataNormalizer());

    @Test
    void 세_서비스의_제목_검색_주소를_한_번_인코딩해_만든다() {
        assertThat(links.forTitle("데미안")).containsExactly(
                new SubscriptionSearchLink("리디셀렉트", "https://select.ridibooks.com/search?q=%EB%8D%B0%EB%AF%B8%EC%95%88&type=Books"),
                new SubscriptionSearchLink("만권당", "https://max.aladin.co.kr/search?searchWord=%EB%8D%B0%EB%AF%B8%EC%95%88"),
                new SubscriptionSearchLink("크레마클럽", "https://cremaclub.yes24.com/BookClub/Search?query=%EB%8D%B0%EB%AF%B8%EC%95%88"));
    }

    @Test
    void 밀리_검색과_같은_규칙으로_부제를_떼고_공백은_퍼센트20으로_쓴다() {
        List<SubscriptionSearchLink> result = links.forTitle("Clean Code: 애자일 소프트웨어 장인 정신");

        assertThat(result).extracting(SubscriptionSearchLink::url).allSatisfy(url ->
                assertThat(url).matches(".*=Clean%20Code(&type=Books)?"));
    }

    @Test
    void 검색어에_쓰인_예약_문자가_주소를_깨지_않는다() {
        assertThat(links.forTitle("C&C++ 입문")).extracting(SubscriptionSearchLink::url).allSatisfy(url ->
                assertThat(url).matches(".*=C%26C%2B%2B%20%EC%9E%85%EB%AC%B8(&type=Books)?"));
    }

    @Test
    void 제목이_없으면_링크를_만들지_않는다() {
        assertThat(links.forTitle(null)).isEmpty();
        assertThat(links.forTitle("  ")).isEmpty();
    }

    @Test
    void 부제뿐이라_검색어가_비면_빈_검색_화면으로_보내지_않는다() {
        assertThat(links.forTitle(":부제")).isEmpty();
        assertThat(links.forTitle(" - 부제")).isEmpty();
        assertThat(links.forTitle("　:부제")).isEmpty();
    }
}
