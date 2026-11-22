#!/usr/bin/env python3
"""1층 측정 결과를 규칙 문서 6절(D3, 11-20 정의 보강) 판정 규칙대로 묶는다. 표준 라이브러리만 쓴다.

    python3 scripts/measure/summarize.py build/measure/before

폴더 안 파일 이름 규칙(README):
    <label>.jsonl              k6 --log-format raw --console-output 결과(MEASURE 줄)
    probe-<label>-pre.txt      그 실행 직전 server-probe.sh read 출력
    probe-<label>-post.txt     그 실행이 끝난 뒤 read 출력(read가 예산 값이 멈출 때까지 기다린다)
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
# 요청 제한 429는 측정 절차의 실패라 표본에서 빼고 "측정 중단"으로 적는다(규칙 문서 6절)
LIMIT_CODES = {"DAILY_REQUEST_LIMIT_EXCEEDED", "RATE_LIMIT_EXCEEDED"}

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
        if f.stem.endswith("-browser"):
            for r in recs:
                r["_label_position"] = f.stem[: -len("-browser")]
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
    """정보나루 호출 수·느린 성공 호출 수·성공 아닌 호출 수·버킷(누적, 전체). 칸끼리는 프로세스가 달라 더한다.

    느린 호출은 성공 호출에서만 센다 — 시간 초과는 "성공 아님"이면서 느린 호출이기도 해 두 번 세게 된다
    """
    buckets = collections.defaultdict(float)
    success_buckets = collections.defaultdict(float)
    count = success = not_success = 0.0
    for values in diff["ports"].values():
        for (name, labels), v in values.items():
            lab = dict(labels)
            ok = lab.get("outcome") in (None, "SUCCESS")
            if name == "http_client_requests_seconds_count":
                count += v
                if ok:
                    success += v
                else:
                    not_success += v
            elif name == "http_client_requests_seconds_bucket":
                le = lab.get("le")
                le = math.inf if le == "+Inf" else float(le)
                buckets[le] += v
                if ok:
                    success_buckets[le] += v
    under = [le for le in success_buckets if le <= SLOW_CALL_SECONDS]
    slow_le = max(under) if under else None
    slow = success - success_buckets[slow_le] if slow_le is not None else None
    return {"count": count, "slow": slow, "slowLe": slow_le, "notSuccess": not_success, "buckets": dict(buckets)}


def limit_hit(rec):
    """그 조회의 도서관 배치나 함께 보낸 /api/search가 요청 제한 429를 받았나(코드를 모르는 429 포함 —
    스크립트가 모든 429에서 멈춘다)"""
    lib = rec.get("library") or {}
    lib_429 = bool(lib.get("error")) and lib["error"].get("status") == 429
    return lib_429 or (rec.get("search") or {}).get("status") == 429


def items_checked(lib):
    """서버가 확인한 곳 수 = 배치 응답 항목 수의 합(화면 seen은 목표를 넘친 소장 칸 뒤를 넣지 않는다)"""
    return sum(b.get("items", 0) for b in lib.get("batches", []))


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


PLAN_PATH = Path(__file__).with_name("plan.json")


def load_plan(path=PLAN_PATH):
    return json.loads(path.read_text(encoding="utf-8"))


def expected_runs(plan):
    """{label: (pass, 기대 조회 수)} — README의 측정일 순서"""
    exp = {}
    for key, pos in plan["positions"].items():
        books = plan["books"][pos["books"]]
        for n in range(1, pos["rounds"] + 1):
            exp[f"{key}-r{n}"] = ("first", len(books))
        if pos["rounds"] > 1:  # 원주(1회차)는 두 번째 사람을 재지 않는다
            exp[f"{key}-second"] = ("second", len(books))
        exp[f"{key}-browser"] = ("browser", len({b["bin"] for b in books}))
    exp["more"] = ("more", 1)
    exp["reference"] = ("reference", 1)
    return exp


def completeness(runs, plan):
    """빠진 것의 설명 목록. 브라우저는 표본마다 시간 초과가 없고 몫이 계산돼야 유효하다"""
    out = []
    for label, (pass_name, n) in expected_runs(plan).items():
        recs = [r for r in runs.get(label, []) if r.get("pass") == pass_name and not r.get("aborted")]
        if label not in runs:
            out.append(f"{'browser ' if pass_name == 'browser' else ''}{label}: 실행 없음")
            continue
        if len(recs) < n:
            out.append(f"{'browser ' if pass_name == 'browser' else ''}{label}: 기대 {n}건 중 {len(recs)}건")
        if pass_name == "browser":
            # 개수가 아니라 묶음마다 정해진 책(그 묶음의 첫 책)의 유효한 기록이 있는지 본다
            position = label[: -len("-browser")]
            for book in required_browser_books(plan, position):
                # 서울시청과 강서구청은 같은 책 목록이라 위치까지 맞춰야 다른 위치 기록으로 대신 채워지지 않는다
                hit = [r for r in recs if r.get("isbn") == book["isbn"] and r.get("bin") == book["bin"]
                       and r.get("position") == position]
                if not hit:
                    out.append(f"browser {label}: {book['bin']} 묶음 {book['isbn']} 기록 없음")
                elif all(r.get("timedOut") or r.get("nonNetworkShareMs") is None for r in hit):
                    out.append(f"browser {label}: {book['bin']} 묶음 {book['isbn']} 몫을 못 잼")
    return out


def required_browser_books(plan, position):
    """browser-check.ts firstOfEachBin과 같은 규칙: 묶음마다 목록의 첫 책"""
    books = plan["books"][plan["positions"][position]["books"]]
    out = []
    for b in BINS:
        first = next((x for x in books if x["bin"] == b), None)
        if first:
            out.append(first)
    return out


FIRST_DISPLAY_FOR_RULE2_SEC = 2.0  # 규칙 2 문턱 T = 첫 표시 2초 − 밖 몫 m(측정 규칙 문서 8절)


def server_batch(diff):
    """서버가 잰 주변 배치 처리 시간의 (수, 합 초, 줄어든 칸이 있나) — 칸끼리 더한다(프로세스마다 따로 센다).
    칸 하나가 회차 중에 다시 떠 카운터가 0부터 시작하면 그 칸의 차이가 음수가 된다. 더하면 다른 칸의 증가에
    가려지므로 더하기 전에 칸마다 본다"""
    count = total = 0.0
    reset = False
    for values in diff["ports"].values():
        port_count = port_total = 0.0
        for (name, labels), v in values.items():
            if dict(labels).get("mode") != "NEARBY":
                continue
            if name == "library_batch_seconds_count":
                port_count += v
            elif name == "library_batch_seconds_sum":
                port_total += v
        reset = reset or port_count < 0 or port_total < 0
        count += port_count
        total += port_total
    return count, total, reset


def outside_share(runs, diffs, plan=None):
    """캐시 빈 회차의 (측정 PC 배치 응답 평균) − (서버 배치 처리 평균).

    짝을 맞추려고 측정 PC 쪽은 응답(200)을 받았고 항목이 있는 배치만 센다(서버는 빈 배치를 기록하지 않고,
    k6 시간 초과는 측정 PC 쪽에만 빠진다). 수가 안 맞거나 지표가 없는 회차는 빼고(경고에 적음) 짝이 맞는 회차로만
    m을 낸다. 캐시 빈 회차가 있는 위치마다 짝이 맞는 회차가 하나도 없으면 m을 내지 않는다(판정 보류).
    돌려주는 것: (표 줄, m 초 또는 None, 경고 목록, 참고 값 dict)
    """
    rows, warns = [], []
    client_n = client_sum = server_n = server_sum = 0.0
    first_ms = []
    per_round = []
    positions, paired_positions = set(), set()
    for label, recs in runs.items():
        firsts = [r for r in recs if r.get("pass") == "first" and "library" in r]
        if not firsts:
            continue
        positions.add(firsts[0]["position"])
        paired = [b for r in firsts for b in r["library"].get("batches", []) if b.get("status") == 200 and b.get("items", 0) > 0]
        ms = [b["ms"] for b in paired]
        first_ms += [r["library"]["batches"][0]["ms"] for r in firsts
                     if r["library"].get("batches") and r["library"]["batches"][0].get("status") == 200]
        d = diffs.get(label)
        if d is None or not d["ports"]:
            warns.append(f"{label}: 서버 지표 없음")
            continue
        n, total, reset = server_batch(d)
        if reset or n < 0 or total < 0:
            warns.append(f"{label}: 서버 카운터가 줄었다(측정 중 칸을 다시 만들었나) — 이 회차는 짝을 못 맞춘다")
            continue
        if n == 0:
            warns.append(f"{label}: 서버 library_batch 줄 없음(server-probe.sh가 이 지표를 읽는지 확인)")
            continue
        if n != len(ms):
            warns.append(f"{label}: 서버 배치 {n:.0f}건 ≠ 측정 PC 배치 {len(ms)}건(실사용이 섞였거나 시간 초과 배치) — 이 회차는 m에서 뺀다")
            rows.append((label + " (제외)", len(ms), sum(ms) / len(ms) if ms else None, n, total / n * 1000))
            continue
        paired_positions.add(firsts[0]["position"])
        rows.append((label, len(ms), sum(ms) / len(ms) if ms else None, n, total / n * 1000))
        if ms:
            per_round.append(sum(ms) / len(ms) - total / n * 1000)
        client_n += len(ms)
        client_sum += sum(ms)
        server_n += n
        server_sum += total * 1000
    ref = {"firstBatchMeanMs": sum(first_ms) / len(first_ms) if first_ms else None,
           "worstRoundMs": max(per_round) if per_round else None}
    missing = sorted(positions - paired_positions)
    if missing:
        warns.append(f"짝이 맞는 회차가 없는 위치: {missing} — m을 내지 않는다")
    # 기록이 통째로 없는 위치는 위 검사에 안 걸린다 — 계획의 캐시 빈 회차와 대조한다
    short = []
    if plan is not None:
        for label, (pass_name, n_books) in expected_runs(plan).items():
            got = sum(1 for r in runs.get(label, []) if r.get("pass") == pass_name and "library" in r)
            if pass_name == "first" and got < n_books:
                short.append(f"{label} {got}/{n_books}")
        if short:
            warns.append(f"계획보다 모자란 캐시 빈 회차: {short} — m을 내지 않는다")
    if missing or short or not client_n or not server_n:
        return rows, None, warns, ref
    return rows, (client_sum / client_n - server_sum / server_n) / 1000, warns, ref


def rule2_threshold(m):
    """T = 2.0 − m을 소수 둘째 자리로 내림(관대하게 올리지 않게). 0 < T < 2.0이 아니면 None(m이 말이 안 됨).

    m은 ms 단위 평균이라 정수 ms로 맞춘 뒤 정수로 계산한다 — 부동소수 오차가 내림을 한 칸 바꾸는 일이 없다
    """
    m_ms = round(m * 1000)
    t_cs = (int(FIRST_DISPLAY_FOR_RULE2_SEC * 1000) - m_ms) // 10
    return t_cs / 100 if 0 < t_cs < int(FIRST_DISPLAY_FOR_RULE2_SEC * 100) else None


def main(folder):
    folder = Path(folder)
    runs = load_records(folder)
    out = []
    p = out.append
    p(f"# 1층 결과 요약 — `{folder}`\n")

    diffs = {label: probe_diff(load_probe(folder / f"probe-{label}-pre.txt"), load_probe(folder / f"probe-{label}-post.txt"))
             for label in runs}

    p("## 회차별 정보나루 호출 (서버 지표 차이가 주 값, 응답 항목 수는 보조)\n")
    p("| 실행 | 조회 | 서버 예산 차이 | 응답 항목 수 | 정보나루 호출(지표) | 느린 호출 | 성공 아님 | 표시 |")
    p("|---|---|---|---|---|---|---|---|")
    ext_evidence = {}
    for label, recs in runs.items():
        lookups = [r for r in recs if "library" in r]
        checked = sum(items_checked(r["library"]) for r in lookups) + sum(
            items_checked(x) for r in recs for x in r.get("rounds", []))
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
            if not cells:
                ext_evidence[label] = False
            elif cs["notSuccess"] >= cells:
                ext_evidence[label] = True  # 실패 호출만으로 설명된다
            elif cs["slow"] is None:
                ext_evidence[label] = None  # 버킷이 없어 느린 호출을 못 셌다 — 0으로 보지 않고 보류
                notes.append("⚠느린 호출 버킷 없음")
            else:
                ext_evidence[label] = cs["slow"] + cs["notSuccess"] >= cells
        if d["switched"]:
            notes.append(f"⚠칸 전환 {sorted(d['switched'])}")
        if any(r.get("pass") == "first" for r in lookups) and used != checked:
            notes.append(f"⚠예산≠항목 수({used - checked:+,.0f})")
        p(f"| {label} | {len(lookups)} | {used:,.0f} | {checked} | {cs['count']:,.0f} | {cs['slow'] if cs['slow'] is not None else '—'} "
          f"| {cs['notSuccess']:,.0f} | {' '.join(notes)} |")
    p("")

    rows, m, warns, ref = outside_share(runs, diffs, load_plan())
    if rows or warns:
        p("## 규칙 2 다시 판정용: 지역 조회 밖 몫 (측정 규칙 문서 8절)\n")
        p("| 실행 | 측정 PC 배치 수 | 측정 PC 평균 ms | 서버 배치 수 | 서버 평균 ms |")
        p("|---|---|---|---|---|")
        for label, n_c, mean_c, n_s, mean_s in rows:
            p(f"| {label} | {n_c} | {rng([mean_c])} | {n_s:.0f} | {mean_s:,.0f} |")
        for w in warns:
            p(f"- ⚠ {w}")
        t = rule2_threshold(m) if m is not None else None
        if m is None:
            p("\n- **판정 보류**: 짝이 맞는 회차가 없는 위치가 있거나 계획보다 회차가 모자라 m을 내지 않는다(위 경고의 원인을 본 뒤 그 회차를 다시 잰다)")
        elif t is None:
            p(f"\n- **판정 보류**: m = {m * 1000:,.0f}ms라 T = 2.0 − m이 0~2초 밖이다(짝이 어긋났을 수 있다)")
        else:
            p(f"\n- 밖 몫 m = {m * 1000:,.0f}ms → 규칙 2 새 문턱 T = 2.0 − m(내림) = **{t:.2f}초** "
              f"(다시 판정: `python3 scripts/measure/api_check.py --summarize <파일> --rule2-target {t:.2f}`)")
            if ref["worstRoundMs"] is not None:
                p(f"- 참고(판정 밖): 회차별 밖 몫 중 가장 큰 값 {ref['worstRoundMs']:,.0f}ms → T {rule2_threshold(ref['worstRoundMs'] / 1000) or '범위 밖'}초. "
                  f"첫 배치만의 측정 PC 평균 {ref['firstBatchMeanMs']:,.0f}ms(검색과 동시에 나가고 첫 연결 수립이 든다)")
            p("- 한계 [가정]: 서버 배치 처리 시간에는 DB 읽기·응답 구성이 들어 있어 m에서 함께 빠진다. 후 구조에서 지역 조회 단계 밖에 있을 "
              "DB 일(지역 목록 표 읽기·쓰기, 거리 정렬)은 m에 안 잡혀 T가 그만큼 관대하다. 최종 판정은 1층 후의 첫 표시 직접 측정이다.")
        p("")

    browser = [r for recs in runs.values() for r in recs if r.get("pass") == "browser"]
    share_by_pos = collections.defaultdict(float)
    for r in browser:
        # 파일 이름(label)의 위치와 기록의 위치가 다른 표본은 몫 계산에 쓰지 않는다(completeness가 누락으로 잡는다)
        if r.get("nonNetworkShareMs") is not None and r.get("_label_position", r["position"]) == r["position"]:
            share_by_pos[r["position"]] = max(share_by_pos[r["position"]], r["nonNetworkShareMs"])
    use_browser = bool(share_by_pos) and max(share_by_pos.values()) > RENDER_SHARE_LIMIT_MS

    # 계획(plan.json)과 대조: 빠진 실행·모자란 조회·무효 브라우저 표본이 있으면 판정을 보류한다
    missing = completeness(runs, load_plan())
    browser_missing = [m for m in missing if m.startswith("browser")]
    if missing:
        p("## ⚠ 표본 부족 — 판정 보류\n")
        for m in missing:
            p(f"- {m}")
        p("")

    stopped = [r for recs in runs.values() for r in recs if r.get("aborted")]
    limited = [r for recs in runs.values() for r in recs if "library" in r and limit_hit(r)]
    if stopped or limited:
        p(f"## ⚠ 측정 중단\n\n- 요청 제한으로 멈춘 실행 {[(r['position'], r.get('round'), r['aborted']) for r in stopped]}, "
          f"제한에 걸린 조회 {len(limited)}건(표본에서 뺌). 그 회차를 다시 잴 때까지 그날 판정은 보류한다.\n")

    def lookups(pass_names):
        for label, recs in runs.items():
            for r in recs:
                if r.get("pass") in pass_names and "library" in r and not limit_hit(r):
                    yield label, r

    for title, passes in (("판정 표본: 캐시 빈 회차 + 기준 도서관", {"first", "reference"}), ("따로: 두 번째 사람(캐시 적중)", {"second"})):
        sample = list(lookups(passes))
        if not sample:
            continue
        kinds = {id(r): unresolved_kind(r["library"], ext_evidence.get(label)) for label, r in sample}
        p(f"## {title}\n")
        p("| 위치 | 묶음 | 조회 | 첫 표시 ms | 확정 ms(TIME 뺌) | 확인 곳 수(1회차) | 멈춤 사유 |")
        p("|---|---|---|---|---|---|---|")
        groups = collections.defaultdict(list)
        for _, r in sample:
            groups[(r["position"], r["bin"] if r["pass"] != "reference" else "reference")].append(r)
        order = BINS + ["reference"]
        for (pos, b), rs in sorted(groups.items(), key=lambda kv: (kv[0][0], order.index(kv[0][1]) if kv[0][1] in order else 99)):
            stops = collections.Counter(r["library"]["stop"] for r in rs)
            p(f"| {pos} | {b} | {len(rs)} | {rng(r['library']['firstDisplayMs'] for r in rs)} "
              f"| {rng(r['library']['settledMs'] for r in rs if kinds[id(r)] != 'time')} "
              f"| {rng(r['library']['checked'] for r in rs if r['round'] == 1)} | {', '.join(f'{k} {v}' for k, v in stops.most_common())} |")
        p("")
        fd = [r["library"]["firstDisplayMs"] for _, r in sample]
        if use_browser:
            fd = [None if v is None else v + share_by_pos.get(r["position"], max(share_by_pos.values())) for v, (_, r) in zip(fd, sample)]
        no_fd = collections.Counter(r["library"]["stop"] for _, r in sample if r["library"]["firstDisplayMs"] is None)
        # 확정 시간 표본은 TIME이 아닌 조회 전부(미확정도 사용자는 멈출 때까지 기다렸다). 확정된 조회만은 참고
        st = [r["library"]["settledMs"] for _, r in sample if kinds[id(r)] != "time"]
        st_ok = [r["library"]["settledMs"] for _, r in sample if kinds[id(r)] is None]
        v1, d1 = judge_times(fd, FIRST_DISPLAY_TARGET_MS)
        v2, d2 = judge_times(st, SETTLED_TARGET_MS)
        if (stopped or limited) and passes != {"second"}:
            v1, v2 = f"판정 보류({v1}, 측정 중단)", f"판정 보류({v2}, 측정 중단)"
        elif missing and passes != {"second"}:
            why = "표본 부족, 브라우저 포함" if browser_missing else "표본 부족"
            v1, v2 = f"판정 보류({v1}, {why})", f"판정 보류({v2}, 표본 부족)"
        _, d2_ok = judge_times(st_ok, SETTLED_TARGET_MS)
        p(f"- 첫 표시{'(브라우저 몫을 더한 값)' if use_browser else ''}: **{v1}** — {d1}. 첫 표시로 세지 않은 조회: {dict(no_fd) or '없음'}")
        p(f"- 확정: **{v2}** — {d2}. 참고(확정된 조회만): {d2_ok}")
        time_stops = [r for _, r in sample if r["library"]["stop"] == "TIME"]
        p(f"- `TIME` 종료(따로 적음): {len(time_stops)}건" + (f", 그때 보인 소장 수 {rng(r['library']['found'] for r in time_stops)}" if time_stops else ""))
        n = len(sample)
        c = collections.Counter(kinds.values())
        ext, ours, und = c.get("external", 0), c.get("ours", 0), c.get("undetermined", 0)
        if stopped or limited:
            verdict = "판정 보류(측정 중단 — 위 경고)"
        elif missing and passes != {"second"}:
            verdict = "판정 보류(표본 부족 — 위 경고)"
        elif und:
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

    for r in (r for recs in runs.values() for r in recs if r.get("pass") == "more" and "rounds" in r):
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
