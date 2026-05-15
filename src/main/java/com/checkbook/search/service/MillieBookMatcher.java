package com.checkbook.search.service;

import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.millie.dto.MillieBookItem;
import com.checkbook.common.matcher.BookMatcher;
import com.checkbook.common.matcher.BookMatcher.MatchPath;
import com.checkbook.common.matcher.BookMatcher.MatchResult;
import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.search.dto.MillieAvailability.Format;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class MillieBookMatcher {

    // 후순위 키워드 11개. EDITION_MARKERS("개정증보판", "개정판", "증보판",
    // "완전판", "특별판", "최신판", "리뉴얼판", "한정판", "양장본", "양장")는
    // 의도적으로 제외 — BookMetadataNormalizer.normalizeTitle이 정규화 단계에서
    // 이를 통째로 제거해 본책과 동치로 매칭되기 때문. edition demotion이 강하게
    // 필요해지면 후속 PR에서 별도 정책으로 도입.
    private static final Set<String> DEMOTED_KEYWORDS = Set.of(
            "필사", "노트", "해설", "요약", "합본", "원전", "완역",
            "확장", "리커버", "에디션", "세트"
    );

    private final BookMatcher baseMatcher;
    private final BookMetadataNormalizer normalizer;

    public Optional<MatchedMillie> findMatch(
            AladinSearchResult selected,
            List<MillieBookItem> candidates
    ) {
        BookMatcher.Selected sel = new BookMatcher.Selected(selected.title(), selected.author());

        List<Scored> matched = candidates.stream()
                .map(item -> {
                    MatchResult r = baseMatcher.match(item.contentName(), item.author(), sel);
                    return r.matched() ? new Scored(item, r.path()) : null;
                })
                .filter(Objects::nonNull)
                .toList();

        if (matched.isEmpty()) return Optional.empty();

        // stream.sorted는 ordered stream에 대해 stable → 동점 시 입력 순서 보존
        Scored primary = matched.stream()
                .sorted(comparator(selected))
                .findFirst()
                .orElseThrow();

        return Optional.of(new MatchedMillie(primary.item(), computeFormat(matched)));
    }

    private Comparator<Scored> comparator(AladinSearchResult selected) {
        // 각 키는 "낮은 값이 좋은 후보" 방향. Boolean false < true이므로
        // false가 best일 땐 그대로, true가 best일 땐 부정 또는 매핑.
        return Comparator
                .<Scored, Boolean>comparing(s -> hasDemotedKeyword(s.item()))           // 1차: false(키워드 없음) 우선
                .thenComparing(s -> !isAvailable(s.item()))                              // 2차: true(available) 우선
                .thenComparing(s -> !brandMatches(s.item(), selected))                   // 3차: true(일치) 우선
                .thenComparing(s -> matchPathOrder(s.path()))                            // 4차: NORM_TITLE(0)부터
                .thenComparing(s -> "오디오북".equals(s.item().category()));             // 5차: false(전자책) 우선
        // 6차 동점 → stream.sorted의 안정성으로 입력 순서 보존
    }

    private boolean hasDemotedKeyword(MillieBookItem item) {
        String contentName = item.contentName() == null ? "" : item.contentName();
        String subtitle = item.subtitle() == null ? "" : item.subtitle();
        // 청사진 §"구현 메모": BookMetadataNormalizer.normalizeTitle 산출물에 키워드
        // 부분 문자열이 있는지 확인. 양쪽(haystack + 키워드) 같은 normalize 적용으로
        // 공백·구두점 차이("역행자 : 확장판", "필사 노트" 등)를 모두 잡는다.
        // EDITION_MARKERS 처리에 대해선 DEMOTED_KEYWORDS 정의부 주석 참조.
        String haystack = normalizer.normalizeTitle(contentName + " " + subtitle);
        for (String kw : DEMOTED_KEYWORDS) {
            if (haystack.contains(normalizer.normalizeTitle(kw))) return true;
        }
        return false;
    }

    private boolean isAvailable(MillieBookItem item) {
        return item.isService() && item.isEbookRent();
    }

    private boolean brandMatches(MillieBookItem item, AladinSearchResult selected) {
        if (item.bookBrand() == null || selected.publisher() == null) return false;
        return normalize(item.bookBrand()).equals(normalize(selected.publisher()));
    }

    private static String normalize(String s) {
        return s.replaceAll("[\\s\\p{Punct}]", "").toLowerCase();
    }

    private static int matchPathOrder(MatchPath path) {
        return switch (path) {
            case NORM_TITLE -> 0;
            case PREFIX -> 1;
            case AUTHOR_STRIPPED -> 2;
            case TITLE_ONLY -> 3;
            case NONE -> 4;
        };
    }

    private Format computeFormat(List<Scored> matched) {
        List<MillieBookItem> availableMatches = matched.stream()
                .map(Scored::item)
                .filter(this::isAvailable)
                .toList();

        boolean hasEbook = availableMatches.stream().anyMatch(m -> !"오디오북".equals(m.category()));
        boolean hasAudio = availableMatches.stream().anyMatch(m -> "오디오북".equals(m.category()));

        // availableMatches가 비어 있으면 둘 다 false → AUDIOBOOK으로 fallthrough.
        // 그러나 그 경우 service의 `isService && isEbookRent` 가드가 unavailable() 반환해
        // format은 외부 미노출. format 필드는 "available 후보 ≥1개일 때만 유효".
        return (hasEbook && hasAudio) ? Format.EBOOK_AND_AUDIOBOOK
                : hasEbook ? Format.EBOOK
                : Format.AUDIOBOOK;
    }

    private record Scored(MillieBookItem item, MatchPath path) { }

    public record MatchedMillie(MillieBookItem primary, Format format) { }
}
