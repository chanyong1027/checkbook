package com.checkbook.search.service;

import com.checkbook.search.dto.IdentifiedBook;
import com.checkbook.client.millie.MillieClient;
import com.checkbook.client.millie.MillieClientException;
import com.checkbook.client.millie.dto.MillieBookItem;
import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.search.dto.MillieAvailability;
import com.checkbook.search.dto.MillieAvailability.Format;
import com.checkbook.search.service.MillieBookMatcher.MatchedMillie;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MillieBookServiceTest {

    @Mock
    private MillieClient millieClient;

    @Mock
    private MillieBookMatcher matcher;

    @Spy
    private BookMetadataNormalizer normalizer = new BookMetadataNormalizer();

    @Spy
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private MillieBookService service;

    private IdentifiedBook aladin(String title, String author) {
        return new IdentifiedBook(title, author, null, null, null);
    }

    private MillieBookItem item(boolean isService, boolean isEbookRent) {
        return new MillieBookItem(
                "SEQ001", "인간실격", "", "다자이 오사무",
                "소설", "민음사", isService, isEbookRent
        );
    }

    // ===== 힌트 제목 보조 매칭 (T1 결정 E8) =====
    // YES24가 짧은 제목을 주면(예: 부제 앞 문구가 빠짐) 제목 완전일치 매처가 실패한다.
    // 같은 후보 목록에서 힌트 제목으로 한 번 더 맞춘다 — 밀리 추가 호출 없음

    @Test
    void primaryNoMatch_hintTitleMatchesOnSameCandidates_withoutSecondSearch() {
        IdentifiedBook primary = aladin("투자", "홍길동");
        IdentifiedBook hint = aladin("부자가 되는 투자", "홍길동");
        List<MillieBookItem> candidates = List.of(item(true, true));
        when(millieClient.searchByTitle("투자")).thenReturn(candidates);
        when(matcher.findMatch(primary, candidates)).thenReturn(Optional.empty());
        when(matcher.findMatch(argThat(b -> b != null && "부자가 되는 투자".equals(b.title())), eq(candidates)))
                .thenReturn(Optional.of(new MatchedMillie(item(true, true), Format.EBOOK)));

        MillieAvailability result = service.findAvailability(primary, hint);

        assertThat(result.available()).isTrue();
        verify(millieClient, times(1)).searchByTitle(anyString());
        assertThat(meterRegistry.counter("millie.match", "via", "hint_fallback").count()).isEqualTo(1.0);
    }

    // 보조 매칭은 힌트의 '제목'만 빌린다. 저자·출판사는 검증된 YES24 값 — 클라이언트 값이 저자 판정을 바꾸지 못하게
    @Test
    void hintFallbackBorrowsOnlyTitle_keepsVerifiedAuthorAndPublisher() {
        IdentifiedBook primary = new IdentifiedBook("투자", "YES24 저자", "9788900000009", "YES24출판", null);
        IdentifiedBook hint = new IdentifiedBook("부자가 되는 투자", "조작된 저자", "9788900000009", "다른출판", null);
        List<MillieBookItem> candidates = List.of(item(true, true));
        when(millieClient.searchByTitle("투자")).thenReturn(candidates);
        when(matcher.findMatch(any(), any())).thenReturn(Optional.empty());

        service.findAvailability(primary, hint);

        ArgumentCaptor<IdentifiedBook> captor = ArgumentCaptor.forClass(IdentifiedBook.class);
        verify(matcher, times(2)).findMatch(captor.capture(), eq(candidates));
        assertThat(captor.getAllValues().get(1)).isEqualTo(
                new IdentifiedBook("부자가 되는 투자", "YES24 저자", "9788900000009", "YES24출판", null));
    }

    @Test
    void hintAuthorUsedOnlyWhenPrimaryAuthorMissing() {
        IdentifiedBook primary = new IdentifiedBook("투자", null, "9788900000009", null, null);
        IdentifiedBook hint = new IdentifiedBook("부자가 되는 투자", "힌트 저자", "9788900000009", "힌트출판", null);
        List<MillieBookItem> candidates = List.of(item(true, true));
        when(millieClient.searchByTitle("투자")).thenReturn(candidates);
        when(matcher.findMatch(any(), any())).thenReturn(Optional.empty());

        service.findAvailability(primary, hint);

        ArgumentCaptor<IdentifiedBook> captor = ArgumentCaptor.forClass(IdentifiedBook.class);
        verify(matcher, times(2)).findMatch(captor.capture(), eq(candidates));
        assertThat(captor.getAllValues().get(1).author()).isEqualTo("힌트 저자");
        assertThat(captor.getAllValues().get(1).publisher()).isEqualTo("힌트출판");
    }

    @Test
    void emptyCandidatesCountedAsNoCandidates() {
        when(millieClient.searchByTitle("투자")).thenReturn(List.of());

        service.findAvailability(aladin("투자", "홍길동"), null);

        assertThat(meterRegistry.counter("millie.match", "via", "no_candidates").count()).isEqualTo(1.0);
    }

    @Test
    void primaryMatches_hintNotTried() {
        IdentifiedBook primary = aladin("인간실격", "다자이 오사무");
        IdentifiedBook hint = aladin("인간 실격 (특별판)", "다자이 오사무");
        List<MillieBookItem> candidates = List.of(item(true, true));
        when(millieClient.searchByTitle("인간실격")).thenReturn(candidates);
        when(matcher.findMatch(primary, candidates)).thenReturn(Optional.of(new MatchedMillie(item(true, true), Format.EBOOK)));

        MillieAvailability result = service.findAvailability(primary, hint);

        assertThat(result.available()).isTrue();
        verify(matcher, never()).findMatch(eq(hint), any());
        assertThat(meterRegistry.counter("millie.match", "via", "primary").count()).isEqualTo(1.0);
    }

    @Test
    void neitherMatches_unavailable_countedAsNone() {
        IdentifiedBook primary = aladin("투자", "홍길동");
        IdentifiedBook hint = aladin("다른 책", "홍길동");
        List<MillieBookItem> candidates = List.of(item(true, true));
        when(millieClient.searchByTitle("투자")).thenReturn(candidates);
        when(matcher.findMatch(any(), any())).thenReturn(Optional.empty());

        MillieAvailability result = service.findAvailability(primary, hint);

        assertThat(result.available()).isFalse();
        assertThat(meterRegistry.counter("millie.match", "via", "none").count()).isEqualTo(1.0);
    }

    @Test
    void blankHintTitle_isNotTried() {
        IdentifiedBook primary = aladin("투자", "홍길동");
        IdentifiedBook hint = aladin(" ", "홍길동");
        List<MillieBookItem> candidates = List.of(item(true, true));
        when(millieClient.searchByTitle("투자")).thenReturn(candidates);
        when(matcher.findMatch(primary, candidates)).thenReturn(Optional.empty());

        assertThat(service.findAvailability(primary, hint).available()).isFalse();
        verify(matcher, never()).findMatch(eq(hint), any());
    }

    @Test
    void nullAladinResult_returnsUnavailable_skipsClientCall() {
        MillieAvailability result = service.findAvailability(null, null);

        assertThat(result.available()).isFalse();
        verify(millieClient, never()).searchByTitle(anyString());
    }

    @Test
    void blankTitle_returnsUnavailable_skipsClientCall() {
        IdentifiedBook sel = aladin("   ", "다자이 오사무");

        MillieAvailability result = service.findAvailability(sel, null);

        assertThat(result.available()).isFalse();
        verify(millieClient, never()).searchByTitle(anyString());
    }

    // 클라이언트 실패는 '밀리에 없음'(unavailable)으로 바꾸지 않고 전파한다 → SearchService가 SUBSCRIPTION FAILED로 표시
    @Test
    void clientFailure_propagates_notUnavailable() {
        IdentifiedBook sel = aladin("인간실격", "다자이 오사무");
        when(millieClient.searchByTitle("인간실격")).thenThrow(new MillieClientException("밀리 HTTP 500"));

        assertThatThrownBy(() -> service.findAvailability(sel, null))
                .isInstanceOf(MillieClientException.class);
        verify(matcher, never()).findMatch(any(), any());
    }

    @Test
    void clientReturnsEmpty_returnsUnavailable() {
        IdentifiedBook sel = aladin("인간실격", "다자이 오사무");
        when(millieClient.searchByTitle("인간실격")).thenReturn(List.of());

        MillieAvailability result = service.findAvailability(sel, null);

        assertThat(result.available()).isFalse();
        verify(matcher, never()).findMatch(any(), any());
    }

    @Test
    void matcherReturnsEmpty_returnsUnavailable() {
        IdentifiedBook sel = aladin("인간실격", "다자이 오사무");
        MillieBookItem candidate = item(true, true);
        when(millieClient.searchByTitle("인간실격")).thenReturn(List.of(candidate));
        when(matcher.findMatch(sel, List.of(candidate))).thenReturn(Optional.empty());

        MillieAvailability result = service.findAvailability(sel, null);

        assertThat(result.available()).isFalse();
    }

    @Test
    void matchedAndAvailable_returnsAvailable_withDetailUrl() {
        IdentifiedBook sel = aladin("인간실격", "다자이 오사무");
        MillieBookItem primary = item(true, true);
        when(millieClient.searchByTitle("인간실격")).thenReturn(List.of(primary));
        when(matcher.findMatch(sel, List.of(primary)))
                .thenReturn(Optional.of(new MatchedMillie(primary, Format.EBOOK)));

        MillieAvailability result = service.findAvailability(sel, null);

        assertThat(result.available()).isTrue();
        assertThat(result.bookSeq()).isEqualTo("SEQ001");
        assertThat(result.detailUrl()).isEqualTo("https://www.millie.co.kr/v3/book/SEQ001");
        assertThat(result.format()).isEqualTo(Format.EBOOK);
    }

    @Test
    void matchedButComingSoon_returnsUnavailable() {
        IdentifiedBook sel = aladin("인간실격", "다자이 오사무");
        MillieBookItem primary = item(true, false); // is_ebook_rent=false → coming_soon
        when(millieClient.searchByTitle("인간실격")).thenReturn(List.of(primary));
        when(matcher.findMatch(sel, List.of(primary)))
                .thenReturn(Optional.of(new MatchedMillie(primary, Format.EBOOK)));

        MillieAvailability result = service.findAvailability(sel, null);

        assertThat(result.available()).isFalse();
        assertThat(result.bookSeq()).isNull();
        assertThat(result.detailUrl()).isNull();
    }

    @Test
    void matchedButNotService_returnsUnavailable() {
        IdentifiedBook sel = aladin("인간실격", "다자이 오사무");
        MillieBookItem primary = item(false, true);
        when(millieClient.searchByTitle("인간실격")).thenReturn(List.of(primary));
        when(matcher.findMatch(sel, List.of(primary)))
                .thenReturn(Optional.of(new MatchedMillie(primary, Format.EBOOK)));

        MillieAvailability result = service.findAvailability(sel, null);

        assertThat(result.available()).isFalse();
    }

    @Test
    void titleWithSubtitle_searchesWithPrefixOnly() {
        IdentifiedBook sel = aladin(
                "사피엔스 - 유인원에서 사이보그까지, 인간 역사의 대담하고 위대한 질문",
                "유발 하라리"
        );
        MillieBookItem primary = item(true, true);
        when(millieClient.searchByTitle("사피엔스")).thenReturn(List.of(primary));
        when(matcher.findMatch(sel, List.of(primary)))
                .thenReturn(Optional.of(new MatchedMillie(primary, Format.EBOOK)));

        MillieAvailability result = service.findAvailability(sel, null);

        assertThat(result.available()).isTrue();
        verify(millieClient).searchByTitle("사피엔스");
    }
}
