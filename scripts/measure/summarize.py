#!/usr/bin/env python3
"""1층 측정 결과를 규칙 문서 6절(D3, 11-20 정의 보강) 판정 규칙대로 묶는다. 표준 라이브러리만 쓴다.

    python3 scripts/measure/summarize.py build/measure/before

폴더 안 파일 이름 규칙(README):
    <label>.jsonl              k6 --log-format raw --console-output 결과(MEASURE 줄)
    probe-<label>-pre.txt      그 실행 직전 server-probe.sh read 출력
    probe-<label>-post.txt     그 실행이 끝나고 15초 뒤 read 출력
label 예: seoul-cityhall-r1, seoul-cityhall-second, seoul-cityhall-browser, more, reference

판정 숫자는 규칙 문서와 목표 카드 3의 값이다. 바꾸려면 결과를 보기 전에 문서부터 고친다.
"""
import collections
import json
import math
import re
import sys
from pathlib import Path

FIRST_DISPLAY_TARGET_MS = 2_000
SETTLED_TARGET_MS = 5_000
SHARE = 0.95
MAX_FACTOR = 2  # 가장 느린 값은 기준의 2배 이하(D3)
EXTERNAL_ALLOWED = 0.01  # 외부 원인 미확정 조회 비율 상한
RENDER_SHARE_LIMIT_MS = 300  # 브라우저 확인 판정선(D5)
EXTERNAL_REASONS = {"TIMEOUT", "UPSTREAM_ERROR"}
# 외부 지연 근거: 이 값 이하에서 가장 큰 버킷(기본 히스토그램이면 1.07초)을 넘은 정보나루 호출. 마감은 1.2초
SLOW_CALL_SECONDS = 1.1
UNRESOLVED_STOPS = {"ERROR", "ALL_UNKNOWN", "DAILY_LIMIT", "CIRCUIT_OPEN"}
BINS = ["common", "middle", "rare", "absent"]

PROBE_LINE = re.compile(r"^(\S+) port=(\d+) ([a-zA-Z_:][a-zA-Z0-9_:]*)\{(.*)\} (\S+)$")
LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"')


def load_records(folder):
    runs = {}
    for f in sorted(folder.glob("*.jsonl")):
        recs = []
        for line in f.read_text(encoding="utf-8").splitlines():
            i = line.find("MEASURE ")
            if i >= 0:
                recs.append(json.loads(line[i + len("MEASURE "):]))
        runs[f.stem] = recs
    return runs


def load_probe(path):
    """{port: {(name, frozenset(labels)): value}}"""
    if not path.exists():
        return None
    out = collections.defaultdict(dict)
    for line in path.read_text(encoding="utf-8").splitlines():
        m = PROBE_LINE.match(line.strip())
        if not m:
            continue
        _, port, name, labels, value = m.groups()
        key = (name, frozenset(LABEL.findall(labels)))
        out[port][key] = out[port].get(key, 0.0) + float(value)
    return dict(out)


def probe_diff(pre, post):
    """칸마다 앞뒤 차이. 한쪽에만 있는 칸은 뺀다(측정 중 칸 전환 — 표에 표시)"""
    if pre is None or post is None:
        return None
    ports = set(pre) & set(post)
    per_port = {}
    for port in ports:
        a, b = pre[port], post[port]
        per_port[port] = {k: b.get(k, 0.0) - a.get(k, 0.0) for k in set(a) | set(b)}
    return {"ports": per_port, "switched": set(pre) ^ set(post)}


def budget_used(diff):
    """예산 게이지는 칸마다 DB와 맞춘 같은 하루 합계라 더하지 않고 큰 값을 쓴다"""
    vals = [sum(v for (name, _), v in values.items() if name == "external_budget_used") for values in diff["ports"].values()]
    return max(vals) if vals else 0.0


def client_stats(diff):
    """정보나루 호출 수·느린 호출 수·성공 아닌 호출 수·버킷(누적). 칸끼리는 프로세스가 달라 더한다"""
    buckets = collections.defaultdict(float)
    count = not_success = 0.0
    for values in diff["ports"].values():
        for (name, labels), v in values.items():
            lab = dict(labels)
            if name == "http_client_requests_seconds_count":
                count += v
                if lab.get("outcome") not in (None, "SUCCESS"):
                    not_success += v
            elif name == "http_client_requests_seconds_bucket":
                le = lab.get("le")
                buckets[math.inf if le == "+Inf" else float(le)] += v
    under = [le for le in buckets if le <= SLOW_CALL_SECONDS]
    slow_le = max(under) if under else None
    slow = count - buckets[slow_le] if slow_le is not None else None
    return {"count": count, "slow": slow, "slowLe": slow_le, "notSuccess": not_success, "buckets": dict(buckets)}


def quantile(buckets, q):
    """누적 버킷에서 선형 보간(초)"""
    items = sorted(buckets.items())
    if not items or items[-1][1] <= 0:
        return None
    total = items[-1][1]
    target = q * total
    prev_le, prev_c = 0.0, 0.0
    for le, c in items:
        if c >= target:
            if math.isinf(le):
                return prev_le
            span = c - prev_c
            return prev_le + (le - prev_le) * ((target - prev_c) / span if span else 0)
        prev_le, prev_c = le, c
    return items[-1][0]


def rng(values):
    vals = [v for v in values if v is not None]
    if not vals:
        return "—"
    lo, hi = min(vals), max(vals)
    return f"{lo:,.0f}" if lo == hi else f"{lo:,.0f}~{hi:,.0f}"


def judge_times(values, target):
    vals = sorted(v for v in values if v is not None)
    if not vals:
        return "표본 없음", None
    within = sum(1 for v in vals if v <= target) / len(vals)
    ok = within >= SHARE and vals[-1] <= target * MAX_FACTOR
    return ("통과" if ok else "미달"), f"{len(vals)}건 중 {within:.1%}가 {target:,}ms 이하, 최대 {vals[-1]:,.0f}ms(상한 {target * MAX_FACTOR:,}ms)"


def external_cells(lib):
    return sum(n for reason, n in lib["unknownReasons"].items() if reason in EXTERNAL_REASONS)


def unresolved_kind(lib, ext_evidence):
    """None(확정) | 'time'(따로 셈) | 'external' | 'ours' | 'undetermined'(지표가 없어 외부 근거를 못 봄)

    ext_evidence: True(그 회차 지표가 외부 칸 수를 설명함) | False(지표는 있으나 근거 부족) | None(지표 없음)
    """
    if lib["stop"] == "TIME":
        return "time"
    if lib["stop"] == "ERROR" or lib["error"]:
        return "ours"  # 우리 서버 4xx·5xx·연결 실패
    if lib["unknown"] == 0 and lib["stop"] not in UNRESOLVED_STOPS:
        return None
    reasons = set(lib["unknownReasons"])
    if lib["stop"] in UNRESOLVED_STOPS - {"ALL_UNKNOWN"}:
        return "ours"  # 한도·서킷(우리 예산·우리 서킷이 막음)
    if reasons and reasons <= EXTERNAL_REASONS:
        if ext_evidence is None:
            return "undetermined"
        return "external" if ext_evidence else "ours"
    return "ours"


def main(folder):
    folder = Path(folder)
    runs = load_records(folder)
    out = []
    p = out.append
    p(f"# 1층 결과 요약 — `{folder}`\n")

    diffs = {label: probe_diff(load_probe(folder / f"probe-{label}-pre.txt"), load_probe(folder / f"probe-{label}-post.txt"))
             for label in runs}

    p("## 회차별 정보나루 호출 (서버 지표 차이가 주 값, 응답으로 센 확인 수는 보조)\n")
    p("| 실행 | 조회 | 서버 예산 차이 | 응답으로 센 확인 수 | 정보나루 호출(지표) | 느린 호출 | 성공 아님 | 표시 |")
    p("|---|---|---|---|---|---|---|---|")
    ext_evidence = {}
    for label, recs in runs.items():
        lookups = [r for r in recs if "library" in r]
        checked = sum(r["library"]["checked"] for r in lookups) + sum(r.get("totalChecked", 0) for r in recs if "rounds" in r)
        aborted = [r for r in recs if r.get("aborted")]
        d = diffs[label]
        notes = []
        if aborted:
            notes.append(f"중단 {aborted[0]['aborted']}(남은 {len(aborted[0]['remaining'])}권)")
        if d is None or not d["ports"]:
            ext_evidence[label] = None
            p(f"| {label} | {len(lookups)} | (지표 없음) | {checked} | — | — | — | {' '.join(notes)} |")
            continue
        cs = client_stats(d)
        used = budget_used(d)
        if cs["count"] == 0 and checked > 0:
            ext_evidence[label] = None  # 정보나루 호출 줄이 비었다 — 판정 불가
            notes.append("⚠정보나루 호출 지표 없음")
        else:
            cells = sum(external_cells(r["library"]) for r in lookups)
            evidence = (cs["slow"] or 0) + cs["notSuccess"]
            ext_evidence[label] = evidence >= cells if cells else False
        if d["switched"]:
            notes.append(f"⚠칸 전환 {sorted(d['switched'])}")
        if any(r.get("pass") == "first" for r in lookups) and used != checked:
            notes.append(f"⚠예산≠확인 수({used - checked:+,.0f})")
        p(f"| {label} | {len(lookups)} | {used:,.0f} | {checked} | {cs['count']:,.0f} | {cs['slow'] if cs['slow'] is not None else '—'} "
          f"| {cs['notSuccess']:,.0f} | {' '.join(notes)} |")
    p("")

    browser = [r for recs in runs.values() for r in recs if r.get("pass") == "browser"]
    share_by_pos = collections.defaultdict(float)
    for r in browser:
        if r.get("nonNetworkShareMs") is not None:
            share_by_pos[r["position"]] = max(share_by_pos[r["position"]], r["nonNetworkShareMs"])
    use_browser = bool(share_by_pos) and max(share_by_pos.values()) > RENDER_SHARE_LIMIT_MS

    def lookups(pass_names):
        for label, recs in runs.items():
            for r in recs:
                if r.get("pass") in pass_names and "library" in r:
                    yield label, r

    for title, passes in (("판정 표본: 캐시 빈 회차 + 기준 도서관", {"first", "reference"}), ("따로: 두 번째 사람(캐시 적중)", {"second"})):
        sample = list(lookups(passes))
        if not sample:
            continue
        kinds = {id(r): unresolved_kind(r["library"], ext_evidence.get(label)) for label, r in sample}
        p(f"## {title}\n")
        p("| 위치 | 묶음 | 조회 | 첫 표시 ms | 확정 ms(확정된 조회만) | 확인 곳 수(1회차) | 멈춤 사유 |")
        p("|---|---|---|---|---|---|---|")
        groups = collections.defaultdict(list)
        for _, r in sample:
            groups[(r["position"], r["bin"] if r["pass"] != "reference" else "reference")].append(r)
        order = BINS + ["reference"]
        for (pos, b), rs in sorted(groups.items(), key=lambda kv: (kv[0][0], order.index(kv[0][1]) if kv[0][1] in order else 99)):
            stops = collections.Counter(r["library"]["stop"] for r in rs)
            p(f"| {pos} | {b} | {len(rs)} | {rng(r['library']['firstDisplayMs'] for r in rs)} "
              f"| {rng(r['library']['settledMs'] for r in rs if kinds[id(r)] is None)} "
              f"| {rng(r['library']['checked'] for r in rs if r['round'] == 1)} | {', '.join(f'{k} {v}' for k, v in stops.most_common())} |")
        p("")
        fd = [r["library"]["firstDisplayMs"] for _, r in sample]
        if use_browser:
            fd = [None if v is None else v + share_by_pos.get(r["position"], max(share_by_pos.values())) for v, (_, r) in zip(fd, sample)]
        no_fd = collections.Counter(r["library"]["stop"] for _, r in sample if r["library"]["firstDisplayMs"] is None)
        st = [r["library"]["settledMs"] for _, r in sample if kinds[id(r)] is None]
        v1, d1 = judge_times(fd, FIRST_DISPLAY_TARGET_MS)
        v2, d2 = judge_times(st, SETTLED_TARGET_MS)
        p(f"- 첫 표시{'(브라우저 몫을 더한 값)' if use_browser else ''}: **{v1}** — {d1}. 첫 표시로 세지 않은 조회: {dict(no_fd) or '없음'}")
        p(f"- 확정: **{v2}** — {d2}")
        time_stops = [r for _, r in sample if r["library"]["stop"] == "TIME"]
        p(f"- `TIME` 종료(따로 적음): {len(time_stops)}건" + (f", 그때 보인 소장 수 {rng(r['library']['found'] for r in time_stops)}" if time_stops else ""))
        n = len(sample)
        c = collections.Counter(kinds.values())
        ext, ours, und = c.get("external", 0), c.get("ours", 0), c.get("undetermined", 0)
        if und:
            verdict = f"판정 보류(지표 없는 회차의 외부 사유 {und}건)"
        elif ext / n > EXTERNAL_ALLOWED:
            verdict = "그날 측정 무효(외부 원인 1% 초과)"
        elif ours:
            verdict = "미달(우리 원인)"
        else:
            verdict = "통과"
        p(f"- 미확정: 우리 원인 {ours}건, 외부 원인 {ext}건({ext / n:.1%}, 허용 {EXTERNAL_ALLOWED:.0%}), 판정 불가 {und}건 → **{verdict}**")
        reasons = collections.Counter()
        for _, r in sample:
            reasons.update(r["library"]["unknownReasons"])
        if reasons:
            p(f"- 확인 못 함 칸의 사유: {dict(reasons)}")
        searches = collections.Counter(r["search"]["status"] for _, r in sample)
        p(f"- 함께 보낸 /api/search 상태: {dict(searches)}\n")

    for r in (r for recs in runs.values() for r in recs if r.get("pass") == "more"):
        p("## 더 찾기 끝까지\n")
        p(f"- {r['position']} {r['isbn']}({r['bin']}): 탐색 {len(r['rounds'])}번, 확인 {r['totalChecked']}곳, 소장 {r['totalFound']}곳, "
          f"마지막 멈춤 {r['rounds'][-1]['stop']}, 탐색마다 확정 {rng(x['settledMs'] for x in r['rounds'])}ms\n")

    if browser:
        p("## 브라우저 확인 (D5: 네트워크 밖 몫 ≤ 300ms면 프로토콜 값으로 판정)\n")
        p("| 위치 | 묶음 | 첫 배치 보낸 시각 | 네트워크 밖 몫 ms | 그중 마지막 그리기 ms | 시간 초과 |")
        p("|---|---|---|---|---|---|")
        for r in browser:
            p(f"| {r['position']} | {r['bin']} | {rng([r['firstBatchStartMs']])} | {rng([r.get('nonNetworkShareMs')])} "
              f"| {rng([r['renderShareMs'] if r['renderShareMs'] is not None else r['noneRenderShareMs']])} | {'예' if r['timedOut'] else ''} |")
        if share_by_pos:
            worst = max(share_by_pos.values())
            p(f"\n- 네트워크 밖 몫 최대 {worst:,.0f}ms → **{'프로토콜 값으로 판정' if not use_browser else '첫 표시에 위치별 최대 몫을 더해 판정'}**")
        p(f"- 참고(판정 밖): 페이지를 연 뒤 첫 배치를 보내기까지 {rng(r['firstBatchStartMs'] for r in browser)}ms(페이지 로드·위치 확인). 프로토콜 측정의 시작점(0)은 첫 요청을 보낸 때다.\n")

    total = collections.defaultdict(float)
    for d in diffs.values():
        if d is not None and d["ports"]:
            for le, cnt in client_stats(d)["buckets"].items():
                total[le] += cnt
    if total:
        q50, q95 = quantile(total, 0.5), quantile(total, 0.95)
        fmt = lambda q: "—" if q is None else f"{q * 1000:,.0f}ms"  # noqa: E731
        p("## 그날 정보나루 호출 시간 (전후 두 날 비교, 규칙 문서 5절)\n")
        p(f"- 측정 회차 동안 서버가 부른 정보나루 호출: p50 {fmt(q50)}, p95 {fmt(q95)} (히스토그램 버킷 보간). 전후 어느 쪽이든 1.5배 넘게 다르면 결과에 적는다.\n")

    print("\n".join(out))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1])
