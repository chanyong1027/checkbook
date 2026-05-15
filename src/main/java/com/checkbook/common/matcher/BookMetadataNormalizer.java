package com.checkbook.common.matcher;

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
public class BookMetadataNormalizer {

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

    public String normalizeTitle(String raw) {
        if (raw == null) return "";
        String s = raw;

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

        s = PAREN_CONTENT.matcher(s).replaceAll(" ");

        for (String marker : EDITION_MARKERS) {
            s = s.replace(marker, " ");
        }
        s = EDITION_NUMBERED.matcher(s).replaceAll(" ");

        for (Map.Entry<String, String> e : sentinels.entrySet()) {
            s = s.replace(e.getKey(), normalizeVolumeMarker(e.getValue()));
        }

        s = PUNCT.matcher(s).replaceAll(" ");

        s = WHITESPACE.matcher(s).replaceAll("");
        return s.toLowerCase();
    }

    public String normalizeTitlePrefix(String raw) {
        if (raw == null) return "";
        String[] parts = SUBTITLE_SPLIT.split(raw, 2);
        return normalizeTitle(parts[0]);
    }

    public String searchKeyword(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String[] parts = SUBTITLE_SPLIT.split(raw, 2);
        return parts[0].trim();
    }

    public Set<String> normalizeAuthorTokens(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();

        String s = AUTHOR_PAREN_CHARS.matcher(raw).replaceAll(" ");

        List<String> sorted = new ArrayList<>(AUTHOR_ROLE_WORDS);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String role : sorted) {
            s = s.replace(role, " ");
        }

        String[] parts = AUTHOR_SPLITTER.split(s);

        Set<String> tokens = new HashSet<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            String whole = PUNCT.matcher(trimmed).replaceAll("");
            whole = WHITESPACE.matcher(whole).replaceAll("").toLowerCase();
            if (whole.length() >= 2) tokens.add(whole);

            for (String w : trimmed.split("\\s+")) {
                String word = PUNCT.matcher(w).replaceAll("").toLowerCase();
                if (word.length() >= 2) tokens.add(word);
            }
        }
        return tokens;
    }

    public String stripAuthorTokensFromTitle(String normTitle, Set<String> authorTokens) {
        List<String> sorted = new ArrayList<>(authorTokens);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String s = normTitle;
        for (String t : sorted) {
            if (t.length() < 2) continue;
            s = s.replace(t, "");
        }
        return s;
    }

    public String debugNormTitle(String raw) { return normalizeTitle(raw); }
    public String debugNormTitlePrefix(String raw) { return normalizeTitlePrefix(raw); }
    public Set<String> debugAuthorTokens(String raw) { return normalizeAuthorTokens(raw); }

    private String normalizeVolumeMarker(String raw) {
        String t = raw.replaceAll("[\\(\\)\\s]", "").toLowerCase();
        t = t.replaceAll("권|제", "");
        t = t.replaceAll("vol\\.?", "vol");
        if (t.equals("상")) return "volA";
        if (t.equals("중")) return "volB";
        if (t.equals("하")) return "volC";
        return t.startsWith("vol") ? t : ("vol" + t);
    }
}
