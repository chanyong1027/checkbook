package com.checkbook.elibrary.service;

import com.checkbook.elibrary.dto.ELibrarySearchResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ELibraryBookMatcher {

    public record Selected(String title, String author) { }

    public enum MatchPath { NORM_TITLE, PREFIX, AUTHOR_STRIPPED, TITLE_ONLY, NONE }

    public record MatchResult(boolean matched, MatchPath path) { }

    private static final List<Pattern> VOLUME_PATTERNS = List.of(
            Pattern.compile("\\(\\s*(상|중|하)\\s*\\)"),
            Pattern.compile("\\(\\s*제?\\s*\\d+\\s*권\\s*\\)"),
            Pattern.compile("\\(\\s*전\\s*\\d+\\s*권\\s*\\)"),
            Pattern.compile("\\(\\s*vol\\.?\\s*\\d+\\s*\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\(\\s*\\d+\\s*\\)"),
            Pattern.compile("(?<![\\(])\\b제?\\s*\\d+\\s*권\\b")
    );

    private static final Pattern PAREN_CONTENT = Pattern.compile(
            "[\\(\\[\\{【「『〈《][^\\)\\]\\}】」』〉》]*[\\)\\]\\}】」』〉》]"
    );

    private static final List<String> EDITION_MARKERS = List.of(
            "개정증보판", "개정판", "증보판", "완전판", "특별판", "최신판",
            "리뉴얼판", "한정판", "양장본", "양장"
    );

    private static final Pattern EDITION_NUMBERED = Pattern.compile("제?\\s*\\d+\\s*판");

    private static final Pattern PUNCT = Pattern.compile(
            "[\\(\\)\\[\\]\\{\\}「」『』【】〈〉《》'\\\"\\,\\.!\\?·•~―—–\\-_/\\\\:;]"
    );

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern SUBTITLE_SPLIT = Pattern.compile(":| - ");

    private static final List<String> AUTHOR_ROLE_WORDS = List.of(
            "지음", "옮김", "번역", "편역", "엮음", "편저", "공저", "감수", "원저",
            "저자", "그림", "글", "편", "저"
    );

    private static final Pattern AUTHOR_PAREN_CHARS = Pattern.compile(
            "[\\(\\)【】「」『』〈〉《》]"
    );

    private static final Pattern AUTHOR_SPLITTER = Pattern.compile(
            ",|/|·|\\||&|\\s외\\s|\\s외$"
    );

    public MatchResult match(ELibrarySearchResponse.ELibraryBook candidate, Selected selected) {
        Set<String> selAuthorTokens = normalizeAuthorTokens(selected.author());
        Set<String> candAuthorTokens = normalizeAuthorTokens(candidate.author());

        boolean authorOk = candAuthorTokens.isEmpty()  // vendor parse failure tolerance
                || hasIntersection(selAuthorTokens, candAuthorTokens);

        String selNorm = normalizeTitle(selected.title());
        String selPrefix = normalizeTitlePrefix(selected.title());

        String candNorm = normalizeTitle(candidate.title());
        String candPrefix = normalizeTitlePrefix(candidate.title());
        String candStripped = stripAuthorTokensFromTitle(candNorm, selAuthorTokens);

        if (!authorOk) {
            return new MatchResult(false, MatchPath.NONE);
        }

        if (selNorm.equals(candNorm) || selPrefix.equals(candNorm)
                || selNorm.equals(candPrefix) || selPrefix.equals(candPrefix)) {
            boolean isPrefixHit = !selNorm.equals(candNorm);
            boolean titleOnly = candAuthorTokens.isEmpty();
            if (titleOnly) return new MatchResult(true, MatchPath.TITLE_ONLY);
            return new MatchResult(true, isPrefixHit ? MatchPath.PREFIX : MatchPath.NORM_TITLE);
        }

        if (!candStripped.isEmpty()
                && (selNorm.equals(candStripped) || selPrefix.equals(candStripped))) {
            // 벤더가 저자 파싱에 실패(빈 저자)했고 제목에 저자명이 섞여있는 케이스도
            // 동일하게 통과시킨다. 이때 path는 TITLE_ONLY로 — 파서 drift 신호가 더 우선.
            return new MatchResult(true, candAuthorTokens.isEmpty()
                    ? MatchPath.TITLE_ONLY
                    : MatchPath.AUTHOR_STRIPPED);
        }

        return new MatchResult(false, MatchPath.NONE);
    }

    public String debugNormTitle(String raw) { return normalizeTitle(raw); }
    public String debugNormTitlePrefix(String raw) { return normalizeTitlePrefix(raw); }
    public Set<String> debugAuthorTokens(String raw) { return normalizeAuthorTokens(raw); }

    private String normalizeTitle(String raw) {
        if (raw == null) return "";
        String s = raw;

        // 1. Replace volume markers with sentinels
        Map<String, String> sentinels = new LinkedHashMap<>();
        int idx = 0;
        for (Pattern p : VOLUME_PATTERNS) {
            Matcher m = p.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String key = "⟪VOL" + (idx++) + "⟫";
                sentinels.put(key, m.group());
                m.appendReplacement(out, Matcher.quoteReplacement(key));
            }
            m.appendTail(out);
            s = out.toString();
        }

        // 2. Strip paren content
        s = PAREN_CONTENT.matcher(s).replaceAll(" ");

        // 3. Strip edition markers (longest-first already in list)
        for (String marker : EDITION_MARKERS) {
            s = s.replace(marker, " ");
        }
        s = EDITION_NUMBERED.matcher(s).replaceAll(" ");

        // 4. Restore sentinels (with normalized volume marker)
        for (Map.Entry<String, String> e : sentinels.entrySet()) {
            s = s.replace(e.getKey(), normalizeVolumeMarker(e.getValue()));
        }

        // 5. Strip punctuation
        s = PUNCT.matcher(s).replaceAll(" ");

        // 6. Strip whitespace + lowercase
        s = WHITESPACE.matcher(s).replaceAll("");
        return s.toLowerCase();
    }

    private String normalizeVolumeMarker(String raw) {
        String t = raw.replaceAll("[\\(\\)\\s]", "").toLowerCase();
        t = t.replaceAll("권|제", "");
        t = t.replaceAll("vol\\.?", "vol");
        if (t.equals("상")) return "volA";
        if (t.equals("중")) return "volB";
        if (t.equals("하")) return "volC";
        return t.startsWith("vol") ? t : ("vol" + t);
    }

    private String normalizeTitlePrefix(String raw) {
        if (raw == null) return "";
        String[] parts = SUBTITLE_SPLIT.split(raw, 2);
        return normalizeTitle(parts[0]);
    }

    private Set<String> normalizeAuthorTokens(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();

        // 1. Strip paren CHARS only (keep content)
        String s = AUTHOR_PAREN_CHARS.matcher(raw).replaceAll(" ");

        // 2. Strip role words (longest first)
        List<String> sorted = new ArrayList<>(AUTHOR_ROLE_WORDS);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String role : sorted) {
            s = s.replace(role, " ");
        }

        // 3. Split by author separators
        String[] parts = AUTHOR_SPLITTER.split(s);

        Set<String> tokens = new HashSet<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            // 4-1. Whole-form (no whitespace, no punct, lowercased)
            String whole = PUNCT.matcher(trimmed).replaceAll("");
            whole = WHITESPACE.matcher(whole).replaceAll("").toLowerCase();
            if (whole.length() >= 2) tokens.add(whole);

            // 4-2. Whitespace-split words
            for (String w : trimmed.split("\\s+")) {
                String word = PUNCT.matcher(w).replaceAll("").toLowerCase();
                if (word.length() >= 2) tokens.add(word);
            }
        }
        return tokens;
    }

    private boolean hasIntersection(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        for (String s : smaller) if (larger.contains(s)) return true;
        return false;
    }

    private String stripAuthorTokensFromTitle(String normTitle, Set<String> authorTokens) {
        String s = normTitle;
        for (String t : authorTokens) {
            if (t.length() < 2) continue;
            s = s.replace(t, "");
        }
        return s;
    }
}
