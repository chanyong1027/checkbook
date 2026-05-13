package com.checkbook.common.matcher;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class BookMatcher {

    private final BookMetadataNormalizer normalizer;

    public record Selected(String title, String author) { }

    public enum MatchPath { NORM_TITLE, PREFIX, AUTHOR_STRIPPED, TITLE_ONLY, NONE }

    public record MatchResult(boolean matched, MatchPath path) { }

    public MatchResult match(String title, String author, Selected selected) {
        Set<String> selAuthorTokens = normalizer.normalizeAuthorTokens(selected.author());
        Set<String> candAuthorTokens = normalizer.normalizeAuthorTokens(author);

        boolean authorOk = candAuthorTokens.isEmpty()
                || hasIntersection(selAuthorTokens, candAuthorTokens);

        String selNorm = normalizer.normalizeTitle(selected.title());
        String selPrefix = normalizer.normalizeTitlePrefix(selected.title());

        String candNorm = normalizer.normalizeTitle(title);
        String candPrefix = normalizer.normalizeTitlePrefix(title);
        String candStripped = normalizer.stripAuthorTokensFromTitle(candNorm, selAuthorTokens);

        if (!authorOk) {
            return new MatchResult(false, MatchPath.NONE);
        }

        boolean candHasContent = !candNorm.isEmpty() || !candPrefix.isEmpty();
        boolean selHasContent = !selNorm.isEmpty() || !selPrefix.isEmpty();
        if (candHasContent && selHasContent
                && (selNorm.equals(candNorm) || selPrefix.equals(candNorm)
                    || selNorm.equals(candPrefix) || selPrefix.equals(candPrefix))) {
            boolean trueNormHit = !candNorm.isEmpty() && selNorm.equals(candNorm);
            boolean titleOnly = candAuthorTokens.isEmpty();
            if (titleOnly) return new MatchResult(true, MatchPath.TITLE_ONLY);
            return new MatchResult(true, trueNormHit ? MatchPath.NORM_TITLE : MatchPath.PREFIX);
        }

        if (!candStripped.isEmpty()
                && (selNorm.equals(candStripped) || selPrefix.equals(candStripped))) {
            return new MatchResult(true, candAuthorTokens.isEmpty()
                    ? MatchPath.TITLE_ONLY
                    : MatchPath.AUTHOR_STRIPPED);
        }

        return new MatchResult(false, MatchPath.NONE);
    }

    private boolean hasIntersection(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        for (String s : smaller) if (larger.contains(s)) return true;
        return false;
    }
}
