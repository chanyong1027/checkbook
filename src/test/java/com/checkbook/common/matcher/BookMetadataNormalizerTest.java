package com.checkbook.common.matcher;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BookMetadataNormalizerTest {

    private final BookMetadataNormalizer normalizer = new BookMetadataNormalizer();

    // 밀리 검색어(F-T21-1). 규칙은 searchKeyword 주석
    @Nested
    class SearchKeyword {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {
                // T2-1에서 밀리에 있는데 "없음"이던 책들
                "사피엔스 (양장 특별판)|사피엔스",
                "아주 작은 습관의 힘 (50만 부 기념 스페셜 에디션)|아주 작은 습관의 힘",
                "나미야 잡화점의 기적 (2021 봄날 한정판)|나미야 잡화점의 기적",
                // 제목 중간 괄호
                "독끝 NCS 통합기본서 (PSAT형+모듈형+피듈형) 공기업 대비|독끝 NCS 통합기본서 공기업 대비",
                // 붙은 괄호(10-15 보류 목록) — "1부 1권"은 권 정보 패턴이 아니라 판 정보처럼 뗀다
                "토지 1(1부 1권)|토지 1",
                // 대괄호류
                "[큰글자책] 사피엔스|사피엔스",
                "【특별판】 데미안|데미안",
                // 중첩 괄호는 안쪽부터 떼서 짝 잃은 ')'가 남지 않게
                "제목 (부제 (괄호))|제목"
        })
        void stripsEditionParentheses(String title, String expected) {
            assertThat(normalizer.searchKeyword(title)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {
                "삼국지 (상)|삼국지 (상)",
                "해리 포터와 마법사의 돌 (1)|해리 포터와 마법사의 돌 (1)",
                "토지 (제2권)|토지 (제2권)",
                "코스모스 (전 3권)|코스모스 (전 3권)",
                "해리 포터 (Vol.2)|해리 포터 (Vol.2)",
                // 권 정보는 남기고 판 정보만 뗀다
                "삼국지 (상) (양장 특별판)|삼국지 (상)"
        })
        void keepsVolumeParenthesesLikeMatcher(String title, String expected) {
            assertThat(normalizer.searchKeyword(title)).isEqualTo(expected);
        }

        @Test
        void stillCutsAtSubtitleSeparator() {
            assertThat(normalizer.searchKeyword("데미안 : 오리지널 초판본 표지디자인")).isEqualTo("데미안");
            assertThat(normalizer.searchKeyword("완벽한 자유와 부를 만드는 인생투자 - 당신의 투자에")).isEqualTo("완벽한 자유와 부를 만드는 인생투자");
        }

        // 괄호를 먼저 떼야 괄호 안의 ':'에서 잘려 "사피엔스 (양장"처럼 닫히지 않은 검색어가 되지 않는다
        @Test
        void removesParenthesesBeforeSubtitleSplit() {
            assertThat(normalizer.searchKeyword("사피엔스 (양장: 특별판)")).isEqualTo("사피엔스");
        }

        // 「」『』〈〉《》는 제목 속 제목 표시라 떼면 책 제목이 사라진다(매처 normalizeTitle과 다른 점)
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"〈미생〉 1", "『어린 왕자』를 읽고", "소설 「토지」 해설", "《논어》 강의"})
        void keepsTitleInTitleMarks(String title) {
            assertThat(normalizer.searchKeyword(title)).isEqualTo(title);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"토지 (1부", "제목 「부제)", "제목 (부제]"})
        void leavesUnpairedBracketsAsIs(String title) {
            assertThat(normalizer.searchKeyword(title)).isEqualTo(title);
        }

        // 다 떼면 검색어가 비어 밀리를 부르지 않게 되므로 원래 제목을 쓴다
        @Test
        void keepsOriginalWhenOnlyParentheses() {
            assertThat(normalizer.searchKeyword("(개정판)")).isEqualTo("(개정판)");
        }

        @Test
        void blankInputGivesEmpty() {
            assertThat(normalizer.searchKeyword(null)).isEmpty();
            assertThat(normalizer.searchKeyword("  ")).isEmpty();
        }

        // F-T21-2: YES24가 괄호 없이 붙이는 판 단어
        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {
                "초판본 데미안|데미안",
                "초판본 노인과 바다|노인과 바다",
                "돈의 속성 400쇄 리커버|돈의 속성",
                "듄 신장판 1|듄 1"
        })
        void stripsBareEditionWords(String title, String expected) {
            assertThat(normalizer.searchKeyword(title)).isEqualTo(expected);
        }

        // 단어로 떨어져 있을 때만 뗀다 — 단어 안에 든 경우와 숫자 없는 '쇄'는 제목 일부
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"트라우마 리커버리", "연쇄 살인", "봉쇄", "초판본의 비밀"})
        void keepsEditionWordsInsideOtherWords(String title) {
            assertThat(normalizer.searchKeyword(title)).isEqualTo(title);
        }

        @Test
        void keepsOriginalWhenOnlyEditionWord() {
            assertThat(normalizer.searchKeyword("리커버")).isEqualTo("리커버");
        }
    }

    // 매처 쪽도 같은 판 단어를 떼야 "초판본 데미안"과 밀리 "데미안"이 같은 제목이 된다(F-T21-2)
    @Nested
    class NormalizeTitle {

        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource(delimiter = '|', value = {
                "초판본 데미안|데미안",
                "돈의 속성 400쇄 리커버|돈의 속성",
                "돈의 속성 400쇄 개정증보판|돈의 속성",
                "듄 신장판 1|듄 1",
                // 권 표시가 바로 뒤에 와도(권 패턴이 앞 공백까지 먹어 판 단어 뒤에 붙는다) 뗀다
                "데미안 초판본 1권|데미안 1권"
        })
        void stripsBareEditionWords(String title, String sameAs) {
            assertThat(normalizer.normalizeTitle(title)).isEqualTo(normalizer.normalizeTitle(sameAs));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "트라우마 리커버리|트라우마리커버리",
                "연쇄 살인|연쇄살인"
        })
        void keepsEditionWordsInsideOtherWords(String title, String expected) {
            assertThat(normalizer.normalizeTitle(title)).isEqualTo(expected);
        }
    }
}
