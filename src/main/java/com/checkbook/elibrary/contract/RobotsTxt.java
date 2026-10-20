package com.checkbook.elibrary.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * robots.txt 규칙 (RFC 9309). 자동 수집(주간 계약 확인)은 robots를 따른다(결정 2026-10-20).
 * 전수 조사 스크립트(survey.py)의 판정을 옮겼다 — java·python 어느 표준 라이브러리도 와일드카드와 "가장 긴 규칙 우선"을
 * 제대로 하지 않는다(urllib.robotparser는 막힌 주소를 허용으로 봤다).
 * <ul>
 *   <li>우리 UA의 제품 토큰(mozilla)과 같은 묶음, 없으면 '*' 묶음. 같은 이름 묶음은 합친다</li>
 *   <li>경로에 맞는 규칙 중 가장 긴 것이 이기고, 길이가 같으면 Allow. 빈 Disallow는 규칙이 아니다</li>
 *   <li>'*'는 아무 글자, 끝의 '$'는 끝 고정. 퍼센트 인코딩은 대소문자를 맞춰 비교한다</li>
 * </ul>
 */
public final class RobotsTxt {

    /** robots.txt가 없을 때(4xx) — 전부 허용 */
    public static final RobotsTxt ALLOW_ALL = new RobotsTxt(List.of());

    // 앱 클라이언트의 UA "Mozilla/5.0 …"의 제품 토큰
    static final String PRODUCT_TOKEN = "mozilla";
    private static final Pattern PERCENT_ENCODED = Pattern.compile("%[0-9a-fA-F]{2}");

    private final List<Group> groups;

    private record Rule(boolean allow, int length, Pattern pattern) {
    }

    private record Group(List<String> agents, List<Rule> rules) {
    }

    private RobotsTxt(List<Group> groups) {
        this.groups = groups;
    }

    public static RobotsTxt parse(String text) {
        List<Group> groups = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        boolean lastWasAgent = false;
        for (String raw : text.split("\\r?\\n|\\r")) {
            String line = raw.split("#", 2)[0].strip();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.equals("user-agent")) {
                // 규칙 뒤에 오는 user-agent는 새 묶음의 시작이다. 연달아 오는 user-agent는 한 묶음
                if (!lastWasAgent && (!agents.isEmpty() || !rules.isEmpty())) {
                    groups.add(new Group(agents, rules));
                    agents = new ArrayList<>();
                    rules = new ArrayList<>();
                }
                agents.add(value.toLowerCase(Locale.ROOT));
                lastWasAgent = true;
            } else if (key.equals("allow") || key.equals("disallow")) {
                lastWasAgent = false;
                // user-agent 앞에 나온 규칙과 빈 값은 규칙이 아니다
                if (!agents.isEmpty() && !value.isEmpty()) {
                    rules.add(new Rule(key.equals("allow"), value.length(), toPattern(value)));
                }
            }
            // 그 밖의 줄(sitemap 등)은 묶음에 영향을 주지 않는다
        }
        if (!agents.isEmpty() || !rules.isEmpty()) {
            groups.add(new Group(agents, rules));
        }
        return new RobotsTxt(List.copyOf(groups));
    }

    /** 경로(쿼리 포함, 퍼센트 인코딩된 그대로)를 우리 UA가 불러도 되는가 */
    public boolean allows(String pathAndQuery) {
        if (pathAndQuery.equals("/robots.txt")) {
            return true;
        }
        String target = normalize(pathAndQuery);
        Rule best = null;
        for (Rule rule : applicableRules()) {
            if (rule.pattern().matcher(target).lookingAt() && (best == null || rule.length() > best.length()
                    || (rule.length() == best.length() && rule.allow()))) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }

    private List<Rule> applicableRules() {
        boolean hasOwn = groups.stream().anyMatch(g -> g.agents().contains(PRODUCT_TOKEN));
        String agent = hasOwn ? PRODUCT_TOKEN : "*";
        return groups.stream()
                .filter(g -> g.agents().contains(agent))
                .flatMap(g -> g.rules().stream())
                .toList();
    }

    private static Pattern toPattern(String path) {
        boolean anchored = path.endsWith("$");
        String body = normalize(anchored ? path.substring(0, path.length() - 1) : path);
        StringBuilder regex = new StringBuilder();
        for (String part : body.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex + (anchored ? "$" : ""));
    }

    // %2f와 %2F를 같게 본다(이미 인코딩된 글자만 다룬다)
    private static String normalize(String path) {
        Matcher m = PERCENT_ENCODED.matcher(path);
        return m.replaceAll(r -> r.group().toUpperCase(Locale.ROOT));
    }
}
