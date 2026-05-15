package com.checkbook.search.service;

import com.checkbook.client.millie.dto.MillieBookItem;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.common.matcher.BookMatcher;
import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.search.dto.MillieAvailability.Format;
import com.checkbook.search.service.MillieBookMatcher.MatchedMillie;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MillieBookMatcherTest {

    private final BookMetadataNormalizer normalizer = new BookMetadataNormalizer();
    private final MillieBookMatcher matcher =
            new MillieBookMatcher(new BookMatcher(normalizer), normalizer);

    private MillieBookItem item(String contentName, String author, String category) {
        return item(contentName, "", author, category, "", true, true);
    }

    private MillieBookItem item(String contentName, String subtitle, String author,
                                 String category, String bookBrand,
                                 boolean isService, boolean isEbookRent) {
        return new MillieBookItem(
                contentName + "-seq",
                contentName,
                subtitle,
                author,
                category,
                bookBrand,
                isService,
                isEbookRent
        );
    }

    private AladinSearchResult aladin(String title, String author) {
        return new AladinSearchResult(null, title, author, null, null, null);
    }

    private AladinSearchResult aladin(String title, String author, String publisher) {
        return new AladinSearchResult(null, title, author, publisher, null, null);
    }

    @Test
    void singleMatch_returnsItemAsPrimary_formatEbook() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        List<MillieBookItem> candidates = List.of(
                item("인간실격", "다자이 오사무", "소설")
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().primary().contentName()).isEqualTo("인간실격");
        assertThat(result.get().format()).isEqualTo(Format.EBOOK);
    }

    @Test
    void noMatch_returnsEmpty() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        List<MillieBookItem> candidates = List.of(
                item("다른 책", "다른 저자", "소설")
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isEmpty();
    }

    @Test
    void colonSubtitle_matches() {
        AladinSearchResult sel = aladin("첫 여름 완주", "김연수");
        List<MillieBookItem> candidates = List.of(
                item("첫 여름, 완주 : 읽는 소설", "김연수", "소설")
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().primary().contentName()).isEqualTo("첫 여름, 완주 : 읽는 소설");
    }

    @Test
    void demotedKeyword_bookWinsOverCalligraphy() {
        // 주의: "어린왕자 필사노트" 형태로는 BookMatcher가 매칭 안 함
        // (normalizeTitle 후 "어린왕자필사노트" ≠ selNorm "어린왕자").
        // 콜론 분리 형태로 두면 PREFIX 경로로 매칭 → Comparator 1차 키(키워드) 검증 가능.
        AladinSearchResult sel = aladin("어린왕자", "생텍쥐페리");
        List<MillieBookItem> candidates = List.of(
                item("어린왕자 : 필사노트", "", "생텍쥐페리", "소설", "", true, true),
                item("어린왕자",            "", "생텍쥐페리", "소설", "", true, true)
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().primary().contentName()).isEqualTo("어린왕자");
    }

    @Test
    void demotedKeyword_regularEditionWinsOverExtended() {
        AladinSearchResult sel = aladin("역행자", "자청");
        List<MillieBookItem> candidates = List.of(
                item("역행자 : 확장판", "자청", "자기계발"),
                item("역행자", "자청", "자기계발")
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().primary().contentName()).isEqualTo("역행자");
    }

    @Test
    void publisherMatch_wins_whenKeywordAndAvailabilityTied() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무", "민음사");
        List<MillieBookItem> candidates = List.of(
                item("인간실격", "", "다자이 오사무", "소설", "문학동네", true, true),
                item("인간실격", "", "다자이 오사무", "소설", "민음사",   true, true)
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().primary().bookBrand()).isEqualTo("민음사");
    }

    @Test
    void audiobookOnly_formatAudiobook() {
        AladinSearchResult sel = aladin("프로젝트 헤일메리", "앤디 위어");
        List<MillieBookItem> candidates = List.of(
                item("프로젝트 헤일메리", "앤디 위어", "오디오북")
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().format()).isEqualTo(Format.AUDIOBOOK);
    }

    @Test
    void ebookOnly_formatEbook() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        List<MillieBookItem> candidates = List.of(
                item("인간실격", "다자이 오사무", "소설")
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().format()).isEqualTo(Format.EBOOK);
    }

    @Test
    void ebookAndAudiobook_bothAvailable_formatBoth() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        List<MillieBookItem> candidates = List.of(
                item("인간실격", "다자이 오사무", "소설"),
                item("인간실격", "다자이 오사무", "오디오북")
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().format()).isEqualTo(Format.EBOOK_AND_AUDIOBOOK);
    }

    @Test
    void tiedCandidates_preserveInputOrder() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        List<MillieBookItem> candidates = List.of(
                item("인간실격", "", "다자이 오사무", "소설", "출판사A", true, true),
                item("인간실격", "", "다자이 오사무", "소설", "출판사B", true, true)
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().primary().bookBrand()).isEqualTo("출판사A");
    }

    @Test
    void subtitleKeyword_alsoCountsAsDemoted() {
        AladinSearchResult sel = aladin("어린왕자", "생텍쥐페리");
        List<MillieBookItem> candidates = List.of(
                item("어린왕자", "필사노트로 만나는", "생텍쥐페리", "소설", "", true, true),
                item("어린왕자", "",                   "생텍쥐페리", "소설", "", true, true)
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().primary().subtitle()).isEmpty();
    }

    @Test
    void availabilityIsSecondaryKey_picksAvailableAmongCleanCandidates() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        List<MillieBookItem> candidates = List.of(
                item("인간실격", "", "다자이 오사무", "소설", "출판사A", true, false),
                item("인간실격", "", "다자이 오사무", "소설", "출판사B", true, true)
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().primary().bookBrand()).isEqualTo("출판사B");
        assertThat(result.get().primary().isEbookRent()).isTrue();
    }

    @Test
    void format_countsOnlyAvailableMatches() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        List<MillieBookItem> candidates = List.of(
                item("인간실격", "", "다자이 오사무", "소설",     "", true, true),
                item("인간실격", "", "다자이 오사무", "오디오북", "", true, false)
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().format()).isEqualTo(Format.EBOOK);
    }

    @Test
    void keywordTier_beatsMatchPathTier_demotedNormTitleLosesToCleanPrefix() {
        // 정책 강화 검증: 키워드(1차 키)가 MatchPath(4차 키)보다 우선.
        // 만약 우선순위가 반대였다면 demoted NORM_TITLE (path=0)가 clean PREFIX (path=1)를 이김.
        // 키워드 우선 정책 하에선 clean이 항상 이김.
        AladinSearchResult sel = aladin("어린왕자", "생텍쥐페리");
        List<MillieBookItem> candidates = List.of(
                // contentName 정확 일치 (NORM_TITLE) but subtitle에 "필사" → demoted
                item("어린왕자", "필사노트로 만나는", "생텍쥐페리", "소설", "", true, true),
                // contentName 콜론 분리 → PREFIX match, subtitle 없음 → clean
                item("어린왕자 : 명작 컬렉션", "", "생텍쥐페리", "소설", "", true, true)
        );

        Optional<MatchedMillie> result = matcher.findMatch(sel, candidates);

        assertThat(result).isPresent();
        assertThat(result.get().primary().contentName()).isEqualTo("어린왕자 : 명작 컬렉션");
    }
}
