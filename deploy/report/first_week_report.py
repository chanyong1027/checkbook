#!/usr/bin/env python3
"""CheckBook 첫 주 지표 보고 — 서버에서 하루(KST) 값을 한 번에 모아 출력한다. 읽기만 한다.

    sudo python3 /opt/checkbook/app/deploy/report/first_week_report.py               # 어제(KST)
    sudo python3 /opt/checkbook/app/deploy/report/first_week_report.py --date 2026-11-15

원천: Nginx 접근·오류 로그(요청 수·IP별 분포·시간대·Nginx 거절), journald 앱 로그(밀리·요청 제한·예산·섹션 실패),
DB external_call_budget(공급자 하루 사용량), 액추에이터(앱이 뜬 뒤 합계로만 있는 값).
드문 사건은 로그 줄 수와 DB 값으로 센다 — Grafana의 increase()는 처음 생긴 카운터의 첫 값을 놓친다.
원천마다 살아 있는지 먼저 보고, 죽었으면 값 대신 '수집 안 됨'과 이유를 찍는다(0과 구분).
IP는 개수만 출력하고 원문은 출력·저장하지 않는다. 결정: notes/decisions/2026-11-13-첫주지표-결정카드.md(로컬 문서)
표준 라이브러리만 쓴다(서버 python3 그대로).
"""
import argparse
import collections
import dataclasses
import datetime as dt
import glob
import gzip
import math
import os
import re
import subprocess
import sys
import urllib.request

try:
    from zoneinfo import ZoneInfo
    KST = ZoneInfo("Asia/Seoul")
except Exception:  # tzdata가 없는 환경. 한국은 서머타임이 없어 고정 +9와 같다
    KST = dt.timezone(dt.timedelta(hours=9))

# DB는 지금 돌고 있는 스택의 compose 파일로 붙는다(래퍼가 /opt/checkbook/current → releases/<커밋>로 둔다, backup.sh와 같다).
# 저장소 HEAD(/opt/checkbook/app)의 compose.yaml은 배포 전 새 변수를 요구할 수 있다. 없으면(로컬 등) 이 파일 옆을 쓴다
CURRENT_DEPLOY_DIR = "/opt/checkbook/current/deploy"
LOCAL_DEPLOY_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SLOTS = (("blue", 8081), ("green", 8082))
APP_CONTAINERS = ("checkbook-app-blue-1", "checkbook-app-green-1")
SECTIONS = ("PUBLIC_LIBRARY", "USED_BOOK", "NEW_BOOK", "SUBSCRIPTION")  # SearchSection enum
HEALTH_PATH = "/api/health"  # 외부 헬스 체크(UptimeRobot). 사용량에서 빼고 '로그가 살아 있나'에만 쓴다
DETAIL_PATH = "/api/search"  # 책 한 권 상세(네 칸)
SEARCH_PATH = "/api/books/search"  # 키워드 검색 목록


def day_window(day):
    """KST 하루 [00:00, 다음 날 00:00)를 UTC 시각으로"""
    start = dt.datetime.combine(day, dt.time(0), KST)
    return start.astimezone(dt.timezone.utc), (start + dt.timedelta(days=1)).astimezone(dt.timezone.utc)


def _in(ts, start, end):
    return start <= ts < end


# ---------- Nginx 접근 로그 ----------

ACCESS_RE = re.compile(r'^(?P<ip>\S+) \S+ \S+ \[(?P<time>[^\]]+)\] "(?P<method>[A-Z]+) (?P<target>\S+)[^"]*" (?P<status>\d{3}) ')


@dataclasses.dataclass
class AccessSummary:
    alive: bool
    api_total: int
    detail: int
    search: int
    unique_ips: int
    detail_ips: int
    detail_per_ip: tuple  # (평균, 중앙, 90%, 최대) 또는 None. 2xx만
    not_ok: int  # /api/ 중 2xx가 아닌 응답(429·스캐너 4xx·5xx) — 상세·검색·IP별 분포에서 뺀다
    hourly: list  # KST 0~23시 /api/ 요청 수(헬스 체크 제외)
    peak_hour: tuple  # (시, 건) 또는 None
    status_429: int


def percentile(sorted_values, q):
    """nearest-rank 백분위수(값 중 하나를 고른다)"""
    return sorted_values[max(0, math.ceil(q * len(sorted_values)) - 1)]


def summarize_access(lines, start, end):
    seen_any = False
    ips, hourly = set(), [0] * 24
    per_ip = collections.Counter()
    api_total = detail = search = status_429 = not_ok = 0
    for line in lines:
        m = ACCESS_RE.match(line)
        if not m:
            continue
        try:
            ts = dt.datetime.strptime(m["time"], "%d/%b/%Y:%H:%M:%S %z")
        except ValueError:
            continue
        if not _in(ts, start, end):
            continue
        seen_any = True
        path = m["target"].split("?", 1)[0]
        if path == HEALTH_PATH or not path.startswith("/api/"):
            continue
        api_total += 1
        ips.add(m["ip"])
        hourly[ts.astimezone(KST).hour] += 1
        if m["status"] == "429":
            status_429 += 1
        if not m["status"].startswith("2"):
            not_ok += 1  # 거절된 요청이 '1인당 조회 수' 가정 교체 값을 부풀리지 않게
            continue
        if path == DETAIL_PATH:
            detail += 1
            per_ip[m["ip"]] += 1
        elif path == SEARCH_PATH:
            search += 1
    dist = None
    if per_ip:
        counts = sorted(per_ip.values())
        dist = (round(sum(counts) / len(counts), 2), counts[(len(counts) - 1) // 2], percentile(counts, 0.9), counts[-1])
    peak = None
    if api_total:
        hour = max(range(24), key=lambda h: hourly[h])
        peak = (hour, hourly[hour])
    return AccessSummary(seen_any, api_total, detail, search, len(ips), len(per_ip), dist, not_ok, hourly, peak,
                         status_429)


# ---------- Nginx 오류 로그(요청 제한 거절) ----------

LIMIT_RE = re.compile(r'^(?P<time>\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}) \[\w+\] .*?limiting requests(?P<dry>, dry run)?, '
                      r'excess: \S+ by zone "(?P<zone>[^"]+)", client: (?P<client>[^,\s]+)')


@dataclasses.dataclass
class LimitSummary:
    rejected: int
    dry_run: int
    by_zone: dict
    unique_clients: int


def summarize_nginx_limits(lines, start, end, server_tz):
    """오류 로그 시각에는 시간대가 없어 서버 시간대로 읽는다. 로그가 비어 있는 것이 정상이라 '살아 있나'는 따로 보지 않는다"""
    by_zone, clients = collections.Counter(), set()
    dry = 0
    for line in lines:
        m = LIMIT_RE.match(line)
        if not m:
            continue
        try:
            ts = dt.datetime.strptime(m["time"], "%Y/%m/%d %H:%M:%S").replace(tzinfo=server_tz)
        except ValueError:
            continue
        if not _in(ts, start, end):
            continue
        if m["dry"]:
            dry += 1
            continue
        by_zone[m["zone"]] += 1
        clients.add(m["client"])
    return LimitSummary(sum(by_zone.values()), dry, dict(by_zone), len(clients))


# ---------- 앱 로그(journald) ----------

# IP마다 하루 첫 거절만 남는다(ClientDailyLimitInterceptor firstRejects). client는 IPv4 끝자리를 가린 값(a.b.c.x)이라
# 같은 /24는 한 대역으로 합쳐진다. 거절 횟수는 액추에이터 ratelimit_client_rejected_total(앱이 뜬 뒤 합계)
CLIENT_LIMIT_RE = re.compile(r"사용자 하루 요청 한도\(\d+\) 초과: client=(\S+?),")
BLOCKED_RE = re.compile(r": (\S+) 하루 예산 차단선 도달")
WARNED_RE = re.compile(r": (\S+) 오늘 호출 \d+회 — 하루 한도 \d+의 80% 도달")


@dataclasses.dataclass
class AppSummary:
    alive: bool
    lines: int
    searches: int
    millie_failed: int
    millie_zero: int
    client_rejected: int  # 거절된 IP 수(첫 거절 로그 수)
    client_rejected_ips: int  # 그 IP의 가린 대역 수(하한)
    budget_blocked: list
    budget_warned: list
    section_failed: dict


FAILED_ITEM_RE = re.compile(r"(?:\[|, )(" + "|".join(SECTIONS) + r"):[A-Z_]+\(")


def failed_sections(line):
    """'failed=[NEW_BOOK:TIMEOUT(원인), SUBSCRIPTION:ERROR(원인)]'에서 섹션 이름만. 원인 글(예외 문구)에 괄호·쉼표가
    있을 수 있어 섹션 enum 이름으로만 맞춘다"""
    i = line.find("failed=[")
    return [] if i < 0 else FAILED_ITEM_RE.findall(line[i + len("failed="):])


def summarize_app_log(lines):
    """journalctl이 창으로 자른 줄을 받는다. 매일 도는 스케줄이 로그를 남기므로 하루 0줄은 수집 문제로 본다"""
    searches = millie_failed = millie_zero = client_rejected = 0
    clients, blocked, warned = set(), [], []
    sections = collections.Counter()
    for line in lines:
        if "통합 검색: code=" in line:
            searches += 1
        elif "밀리 매칭 실패:" in line:
            millie_failed += 1
        elif "밀리 검색 결과 0건:" in line:
            millie_zero += 1
        elif "통합 검색 일부 실패:" in line:
            sections.update(failed_sections(line))
        elif m := CLIENT_LIMIT_RE.search(line):
            client_rejected += 1
            clients.add(m[1])
        elif m := BLOCKED_RE.search(line):
            blocked.append(m[1])
        elif m := WARNED_RE.search(line):
            warned.append(m[1])
    return AppSummary(bool(lines), len(lines), searches, millie_failed, millie_zero, client_rejected, len(clients),
                      blocked, warned, dict(sections))


# ---------- 액추에이터(앱이 뜬 뒤 합계) ----------

SAMPLE_RE = re.compile(r"^(?P<name>[a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(?P<labels>.*)\})?\s+(?P<value>\S+)")
LABEL_RE = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')


def parse_prometheus(text):
    samples = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        m = SAMPLE_RE.match(line)
        if not m:
            continue
        try:
            value = float(m["value"])
        except ValueError:
            continue
        labels = {k: v.replace('\\"', '"').replace("\\\\", "\\") for k, v in LABEL_RE.findall(m["labels"] or "")}
        samples.append((m["name"], labels, value))
    return samples


@dataclasses.dataclass
class MetricsSummary:
    alive: bool
    slots: list
    started_at: object = None
    permit_wait: tuple = None  # (건수, 평균 초, 최대 초 — 최근 몇 분 창)
    yes24_rate_limited: dict = dataclasses.field(default_factory=dict)
    section: dict = dataclasses.field(default_factory=dict)
    hit_rate: float = None  # 1 - bookExist 호출 ÷ 배치 기관 수
    batch_items: int = 0
    book_exist_calls: int = 0
    block_limits: dict = dataclasses.field(default_factory=dict)
    budget_rejected: dict = dataclasses.field(default_factory=dict)
    # 아래 둘은 응답에 지표가 없으면 None으로 둔다 — 없는 값을 0으로 읽어 예산을 확정하지 않게
    sync_failures: int = None
    client_rejected_total: int = 0
    today_used: int = None  # external_budget_used 게이지 합(오늘 KST, 공급자 4곳 — 전자도서관 제외)
    note: str = ""


def _int(value):
    return 0 if math.isnan(value) else int(value)


def summarize_metrics(by_slot):
    """칸이 둘 다 떠 있으면(전환 중) 카운터를 더한다. 값은 '앱이 뜬 뒤 합계'라 하루 값이 아니다"""
    if not by_slot:
        return MetricsSummary(False, [])
    m = MetricsSummary(True, sorted(by_slot))
    starts, wait_count, wait_sum, wait_max = [], 0, 0.0, 0.0
    rate_limited, section, rejected = collections.Counter(), {}, collections.Counter()
    for samples in by_slot.values():  # 칸이 둘이면(전환 중) 더한다
        for name, labels, value in samples:
            if name == "process_start_time_seconds":
                starts.append(value)
            elif name == "yes24_permit_wait_seconds_count":
                wait_count += _int(value)
            elif name == "yes24_permit_wait_seconds_sum":
                wait_sum += 0.0 if math.isnan(value) else value
            elif name == "yes24_permit_wait_seconds_max" and not math.isnan(value):
                wait_max = max(wait_max, value)
            elif name == "yes24_rate_limited_total" and value:
                rate_limited[f'{labels.get("by")}/{labels.get("kind")}'] += _int(value)
            elif name == "search_section_total" and value:
                per = section.setdefault(labels.get("section"), collections.Counter())
                per[labels.get("status")] += _int(value)
            elif name == "library_batch_items_total":
                m.batch_items += _int(value)
            # uri 태그에는 base-url 경로가 붙는다(https://data4library.kr/api → "/api/bookExist?authKey={key}&...")
            elif (name == "http_client_requests_seconds_count"
                  and labels.get("uri", "").split("?", 1)[0].endswith("/bookExist")):
                m.book_exist_calls += _int(value)
            elif name == "external_budget_used":
                m.today_used = (m.today_used or 0) + _int(value)
            elif name == "external_budget_limit" and labels.get("kind") == "block":
                m.block_limits[labels.get("provider")] = _int(value)
            elif name == "external_budget_rejected_total" and value:
                rejected[labels.get("provider")] += _int(value)
            elif name == "external_budget_sync_failures_total":
                m.sync_failures = (m.sync_failures or 0) + _int(value)
            elif name == "ratelimit_client_rejected_total":
                m.client_rejected_total += _int(value)
    if not starts:
        # 응답은 왔지만 JVM이면 늘 있는 지표조차 없다(Prometheus 형식이 아닌 오류 페이지 등) → 값을 믿지 않는다
        return MetricsSummary(False, sorted(by_slot), note="응답에 지표가 없음(Prometheus 형식이 아님?)")
    # 칸마다 따로 본다. 한 칸만 지표가 빠져도 합계는 그 칸 몫을 모르는 값이라 '모름'으로 둔다(다른 칸 값만으로 0을 믿지 않게)
    names = {slot: {name for name, _, _ in samples} for slot, samples in by_slot.items()}
    broken = sorted(slot for slot, n in names.items() if "process_start_time_seconds" not in n)
    if broken:
        m.note = f"칸 {', '.join(broken)} 응답에 지표 없음 — 그 칸 몫은 모름"
    if any("external_budget_sync_failures_total" not in n for n in names.values()):
        m.sync_failures = None
    if any("external_budget_used" not in n for n in names.values()):
        m.today_used = None
    m.started_at = dt.datetime.fromtimestamp(min(starts), dt.timezone.utc)
    if wait_count:
        m.permit_wait = (wait_count, wait_sum / wait_count, wait_max)
    m.yes24_rate_limited = dict(rate_limited)
    m.section = {k: dict(v) for k, v in section.items()}
    m.budget_rejected = dict(rejected)
    if m.batch_items:
        m.hit_rate = max(0.0, 1 - m.book_exist_calls / m.batch_items)
    return m


# ---------- DB 공급자 예산 ----------

@dataclasses.dataclass
class BudgetSummary:
    alive: bool
    providers: dict
    elibrary_sites: int
    elibrary_calls: int
    note: str = ""


def summarize_budget(rows, last_update_epoch, window_end):
    """그날 행이 있으면 그 값. 없으면 동기화가 창 끝 뒤에도 돌았을 때만 0으로 믿는다(아니면 0과 동기화 멈춤을 가를 수 없다)"""
    providers, sites, calls = {}, 0, 0
    for key, used in rows:
        if key.startswith("elibrary:"):
            sites += 1
            calls += used
        else:
            providers[key.lower()] = used
    # updated_at은 증가분을 쓸 때만 오른다. 창 끝 뒤에 기록이 있으면 그날 몫은 다 썼다고 본다
    after_end = last_update_epoch is not None and last_update_epoch >= window_end.timestamp()
    if rows:
        note = "" if after_end else "미확정 — 그 뒤 사용량 기록이 아직 없음(오늘 호출 전이거나 동기화 멈춤, 동기화 실패 수 확인)"
        return BudgetSummary(True, providers, sites, calls, note)
    if after_end:
        return BudgetSummary(True, {}, 0, 0, "그날 행 없음(호출 0), 그 뒤 사용량은 기록됨")
    last = "기록 없음" if last_update_epoch is None else _kst(dt.datetime.fromtimestamp(last_update_epoch, dt.timezone.utc))
    return BudgetSummary(False, {}, 0, 0, f"그날 행이 없고 마지막 갱신이 {last} — 호출 0인지 동기화 멈춤인지 모름")


# ---------- 보고 ----------

SOURCES = ("Nginx 접근 로그", "Nginx 오류 로그", "앱 로그", "DB", "액추에이터")


@dataclasses.dataclass
class Report:
    day: dt.date
    access: AccessSummary = None
    limits: LimitSummary = None
    app: AppSummary = None
    budget: BudgetSummary = None
    metrics: MetricsSummary = None
    missing: dict = dataclasses.field(default_factory=dict)  # 원천 → 이유
    partial: dict = dataclasses.field(default_factory=dict)  # 원천 → 못 읽은 파일(값이 실제보다 적을 수 있음)
    sync_events: list = None  # 그날 0시부터 지금까지 동기화 실패·종료 때 미반영·앱 시작·정상 종료 로그. None이면 못 읽음
    sync_events_error: str = ""


def build_report(day, sources):
    start, end = day_window(day)
    rep = Report(day)

    def collect(name, fn):
        try:
            return fn()
        except Exception as e:  # 원천 하나가 죽어도 나머지는 낸다
            rep.missing[name] = f"{type(e).__name__}: {e}"
            return None

    def read(name, fn):
        lines, failed = fn(start)
        if failed:
            rep.partial[name] = ", ".join(failed)
        return lines

    rep.access = collect("Nginx 접근 로그",
                         lambda: summarize_access(read("Nginx 접근 로그", sources.access_lines), start, end))
    if rep.access and not rep.access.alive:
        rep.missing["Nginx 접근 로그"] = "그날 줄이 하나도 없음(헬스 체크 줄도 없음)"
    rep.limits = collect("Nginx 오류 로그",
                         lambda: summarize_nginx_limits(read("Nginx 오류 로그", sources.error_lines), start, end,
                                                        sources.server_tz()))
    rep.app = collect("앱 로그", lambda: summarize_app_log(sources.app_lines(start, end)))
    if rep.app and not rep.app.alive:
        rep.missing["앱 로그"] = "그날 앱 로그 0줄(매일 도는 스케줄 로그도 없음)"
    rep.budget = collect("DB", lambda: summarize_budget(*sources.budget(day), window_end=end))
    if rep.budget and not rep.budget.alive:
        rep.missing["DB"] = rep.budget.note
    rep.metrics = collect("액추에이터", lambda: summarize_metrics(sources.prometheus()))
    if rep.metrics and not rep.metrics.alive:
        rep.missing["액추에이터"] = rep.metrics.note or "블루·그린 두 칸 모두 응답 없음"
    try:
        rep.sync_events = sources.budget_sync_events(start)
    except Exception as e:
        rep.sync_events, rep.sync_events_error = None, f"{type(e).__name__}: {e}"
    _check_budget(rep)
    return rep


SYNC_EVENT_MARKERS = ("공급자 예산 DB 동기화 실패", "종료 때 쓰지 않음")
APP_START_MARKER = "Started CheckbookApplication in"
# Spring Boot 4.1 tomcat GracefulShutdown의 문구(jar에서 확인, 11-14). aborted도 그 뒤 빈 정리(@PreDestroy flush)는 돈다
GRACEFUL_MARKERS = ("Graceful shutdown complete", "Graceful shutdown aborted")
LIFECYCLE_MARKERS = SYNC_EVENT_MARKERS + (APP_START_MARKER,) + GRACEFUL_MARKERS


def _count(lines, markers):
    return sum(1 for line in lines if any(k in line for k in markers))


def _check_budget(rep):
    """그날 예산 값을 확정으로 볼지 정한다. 빠질 수 있는 길은 둘이다(ExternalCallBudgetSyncer).
    ① 동기화는 행마다 저장하다 첫 실패에서 멈추고 나머지를 메모리로 되돌린다 → 테이블의 마지막 갱신이 자정 뒤여도
       어떤 공급자 몫은 아직 안 들어갔을 수 있다.
    ② 실패 중에 앱이 내려가면 남은 증가분을 버리고 '종료 때 쓰지 않음'을 남긴다. 새 프로세스의 실패 카운터는 0부터다.
    로그는 실패가 시작될 때(한 번)와 ②일 때 남는다. 그래서 그날 0시부터 지금까지 이 로그가 없고
    - 지금 프로세스가 0시 뒤에 떴으면: 그 전 프로세스의 실패는 ② 로그로, 이후 실패는 시작 로그로 범위 안에 남았을 것 → 확정
    - 0시 전에 떴으면: 0시 전에 시작해 이어진 실패는 범위 밖에 로그가 있다 → 액추에이터 실패 카운터(앱이 뜬 뒤 합계)로 본다
    ③ 강제 종료(SIGKILL·OOM·재부팅)면 종료 정리(@PreDestroy)가 안 돌아 ② 로그도 없이 증가분을 잃는다(실패가 없어도
       마지막 동기화 뒤 최대 10초치). 정상 종료는 Spring이 'Graceful shutdown complete'를 남기므로, 그날 0시부터 지금까지
       앱 시작 수보다 정상 종료 수가 모자라면 강제 종료가 있었다고 본다. 칸 하나는 늘 떠 있으니 끝난 프로세스 수 ≈
       시작 수 + 1 - 지금 떠 있는 칸 수(0시에 앱이 꺼져 있었으면 과하게 잡지만 드물다).
    자정 뒤 기록이 없어도 앱이 오늘 쓴 양이 0이면 아직 DB에 안 쓴 그날 몫이 없어 확정이다(새벽 예약 호출은 월·목에만 있다)."""
    b, m = rep.budget, rep.metrics
    if not (b and b.alive):
        return
    start, _ = day_window(rep.day)
    reason = None
    if rep.sync_events is None:
        reason = f"앱 로그로 동기화 실패를 확인 못 함({rep.sync_events_error})"
    elif failures := _count(rep.sync_events, SYNC_EVENT_MARKERS):
        reason = f"그날 0시 뒤 동기화 실패·종료 때 미반영 로그 {failures}줄, 일부 공급자 몫이 빠졌을 수 있음"
    elif not (m and m.alive):
        reason = "액추에이터가 없어 동기화 상태를 못 봄"
    elif m.note:
        reason = m.note  # 지표가 빠진 칸은 시작 시각도 실패 수도 모른다
    elif (starts := _count(rep.sync_events, (APP_START_MARKER,))) == 0 and m.started_at > start:
        reason = "지금 앱은 0시 뒤에 떴는데 시작 로그가 없음(앱 로그가 빠졌을 수 있음)"
    elif (stops := _count(rep.sync_events, GRACEFUL_MARKERS)) < starts + 1 - len(m.slots):
        reason = (f"그날 0시 뒤 앱 시작 {starts}회에 정상 종료 {stops}회 — 강제 종료(OOM·재부팅 등)로 "
                  "아직 DB에 안 쓴 증가분을 잃었을 수 있음")
    elif m.started_at <= start:
        if m.sync_failures is None:
            reason = "응답에 동기화 실패 지표가 없음"
        elif m.sync_failures:
            reason = f"앱이 뜬 뒤 동기화 실패 {m.sync_failures}회(0시 전에 시작해 이어진 실패일 수 있음)"
    if reason:
        b.note = f"미확정 — {reason} (journald '공급자 예산 DB 동기화' 줄의 시각 확인)"
    elif b.note.startswith("미확정") and m and m.alive and m.today_used == 0:
        b.note = ""


MISSING = "수집 안 됨"


def _ok(rep, source):
    return source not in rep.missing


def _fmt(d):
    """{'a': 1, 'b': {'X': 2}} → 'a 1, b(X 2)'. 비면 '없음'"""
    if not d:
        return "없음"
    return ", ".join(f"{k}({_fmt(v)})" if isinstance(v, dict) else f"{k} {v}" for k, v in sorted(d.items()))


def _kst(ts):
    return ts.astimezone(KST).strftime("%m-%d %H:%M KST")


def _budget_cell(rep, provider):
    if not _ok(rep, "DB"):
        return MISSING
    used = rep.budget.providers.get(provider, 0)
    limit = rep.metrics.block_limits.get(provider) if rep.metrics and rep.metrics.alive else None
    cell = f"{used} ({used / limit * 100:.1f}%)" if limit else f"{used}"
    return cell + (" 미확정" if rep.budget.note.startswith("미확정") else "")


def _peak(acc):
    if not acc.peak_hour:
        return "-"
    hour, count = acc.peak_hour
    return f"{hour}시 {count}건 ×{count / (acc.api_total / 24):.1f}"


ROW_HEADER = ("날짜(KST)", "상세 조회", "검색", "고유 IP", "1인 상세(평균/중앙/90%/최대)", "피크(시·건·계수)",
              "YES24 예산", "정보나루 예산", "우리 한도 거절 IP(대역)", "Nginx 거절(IP)", "밀리 실패/0건", "섹션 실패", "수집 안 됨")


def markdown_row(rep):
    acc, app, lim = rep.access, rep.app, rep.limits
    a_ok, app_ok, l_ok = _ok(rep, "Nginx 접근 로그"), _ok(rep, "앱 로그"), _ok(rep, "Nginx 오류 로그")
    cells = [
        rep.day.isoformat(),
        str(acc.detail) if a_ok else MISSING,
        str(acc.search) if a_ok else MISSING,
        str(acc.unique_ips) if a_ok else MISSING,
        ("/".join(str(v) for v in acc.detail_per_ip) if acc.detail_per_ip else "-") if a_ok else MISSING,
        _peak(acc) if a_ok else MISSING,
        _budget_cell(rep, "yes24"),
        _budget_cell(rep, "datanaru"),
        f"{app.client_rejected} ({app.client_rejected_ips})" if app_ok else MISSING,
        f"{lim.rejected} ({lim.unique_clients})" if l_ok else MISSING,
        f"{app.millie_failed}/{app.millie_zero}" if app_ok else MISSING,
        (", ".join(f"{k} {v}" for k, v in sorted(app.section_failed.items())) or "0") if app_ok else MISSING,
        ", ".join([*rep.missing, *(f"{n}(일부)" for n in rep.partial if n not in rep.missing)]) or "-",
    ]
    return "| " + " | ".join(cells) + " |"


def render(rep):
    start, end = day_window(rep.day)
    out = [f"CheckBook 첫 주 지표 — {rep.day.isoformat()} (KST 00:00~24:00, UTC {start:%m-%d %H:%M}~{end:%m-%d %H:%M})", ""]
    out.append("[원천]")
    for name in SOURCES:
        if not _ok(rep, name):
            out.append(f"  {name}: {MISSING} — {rep.missing[name]}")
        elif name in rep.partial:
            out.append(f"  {name}: 일부만 — 못 읽은 파일 {rep.partial[name]}, 값이 실제보다 적을 수 있음")
        else:
            out.append(f"  {name}: 정상")
    out.append("")

    out.append("[얼마나 썼나] Nginx 접근 로그, 헬스 체크 제외, 사람·봇 구분 없음")
    if _ok(rep, "Nginx 접근 로그"):
        a = rep.access
        out.append(f"  /api/ 요청 {a.api_total}(2xx 아님 {a.not_ok}, 그중 429 {a.status_429}), 고유 IP {a.unique_ips}")
        out.append(f"  상세 조회 {a.detail}, 검색 {a.search} (2xx만)")
        if a.detail_per_ip:
            out.append(f"  상세를 본 IP {a.detail_ips}곳, IP당 상세 조회 평균 {a.detail_per_ip[0]} / 중앙 {a.detail_per_ip[1]}"
                       f" / 90% {a.detail_per_ip[2]} / 최대 {a.detail_per_ip[3]}")
        if a.peak_hour:
            out.append(f"  시간대(KST) 가장 많은 때 {_peak(a)} (계수 = 그 시간 ÷ 시간 평균)")
            out.append("  " + " ".join(f"{h:02d}:{c}" for h, c in enumerate(a.hourly)))
    else:
        out.append(f"  {MISSING}")
    out.append("")

    out.append("[예산] DB external_call_budget 그날 확정값 ÷ 차단선(액추에이터)")
    if _ok(rep, "DB"):
        b = rep.budget
        names = sorted(set(b.providers) | set(rep.metrics.block_limits if rep.metrics else {}))
        out.append("  " + (", ".join(f"{p} {_budget_cell(rep, p)}" for p in names) or "공급자 호출 0"))
        out.append(f"  전자도서관 사이트 {b.elibrary_sites}곳 {b.elibrary_calls}회")
        if b.note:
            out.append(f"  ({b.note})")
    else:
        out.append(f"  {MISSING}")
    out.append("  예산 DB 동기화 실패(앱이 뜬 뒤): " + (
        (MISSING + "(지표 없음)" if rep.metrics.sync_failures is None else str(rep.metrics.sync_failures))
        if _ok(rep, "액추에이터") else f"{MISSING}(액추에이터)"))
    if _ok(rep, "앱 로그"):
        out.append(f"  80% 경고 {', '.join(rep.app.budget_warned) or '없음'}, 차단선 도달 {', '.join(rep.app.budget_blocked) or '없음'}")
    out.append("")

    out.append("[제한]")
    out.append("  우리 하루 한도 거절: " + (f"IP {rep.app.client_rejected}곳(가린 대역 {rep.app.client_rejected_ips}) — "
                                        "IP당 하루 첫 거절만 로그에 남는다, 횟수는 아래 액추에이터"
                                        if _ok(rep, "앱 로그") else MISSING))
    if _ok(rep, "Nginx 오류 로그"):
        lim = rep.limits
        out.append(f"  Nginx 거절: {lim.rejected}회" + (f" ({_fmt(lim.by_zone)})" if lim.by_zone else "")
                   + f", IP {lim.unique_clients}곳"
                   + (f", dry run {lim.dry_run}" if lim.dry_run else ""))
    else:
        out.append(f"  Nginx 거절: {MISSING}")
    out.append("")

    out.append("[결과 품질] 앱 로그")
    if _ok(rep, "앱 로그"):
        app = rep.app
        out.append(f"  상세 조회(앱) {app.searches}, 밀리 매칭 실패 {app.millie_failed}, 밀리 0건 {app.millie_zero}")
        out.append(f"  섹션 실패 {_fmt(app.section_failed)}")
    else:
        out.append(f"  {MISSING}")
    out.append("")

    out.append("[앱이 뜬 뒤 합계] 액추에이터 — 하루 값이 아님, 배포하면 0부터")
    if _ok(rep, "액추에이터"):
        m = rep.metrics
        out.append(f"  칸 {', '.join(m.slots)}, 시작 {_kst(m.started_at) if m.started_at else '모름'}"
                   + (f" ({m.note})" if m.note else ""))
        if m.permit_wait:
            c, avg, mx = m.permit_wait
            out.append(f"  YES24 허가 대기 {c}건 평균 {avg * 1000:.1f}ms, 최근 최대 {mx * 1000:.0f}ms")
        out.append(f"  YES24 거절 {_fmt(m.yes24_rate_limited)}, 예산 거절 {_fmt(m.budget_rejected)}, "
                   f"우리 하루 한도 거절 {m.client_rejected_total}회")
        if m.hit_rate is not None:
            out.append(f"  정보나루 적중률 근사 {m.hit_rate * 100:.0f}% (1 - bookExist {m.book_exist_calls} ÷ 배치 기관 {m.batch_items})"
                       + (" — bookExist 0건: 지표 경로 이름이 바뀌었는지 확인" if m.book_exist_calls == 0 else ""))
        skipped_failed = {s: {k: v for k, v in st.items() if k in ("SKIPPED", "FAILED")} for s, st in m.section.items()}
        out.append(f"  섹션 SKIPPED·FAILED {_fmt({k: v for k, v in skipped_failed.items() if v})}")
    else:
        out.append(f"  {MISSING}")
    out.append("")
    out.append("[손으로] Slack 두 채널에서 그날 울린 알림이 있었는지 본다")
    out.append("")
    out.append("[재가동 기록에 붙일 한 행]")
    out.append("| " + " | ".join(ROW_HEADER) + " |")
    out.append(markdown_row(rep))
    return "\n".join(out)


# ---------- 실제 서버 원천 ----------

def _read_logs(pattern, since):
    """회전된 파일(.1, .2.gz …)까지 읽는다. (줄, 못 읽은 파일 이름)을 돌려준다. 하나도 못 읽으면 실패.
    못 읽은 파일 하나가 나머지까지 버리게 하지 않되, 그 파일에 창의 줄이 있었을 수 있으면(마지막 수정이 창 시작 뒤)
    숨기지 않고 돌려준다 — 값이 실제보다 적을 수 있다는 표시가 된다. 창 전에 끝난 파일은 어차피 세지 않으니 뺀다"""
    paths = sorted(glob.glob(pattern))
    if not paths:
        raise FileNotFoundError(f"{pattern} 없음")
    lines, read, failed = [], 0, []
    for path in paths:
        opener = gzip.open if path.endswith(".gz") else open
        try:
            with opener(path, "rt", encoding="utf-8", errors="replace") as f:
                lines.extend(f.read().splitlines())
            read += 1
        except (OSError, EOFError) as e:
            try:
                relevant = os.path.getmtime(path) >= since.timestamp()
            except OSError:
                relevant = True
            if relevant:
                failed.append(f"{os.path.basename(path)}({type(e).__name__})")
    if not read:
        raise OSError(f"{pattern} 하나도 못 읽음")
    return lines, failed


class ServerSources:
    def __init__(self, nginx_dir, env_file, deploy_dir):
        self.nginx_dir, self.env_file, self.deploy_dir = nginx_dir, env_file, deploy_dir

    def access_lines(self, start):
        return _read_logs(os.path.join(self.nginx_dir, "access.log*"), start)

    def error_lines(self, start):
        return _read_logs(os.path.join(self.nginx_dir, "error.log*"), start)

    def server_tz(self):
        return dt.datetime.now().astimezone().tzinfo

    def app_lines(self, start, end):
        cmd = ["journalctl", "--no-pager", "-q", "-o", "cat",
               "--since", f"@{int(start.timestamp())}", "--until", f"@{int(end.timestamp())}"]
        cmd += [f"CONTAINER_NAME={c}" for c in APP_CONTAINERS]
        res = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=120)
        if res.returncode != 0:
            raise RuntimeError(res.stderr.strip() or f"journalctl 종료 코드 {res.returncode}")
        return res.stdout.splitlines()

    def budget_sync_events(self, start):
        now = dt.datetime.now(dt.timezone.utc)
        return [line for line in self.app_lines(start, now) if any(k in line for k in LIFECYCLE_MARKERS)]

    def budget(self, day):
        sql = (f"SELECT budget_key, used FROM external_call_budget WHERE usage_date = DATE '{day.isoformat()}';\n"
               "SELECT '#last', COALESCE(EXTRACT(EPOCH FROM MAX(updated_at))::bigint::text, '') FROM external_call_budget;\n")
        # 읽기 전용 트랜잭션으로만 붙는다. 사용자 이름은 컨테이너 안의 POSTGRES_USER(.env를 셸로 읽지 않는다, backup.sh와 같다)
        deploy_dir = self.deploy_dir
        cmd = ["docker", "compose", "--project-directory", deploy_dir, "-f", os.path.join(deploy_dir, "compose.yaml"),
               "--env-file", self.env_file, "exec", "-T", "postgres", "sh", "-c",
               'PGOPTIONS="-c default_transaction_read_only=on" '
               'psql -U "$POSTGRES_USER" -d checkbook -At -F "|" -v ON_ERROR_STOP=1']
        res = subprocess.run(cmd, input=sql, capture_output=True, text=True, timeout=60)
        if res.returncode != 0:
            raise RuntimeError(res.stderr.strip() or f"psql 종료 코드 {res.returncode}")
        rows, last = [], None
        for line in res.stdout.splitlines():
            key, _, value = line.partition("|")
            if key == "#last":
                last = int(value) if value else None
            elif key:
                rows.append((key, int(value)))
        return rows, last

    def prometheus(self):
        out = {}
        for slot, port in SLOTS:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/prometheus", timeout=5) as resp:
                    out[slot] = parse_prometheus(resp.read().decode("utf-8", "replace"))
            except Exception:
                continue  # 대기 칸은 꺼져 있는 것이 정상. 읽다 끊긴 칸도 다른 칸을 막지 않게 건너뛴다
        return out


def main(argv=None):
    p = argparse.ArgumentParser(description="CheckBook 첫 주 지표(하루, KST). 읽기만 한다")
    p.add_argument("--date", type=dt.date.fromisoformat, help="YYYY-MM-DD(KST). 기본 어제")
    p.add_argument("--nginx-log-dir", default="/var/log/nginx")
    p.add_argument("--env-file", default=os.environ.get("CHECKBOOK_ENV_FILE", "/opt/checkbook/.env"))
    p.add_argument("--deploy-dir", default=CURRENT_DEPLOY_DIR if os.path.isdir(CURRENT_DEPLOY_DIR) else LOCAL_DEPLOY_DIR,
                   help="DB에 붙을 compose 파일이 있는 폴더(기본: 서비스 중인 릴리스)")
    args = p.parse_args(argv)
    day = args.date or (dt.datetime.now(KST).date() - dt.timedelta(days=1))
    rep = build_report(day, ServerSources(args.nginx_log_dir, args.env_file, args.deploy_dir))
    print(render(rep))
    return 1 if rep.missing or rep.partial else 0


if __name__ == "__main__":
    sys.exit(main())
