#!/usr/bin/env python3
"""1층 측정 결과를 규칙 문서 6절(D3) 판정 규칙대로 묶는다. 표준 라이브러리만 쓴다.

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
SLOW_CALL_SECONDS = 1.0  # 외부 지연 근거: 이 값을 넘은 정보나루 호출(마감 1.2초 근처)
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
    """{(name, frozenset(labels)): value} — 칸(port)끼리는 더한다(평소 한 칸, 전환 중이면 둘)"""
    if not path.exists():
        return None
    out = collections.defaultdict(float)
    ports = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        m = PROBE_LINE.match(line.strip())
        if not m:
            continue
        _, port, name, labels, value = m.groups()
        ports.add(port)
        out[(name, frozenset(LABEL.findall(labels)))] += float(value)
    return {"values": out, "ports": ports}


def probe_diff(pre, post):
    if pre is None or post is None:
        return None
    d = {k: post["values"].get(k, 0.0) - pre["values"].get(k, 0.0) for k in set(pre["values"]) | set(post["values"])}
    return {"values": d, "ports": pre["ports"] | post["ports"]}


def budget_used(diff):
    return sum(v for (name, _), v in diff["values"].items() if name == "external_budget_used")


def client_stats(diff):
    """정보나루 호출 수, 느린 호출 수, 성공 아닌 호출 수, 버킷(누적)"""
    buckets = collections.defaultdict(float)
    count = slow_le = 0.0
    not_success = 0.0
    for (name, labels), v in diff["values"].items():
        lab = dict(labels)
        if name == "http_client_requests_seconds_count":
            count += v
            if lab.get("outcome") not in (None, "SUCCESS"):
                not_success += v
        elif name == "http_client_requests_seconds_bucket":
            le = lab.get("le")
            buckets[math.inf if le == "+Inf" else float(le)] += v
    # SLOW_CALL_SECONDS 이하 버킷(가장 가까운 것)으로 느린 호출을 센다
    under = [le for le in buckets if le <= SLOW_CALL_SECONDS]
    if under:
        slow_le = max(under)
    slow = count - buckets.get(slow_le, count) if under else None
    return {"count": count, "slow": slow, "slowLe": slow_le if under else None, "notSuccess": not_success, "buckets": dict(buckets)}


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


def unresolved_kind(lib, ext_evidence):
    """None(확정) | 'external' | 'ours'. TIME 종료는 따로 세므로 여기서 다루지 않는다"""
    if lib["stop"] == "ERROR" or lib["error"]:
        return "ours"  # 우리 서버 4xx·5xx·연결 실패(D3: 근거 없는 것은 우리 원인)
    if lib["unknown"] == 0 and lib["stop"] not in ("ALL_UNKNOWN", "DAILY_LIMIT", "CIRCUIT_OPEN"):
        return None
    reasons = set(lib["unknownReasons"])
    if reasons and reasons <= EXTERNAL_REASONS and ext_evidence:
        return "external"
    return "ours"


def main(folder):
    folder = Path(folder)
    runs = load_records(folder)
    out = []
    p = out.append
    p(f"# 1층 결과 요약 — `{folder}`\n")

    # 회차별 서버 지표 차이
    diffs = {}
    for label in runs:
        diffs[label] = probe_diff(load_probe(folder / f"probe-{label}-pre.txt"), load_probe(folder / f"probe-{label}-post.txt"))

    p("## 회차별 정보나루 호출 (서버 지표 차이가 주 값, 응답 항목 수는 보조)\n")
    p("| 실행 | 조회 | 서버 `external.budget.used` 차이 | 응답으로 센 확인 수 | 정보나루 호출(지표) | 1초 넘음 | 성공 아님 | 칸 |")
    p("|---|---|---|---|---|---|---|---|")
    ext_evidence = {}
    for label, recs in runs.items():
        lookups = [r for r in recs if "library" in r]
        checked = sum(r["library"]["checked"] for r in lookups) + sum(
            sum(x["checked"] for x in r.get("rounds", [])) for r in recs if "rounds" in r)
        d = diffs[label]
        if d is None:
            p(f"| {label} | {len(recs)} | (지표 없음) | {checked} | — | — | — | — |")
            ext_evidence[label] = False
            continue
        cs = client_stats(d)
        ext_evidence[label] = bool((cs["slow"] or 0) > 0 or cs["notSuccess"] > 0)
        warn = " ⚠둘" if len(d["ports"]) > 1 else ""
        p(f"| {label} | {len(recs)} | {budget_used(d):,.0f} | {checked} | {cs['count']:,.0f} | {cs['slow'] if cs['slow'] is not None else '—'} | {cs['notSuccess']:,.0f} | {','.join(sorted(d['ports']))}{warn} |")
    p("")

    # 판정 표본: first 전부 + reference. second는 따로
    def lookups(pass_names):
        for label, recs in runs.items():
            for r in recs:
                if r.get("pass") in pass_names and "library" in r:
                    yield label, r

    for title, passes in (("판정 표본: 캐시 빈 회차 + 기준 도서관", {"first", "reference"}), ("따로: 두 번째 사람(캐시 적중)", {"second"})):
        sample = list(lookups(passes))
        if not sample:
            continue
        p(f"## {title}\n")
        p("| 위치 | 묶음 | 조회 | 첫 표시 ms | 확정 ms | 확인 곳 수(1회차) | 멈춤 사유 |")
        p("|---|---|---|---|---|---|---|")
        groups = collections.defaultdict(list)
        for label, r in sample:
            groups[(r["position"], r["bin"] if r["pass"] != "reference" else "reference")].append(r)
        for (pos, b), rs in sorted(groups.items(), key=lambda kv: (kv[0][0], (BINS + ["reference"]).index(kv[0][1]) if kv[0][1] in BINS + ["reference"] else 99)):
            stops = collections.Counter(r["library"]["stop"] for r in rs)
            p(f"| {pos} | {b} | {len(rs)} | {rng(r['library']['firstDisplayMs'] for r in rs)} | {rng(r['library']['settledMs'] for r in rs)} "
              f"| {rng(r['library']['checked'] for r in rs if r['round'] == 1)} | {', '.join(f'{k} {v}' for k, v in stops.most_common())} |")
        p("")
        fd = [r["library"]["firstDisplayMs"] for _, r in sample]
        no_fd = collections.Counter(r["library"]["stop"] for _, r in sample if r["library"]["firstDisplayMs"] is None)
        st = [r["library"]["settledMs"] for _, r in sample]
        v1, d1 = judge_times(fd, FIRST_DISPLAY_TARGET_MS)
        v2, d2 = judge_times(st, SETTLED_TARGET_MS)
        p(f"- 첫 표시: **{v1}** — {d1}. 첫 표시로 세지 않은 종료: {dict(no_fd) or '없음'}")
        p(f"- 확정: **{v2}** — {d2}")
        time_stops = [r for _, r in sample if r["library"]["stop"] == "TIME"]
        p(f"- `TIME` 종료(따로 적음): {len(time_stops)}건" + (f", 그때 보인 소장 수 {rng(r['library']['found'] for r in time_stops)}" if time_stops else ""))
        kinds = collections.Counter(unresolved_kind(r["library"], ext_evidence.get(label, False)) for label, r in sample)
        n = len(sample)
        ext = kinds.get("external", 0)
        ours = kinds.get("ours", 0)
        verdict = "통과" if ours == 0 and ext / n <= EXTERNAL_ALLOWED else ("그날 측정 무효(외부 원인 1% 초과)" if ext / n > EXTERNAL_ALLOWED else "미달(우리 원인)")
        p(f"- 미확정: 우리 원인 {ours}건, 외부 원인 {ext}건({ext / n:.1%}, 허용 {EXTERNAL_ALLOWED:.0%}) → **{verdict}**")
        reasons = collections.Counter()
        for _, r in sample:
            reasons.update(r["library"]["unknownReasons"])
        if reasons:
            p(f"- 확인 못 함 칸의 사유: {dict(reasons)}")
        searches = collections.Counter(r["search"]["status"] for _, r in sample)
        p(f"- 함께 보낸 /api/search 상태: {dict(searches)}\n")

    more = [r for recs in runs.values() for r in recs if r.get("pass") == "more"]
    for r in more:
        p("## 더 찾기 끝까지\n")
        p(f"- {r['position']} {r['isbn']}({r['bin']}): 탐색 {len(r['rounds'])}번, 확인 {r['totalChecked']}곳, 소장 {r['totalFound']}곳, "
          f"마지막 멈춤 {r['rounds'][-1]['stop']}, 탐색마다 확정 {rng(x['settledMs'] for x in r['rounds'])}ms\n")

    browser = [r for recs in runs.values() for r in recs if r.get("pass") == "browser"]
    if browser:
        p("## 브라우저 확인 (D5: 렌더링 몫 ≤ 300ms면 프로토콜 값으로 판정)\n")
        p("| 위치 | 묶음 | 첫 배치 보낸 시각 | 소장 응답 → 그려짐 ms | 없음 응답 → 그려짐 ms | 시간 초과 |")
        p("|---|---|---|---|---|---|")
        for r in browser:
            p(f"| {r['position']} | {r['bin']} | {rng([r['firstBatchStartMs']])} | {rng([r['renderShareMs']])} | {rng([r['noneRenderShareMs']])} | {'예' if r['timedOut'] else ''} |")
        shares = [x for r in browser for x in (r["renderShareMs"], r["noneRenderShareMs"]) if x is not None]
        if shares:
            worst = max(shares)
            p(f"\n- 렌더링 몫 최대 {worst:,.0f}ms → **{'프로토콜 값으로 판정' if worst <= RENDER_SHARE_LIMIT_MS else '첫 표시를 브라우저 값으로 판정'}**")
        p(f"- 참고(판정 밖): 페이지를 연 뒤 첫 배치를 보내기까지 {rng(r['firstBatchStartMs'] for r in browser)}ms(페이지 로드·위치 확인). 프로토콜 측정의 시작점(0)은 첫 요청을 보낸 때다.\n")

    # 두 날 비교용: 그날 전체 정보나루 호출 시간
    all_diffs = [d for d in diffs.values() if d is not None]
    if all_diffs:
        total = collections.defaultdict(float)
        for d in all_diffs:
            for le, c in client_stats(d)["buckets"].items():
                total[le] += c
        q50, q95 = quantile(total, 0.5), quantile(total, 0.95)
        p("## 그날 정보나루 호출 시간 (전후 두 날 비교, 규칙 문서 5절)\n")
        p(f"- 측정 회차 동안 서버가 부른 정보나루 호출: p50 {q50 * 1000 if q50 is not None else float('nan'):,.0f}ms, "
          f"p95 {q95 * 1000 if q95 is not None else float('nan'):,.0f}ms (히스토그램 버킷 보간)\n")

    print("\n".join(out))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1])
