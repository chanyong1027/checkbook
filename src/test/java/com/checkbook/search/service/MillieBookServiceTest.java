package com.checkbook.search.service;

import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.millie.MillieClient;
import com.checkbook.client.millie.dto.MillieBookItem;
import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.search.dto.MillieAvailability;
import com.checkbook.search.dto.MillieAvailability.Format;
import com.checkbook.search.service.MillieBookMatcher.MatchedMillie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

    @InjectMocks
    private MillieBookService service;

    private AladinSearchResult aladin(String title, String author) {
        return new AladinSearchResult(null, title, author, null, null, null);
    }

    private MillieBookItem item(boolean isService, boolean isEbookRent) {
        return new MillieBookItem(
                "SEQ001", "인간실격", "", "다자이 오사무",
                "소설", "민음사", isService, isEbookRent
        );
    }

    @Test
    void nullAladinResult_returnsUnavailable_skipsClientCall() {
        MillieAvailability result = service.findAvailability(null);

        assertThat(result.available()).isFalse();
        verify(millieClient, never()).searchByTitle(anyString());
    }

    @Test
    void blankTitle_returnsUnavailable_skipsClientCall() {
        AladinSearchResult sel = aladin("   ", "다자이 오사무");

        MillieAvailability result = service.findAvailability(sel);

        assertThat(result.available()).isFalse();
        verify(millieClient, never()).searchByTitle(anyString());
    }

    @Test
    void clientReturnsEmpty_returnsUnavailable() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        when(millieClient.searchByTitle("인간실격")).thenReturn(List.of());

        MillieAvailability result = service.findAvailability(sel);

        assertThat(result.available()).isFalse();
        verify(matcher, never()).findMatch(any(), any());
    }

    @Test
    void matcherReturnsEmpty_returnsUnavailable() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        MillieBookItem candidate = item(true, true);
        when(millieClient.searchByTitle("인간실격")).thenReturn(List.of(candidate));
        when(matcher.findMatch(sel, List.of(candidate))).thenReturn(Optional.empty());

        MillieAvailability result = service.findAvailability(sel);

        assertThat(result.available()).isFalse();
    }

    @Test
    void matchedAndAvailable_returnsAvailable_withDetailUrl() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        MillieBookItem primary = item(true, true);
        when(millieClient.searchByTitle("인간실격")).thenReturn(List.of(primary));
        when(matcher.findMatch(sel, List.of(primary)))
                .thenReturn(Optional.of(new MatchedMillie(primary, Format.EBOOK)));

        MillieAvailability result = service.findAvailability(sel);

        assertThat(result.available()).isTrue();
        assertThat(result.bookSeq()).isEqualTo("SEQ001");
        assertThat(result.detailUrl()).isEqualTo("https://www.millie.co.kr/v3/book/SEQ001");
        assertThat(result.format()).isEqualTo(Format.EBOOK);
    }

    @Test
    void matchedButComingSoon_returnsUnavailable() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        MillieBookItem primary = item(true, false); // is_ebook_rent=false → coming_soon
        when(millieClient.searchByTitle("인간실격")).thenReturn(List.of(primary));
        when(matcher.findMatch(sel, List.of(primary)))
                .thenReturn(Optional.of(new MatchedMillie(primary, Format.EBOOK)));

        MillieAvailability result = service.findAvailability(sel);

        assertThat(result.available()).isFalse();
        assertThat(result.bookSeq()).isNull();
        assertThat(result.detailUrl()).isNull();
    }

    @Test
    void matchedButNotService_returnsUnavailable() {
        AladinSearchResult sel = aladin("인간실격", "다자이 오사무");
        MillieBookItem primary = item(false, true);
        when(millieClient.searchByTitle("인간실격")).thenReturn(List.of(primary));
        when(matcher.findMatch(sel, List.of(primary)))
                .thenReturn(Optional.of(new MatchedMillie(primary, Format.EBOOK)));

        MillieAvailability result = service.findAvailability(sel);

        assertThat(result.available()).isFalse();
    }

    @Test
    void titleWithSubtitle_searchesWithPrefixOnly() {
        AladinSearchResult sel = aladin(
                "사피엔스 - 유인원에서 사이보그까지, 인간 역사의 대담하고 위대한 질문",
                "유발 하라리"
        );
        MillieBookItem primary = item(true, true);
        when(millieClient.searchByTitle("사피엔스")).thenReturn(List.of(primary));
        when(matcher.findMatch(sel, List.of(primary)))
                .thenReturn(Optional.of(new MatchedMillie(primary, Format.EBOOK)));

        MillieAvailability result = service.findAvailability(sel);

        assertThat(result.available()).isTrue();
        verify(millieClient).searchByTitle("사피엔스");
    }
}
