package com.checkbook.elibrary.service;

import com.checkbook.elibrary.dto.ELibrarySearchResponse.ELibraryBook;
import com.checkbook.elibrary.service.ELibraryBookMatcher.MatchPath;
import com.checkbook.elibrary.service.ELibraryBookMatcher.MatchResult;
import com.checkbook.elibrary.service.ELibraryBookMatcher.Selected;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ELibraryBookMatcherTest {

    private final ELibraryBookMatcher matcher = new ELibraryBookMatcher();

    private ELibraryBook book(String title, String author) {
        return new ELibraryBook(title, author, null, null, true, null);
    }

    @Nested
    class HumanLossPositives {
        private final Selected sel = new Selected("인간실격", "다자이 오사무");

        @Test
        void plainExactMatch() {
            MatchResult r = matcher.match(book("인간 실격", "다자이 오사무"), sel);
            assertThat(r.matched()).isTrue();
            assertThat(r.path()).isEqualTo(MatchPath.NORM_TITLE);
        }

        @Test
        void parenKanjiInBoth() {
            MatchResult r = matcher.match(book("인간실격(人間失格)", "太宰治 (다자이 오사무)"), sel);
            assertThat(r.matched()).isTrue();
        }

        @Test
        void authorAppendedInTitle() {
            MatchResult r = matcher.match(book("인간실격, 다자이 오사무", "다자이 오사무"), sel);
            assertThat(r.matched()).isTrue();
            assertThat(r.path()).isEqualTo(MatchPath.AUTHOR_STRIPPED);
        }

        @Test
        void colonSubtitle() {
            MatchResult r = matcher.match(book("인간실격 : 다자이 오사무", "다자이 오사무"), sel);
            assertThat(r.matched()).isTrue();
            assertThat(r.path()).isEqualTo(MatchPath.PREFIX);
        }

        @Test
        void editionMarkerInParen() {
            MatchResult r = matcher.match(book("인간 실격(초판 완역본)", "다자이 오사무"), sel);
            assertThat(r.matched()).isTrue();
        }

        @Test
        void parenLanguageVariants() {
            MatchResult r = matcher.match(book("인간 실격(한글판 영문판)", "다자이 오사무"), sel);
            assertThat(r.matched()).isTrue();
        }
    }

    @Nested
    class VolumePreservation {
        @Test
        void volumeMarkerPreserved_matchesSameVolume() {
            Selected sel = new Selected("인간실격 1권", "다자이 오사무");
            MatchResult r = matcher.match(book("인간실격 1권", "다자이 오사무"), sel);
            assertThat(r.matched()).isTrue();
        }

        @Test
        void parenVolumeMarkerPreserved() {
            Selected sel = new Selected("인간실격(1)", "다자이 오사무");
            MatchResult r = matcher.match(book("인간실격(1)", "다자이 오사무"), sel);
            assertThat(r.matched()).isTrue();
        }
    }

    @Nested
    class Negatives {
        private final Selected dazaiSel = new Selected("인간실격", "다자이 오사무");

        @Test
        void derivativeBook_japaneseStudy() {
            MatchResult r = matcher.match(
                    book("다자이 오사무의 『인간실격』 명문장 일본어 필사", "다자이 오사무"),
                    dazaiSel
            );
            assertThat(r.matched()).isFalse();
        }

        @Test
        void derivativeBook_subtitleStripFails() {
            MatchResult r = matcher.match(
                    book("필사의 힘: 다자이 오사무처럼 인간실격 따라쓰기", "다자이 오사무"),
                    dazaiSel
            );
            assertThat(r.matched()).isFalse();
        }

        @Test
        void derivativeBook_unrelatedAuthor() {
            MatchResult r = matcher.match(
                    book("아빠와 함께하는 고전 문학 독서 루틴 : 인간 실격 편", "정리남"),
                    dazaiSel
            );
            assertThat(r.matched()).isFalse();
        }

        @Test
        void sapiensKeywordNoise() {
            Selected sapiensSel = new Selected("유발 하라리 『사피엔스』: 인류의 거대한 착각", "유발 하라리");
            MatchResult r = matcher.match(
                    book("AI혁명의 시대, 사피엔스의 마지막 항해", "김도열"),
                    sapiensSel
            );
            assertThat(r.matched()).isFalse();
        }
    }

    @Nested
    class VendorParseFailureFallback {
        @Test
        void emptyAuthor_passesViaTitleOnly() {
            Selected sel = new Selected("인간실격", "다자이 오사무");
            MatchResult r = matcher.match(book("인간실격", null), sel);
            assertThat(r.matched()).isTrue();
            assertThat(r.path()).isEqualTo(MatchPath.TITLE_ONLY);
        }
    }

    @Nested
    class AuthorTokenLengthFilter {
        @Test
        void singleCharSurnameDoesNotMatch() {
            Selected sel = new Selected("어떤책", "김");
            MatchResult r = matcher.match(book("다른책", "김도열"), sel);
            assertThat(r.matched()).isFalse();
        }
    }
}
