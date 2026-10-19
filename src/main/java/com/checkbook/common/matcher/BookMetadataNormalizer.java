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

    // 서점이 괄호 없이 붙이는 판 단어("초판본 데미안", "돈의 속성 400쇄 리커버", F-T21-2). EDITION_MARKERS와 달리
    // 단어로 떨어져 있을 때만 뗀다 — 어디서든 떼면 "트라우마 리커버리" → "트라우마 리". '쇄'는 숫자가 붙을 때만("연쇄" 보호)
    private static final Pattern BARE_EDITION_WORD = Pattern.compile("(?<!\\S)(?:초판본|리커버|신장판|\\d+\\s*쇄)(?!\\S)");

    private static final Pattern PUNCT = Pattern.compile(
            "[\\(\\)\\[\\]\\{\\}「」『』【】〈〉《》'\\\"\\,\\.!\\?·•~―—–\\-_/\\\\:;]"
    );

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern SUBTITLE_SPLIT = Pattern.compile(":| - ");

    // 검색어에서 떼는 판 정보 괄호. 짝이 맞는 ( ) [ ] 【】만 — 「」『』〈〉《》는 제목 속 제목 표시라 떼면 책 제목이 사라진다
    private static final Pattern EDITION_BRACKET = Pattern.compile("\\([^()]*\\)|\\[[^\\[\\]]*]|【[^【】]*】");

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
        s = BARE_EDITION_WORD.matcher(s).replaceAll(" ");

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

    /**
     * 밀리 검색어. 밀리 검색은 검색어 전체가 든 제목을 찾아서 "(양장 특별판)" 같은 서점 판 정보가 남으면 0건이 된다(F-T21-1).
     * normalizeTitle처럼 권 정보 괄호((상)·(1)·(제2권) 등)는 남기고 판 정보 괄호는 떼되, 괄호 종류는 EDITION_BRACKET으로 좁힌다.
     * 괄호를 먼저 떼야 괄호 안의 ':'에서 잘려 닫히지 않은 검색어가 되지 않는다.
     */
    public String searchKeyword(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw;
        // 중첩 괄호는 안쪽부터 한 겹씩 뗀다. 권 정보는 그대로 다시 써서 더 바뀌지 않으면 끝
        String previous;
        do {
            previous = s;
            Matcher m = EDITION_BRACKET.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(out, Matcher.quoteReplacement(isVolumeMarker(m.group()) ? m.group() : " "));
            }
            m.appendTail(out);
            s = out.toString();
        } while (!s.equals(previous));
        s = BARE_EDITION_WORD.matcher(s).replaceAll(" ");
        String keyword = WHITESPACE.matcher(SUBTITLE_SPLIT.split(s, 2)[0]).replaceAll(" ").trim();
        // 괄호·판 단어뿐인 제목은 다 떼면 비어 밀리를 부르지 않게 되므로 원래대로 둔다
        return keyword.isEmpty() ? SUBTITLE_SPLIT.split(raw, 2)[0].trim() : keyword;
    }

    private static boolean isVolumeMarker(String bracketed) {
        for (Pattern p : VOLUME_PATTERNS) {
            if (p.matcher(bracketed).matches()) return true;
        }
        return false;
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
