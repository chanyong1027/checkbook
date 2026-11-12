#!/usr/bin/env python3
"""CheckBook Grafana 알림 규칙 원본(PR-B 결정 카드 2026-10-19). 규칙은 이 파일 한 곳에만 있다.

여기서 두 가지를 만든다.
  grafana_groups(설정)  → Grafana provisioning API에 넣을 규칙 묶음 JSON(apply-alerts.py가 씀)
  promtool_rules()      → 같은 식으로 만든 Prometheus 규칙 파일(tests/의 promtool 시험이 씀)
식은 한 번만 적고 두 곳이 같은 문자열을 쓴다 — 시험한 식과 반영한 식이 다를 수 없게.

규칙마다 질의 A(조건을 넘는 시리즈만 남기는 PromQL·LogQL)와 조건 B(A {op} {값})를 둔다.
조건을 넘지 않으면 A가 비어 '데이터 없음'이 되므로 대부분 noDataState=OK다. 그러면 지표 이름이 바뀌어도
조용히 정상으로 보이므로, 규칙들이 보는 지표가 사라지면 따로 알린다(app-metrics-missing).

블루·그린: 두 칸을 slot 라벨로 따로 수집한다. 카운터는 칸마다 따로 세므로 칸끼리 더하고(sum),
예산·featured 게이지는 각 칸이 DB 값을 읽어 이미 전체 값이라 더하면 2배가 되므로 max로 본다.

실행: python3 deploy/grafana/rules.py --list          규칙 목록
      python3 deploy/grafana/rules.py --promtool <파일> promtool용 규칙 파일(JSON은 YAML이기도 하다)
"""
import json
import re
import sys
from dataclasses import dataclass, field

import thresholds

URGENT, INFO = "urgent", "info"
TIERS = {
    # 묶기 30초·5분은 Alertmanager 기본값, 재알림은 즉시 4시간(기본값)·낮 24시간(하루 한 번 보면 됨)
    URGENT: {"group": "checkbook-urgent", "receiver": "slack-urgent", "repeat": "4h"},
    INFO: {"group": "checkbook-info", "receiver": "slack-info", "repeat": "24h"},
}
EVAL_INTERVAL_SECONDS = 60

APP = 'job="checkbook-app"'
NODE = 'job="integrations/node_exporter"'
# 사용자 요청만 센다. /actuator는 Alloy가 칸마다 1분에 한 번, /api/health는 UptimeRobot이 5분에 한 번 부른다 —
# 분모에 넣으면 실제 사용자의 오류가 묽어진다(SRE Workbook: 합성 요청의 성공이 사용자 실패를 가릴 수 있다)
USER_API = 'uri=~"/api/.+", uri!="/api/health"'
PROVIDER_HOSTS = {
    "datanaru": "data4library.kr",
    "millie": "live-api.millie.co.kr",
    "kakao": "dapi.kakao.com",
    "yes24": "apis.yes24.com",
}
# 외부 호출 히스토그램에서 1초에 가장 가까운 구간 경계(10ms~10s 자동 구간, 서버 /actuator/prometheus에서 확인)
SLOW_BUCKET = "1.073741824"

# 알림 요약에 넣는 질의 A의 값. 비율은 0.1834 대신 18.34%로, 건수는 increase()의 외삽으로 소수(2.98)가 나와 정수로
PCT = "{{ humanizePercentage $values.A.Value }}"
COUNT = '{{ printf "%.0f" $values.A.Value }}'

APP_LOGS = ("sudo journalctl -o cat CONTAINER_NAME=checkbook-app-blue-1 CONTAINER_NAME=checkbook-app-green-1 "
            "--since '-1 hour'")
LOKI_APP = '{job="checkbook/journal", container=~"checkbook-app-.+"}'


@dataclass
class Rule:
    uid: str            # Grafana 규칙 uid(바꾸면 다른 규칙이 된다 — 알림 이력이 끊긴다)
    tier: str
    title: str
    expr: str
    summary: str        # Slack 첫 줄. {{ $labels.x }}, 값은 PCT(비율 → 18.3%)·COUNT(건수 → 정수)
    runbook: list       # 확인할 것(순서대로)
    datasource: str = "prom"
    op: str = "gt"      # 조건 B: A {op} {value}
    value: float = 0
    for_: str = "0s"
    no_data: str = "OK"
    # 앱이 기동 때 만들지 않아 평소에 없을 수 있는 지표(사라짐 감시에서 뺀다)
    lazy_metrics: tuple = field(default_factory=tuple)


def increase(metric, selector, window, by=""):
    grouping = f" by ({by})" if by else ""
    return f"sum{grouping} (increase({metric}{{{selector}}}[{window}]))"


def ratio_rule_expr(bad, total, by, targets, ratio, long, short=None):
    """대상별로 (긴 창 비율 > r) and (짧은 창 비율 > r) and (긴 창 건수 ≥ 최소 건수)를 만들어 or로 잇는다.
    bad·total은 (창, 대상 선택자) → PromQL. 짧은 창은 '지금도 계속되는지'(SRE Workbook, 긴 창의 1/12)
    대상마다 최소 건수가 달라(기준선이 다름) 대상별로 나눈다"""
    on = f"on ({by})" if by else "on ()"
    parts = []
    for target, n_min in targets.items():
        sel = f'{by}="{target}"' if by else ""
        cond = [f"({bad(long, sel)} / {total(long, sel)}) > {ratio}"]
        if short:
            cond.append(f"({bad(short, sel)} / {total(short, sel)}) > {ratio}")
        cond.append(f"{total(long, sel)} >= {n_min}")
        parts.append("(\n  " + f"\n  and {on} ".join(cond) + "\n)")
    return "\n or ".join(parts)


def _section_counts(status_selector):
    def counts(window, sel):
        selector = ", ".join(s for s in (APP, status_selector, sel) if s)
        return increase("search_section_total", selector, window, by="section")
    return counts


def _client_counts(kind):
    def counts(window, sel):
        selector = ", ".join(s for s in (APP, sel) if s)
        total = increase("http_client_requests_seconds_count", selector, window, by="client_name")
        if kind == "total":
            return total
        fast = increase("http_client_requests_seconds_bucket", f'{selector}, le="{SLOW_BUCKET}"', window,
                        by="client_name")
        return f"({total} - {fast})"
    return counts


def _server_counts(status_selector):
    def counts(window, sel):
        selector = ", ".join(s for s in (APP, USER_API, status_selector, sel) if s)
        return increase("http_server_requests_seconds_count", selector, window)
    return counts


def _elibrary_counts(outcome_selector):
    def counts(window, sel):
        selector = ", ".join(s for s in (APP, outcome_selector, sel) if s)
        return increase("elibrary_scrape_seconds_count", selector, window, by="vendor")
    return counts


def _library_item_counts(state_selector):
    def counts(window, sel):
        selector = ", ".join(s for s in (APP, state_selector, sel) if s)
        return increase("library_batch_items_total", selector, window)
    return counts


FS = f'{NODE}, fstype!="", mountpoint!=""'


def _disk_used_above(free_below, fill_within_hours=None):
    """여유가 free_below% 미만(= 사용이 100 - free_below% 초과)인 파일 시스템. 값은 사용 비율로 돌려준다 —
    여유 비율을 돌려주면 꽉 찼을 때(여유 0) 값이 0이 되어 조건 '> 0'이 거짓, 가장 나쁜 순간에 알림이 풀린다"""
    expr = (f"100 - node_filesystem_avail_bytes{{{FS}}} / node_filesystem_size_bytes{{{FS}}} * 100 "
            f"> {100 - free_below}")
    if fill_within_hours:
        expr += (f"\nand predict_linear(node_filesystem_avail_bytes{{{FS}}}[6h], {fill_within_hours} * 3600) < 0")
    return f"({expr}\nand on (instance, device, mountpoint) node_filesystem_readonly{{{FS}}} == 0)"


def build_rules():
    mins = thresholds.minimum_counts()
    ratio = thresholds.ratio_of
    # 섹션 SLI: 공급자에 실제로 물어본 것(SUCCESS + FAILED) 중 FAILED. SKIPPED(위치 없음·우리 쪽 선제한 등)는 뺀다
    section_bad = _section_counts('status="FAILED"')
    section_total = _section_counts('status=~"SUCCESS|FAILED"')
    host_mins = {fast_or_slow: {PROVIDER_HOSTS[p]: n for p, n in mins[fast_or_slow].items()}
                 for fast_or_slow in ("client_slow_fast", "client_slow_slow")}
    server_bad = _server_counts('status=~"5.."')
    server_total = _server_counts("")
    five_xx = f'{APP}, {USER_API}, status=~"5.."'

    rules = [
        # ---------- 즉시 ----------
        Rule(
            uid="cb-app-down", tier=URGENT, title="앱 지표 끊김",
            # 대기 칸은 꺼져 있어 up=0이 정상이다 — 칸 중 하나라도 up이면 산 것
            expr=f"max (up{{{APP}}})", op="lt", value=1, for_="3m", no_data="Alerting",
            summary="앱 지표가 3분 넘게 끊겼습니다(앱 다운 또는 수집 중단)",
            runbook=[
                "UptimeRobot도 다운이면 앱·서버 문제: `sudo docker ps`, " + APP_LOGS + " | tail -50",
                "UptimeRobot은 정상인데 이것만 오면 수집 문제: `sudo journalctl -u alloy --since '-10 min' | grep -iE 'error|401|403'` "
                "— 401이면 Alloy 토큰 만료(만료 2027-10-20, deploy/README.md '관측: Grafana Alloy')",
                "배포 직후면 deploy.sh가 칸을 바꾸는 중인지 확인(두 칸이 잠깐 같이 0이면 정상 아님)",
            ]),
        Rule(
            uid="cb-server-5xx-burn", tier=URGENT, title="서버 5xx 소진율",
            expr=ratio_rule_expr(server_bad, server_total, "", {"": mins["server_5xx_fast"]["server"]},
                                 ratio("server_5xx_fast"), "1h", "5m")
                 + "\n or "
                 + ratio_rule_expr(server_bad, server_total, "", {"": mins["server_5xx_slow"]["server"]},
                                   ratio("server_5xx_slow"), "6h", "30m"),
            summary="사용자 API 5xx 비율이 SLO 99%의 오류 예산을 빠르게 쓰고 있습니다(실패 " + PCT + ")",
            runbook=[
                "어느 경로인지: Explore `sum by (uri, status) (increase(http_server_requests_seconds_count"
                f"{{{five_xx}}}[1h]))`",
                "원인 로그: " + APP_LOGS + " | grep -A20 -E ' ERROR |카카오 도서 검색|공급자 하루 예산 소진'",
                "/api/books/search의 503은 검색창 검색이 막힌 것(추천 목록·공유 링크는 됨). 로그로 가른다 — "
                "`카카오 도서 검색 실패`면 카카오 실패(스택이 4xx면 키·차단이라 기다려도 안 나음, 5xx·시간 초과면 기다림), "
                "`응답 구조 누락`이면 응답 형식이 바뀐 것(코드를 고칠 일), "
                "`공급자 하루 예산 소진: KAKAO`면 우리 예산이 다 찬 것(자정까지, '예산 차단선' 알림이 같이 옴)",
                "배포 직후면 되돌리기 검토(deploy/README.md '되돌리기')",
            ]),
        Rule(
            uid="cb-budget-blocked", tier=URGENT, title="공급자 하루 예산 차단선 도달",
            expr=(f"max by (provider) (external_budget_used{{{APP}}})\n"
                  f">= on (provider) max by (provider) (external_budget_limit{{{APP}, kind=\"block\"}})"),
            summary="{{ $labels.provider }} 하루 예산 차단선 도달 — 자정(KST)까지 사용자 요청 호출이 막힙니다",
            runbook=[
                "남용인지: Explore `sum(increase(ratelimit_client_rejected_total[1h]))`, Nginx 접근 로그에서 많이 부른 IP "
                "`sudo awk '{print $1}' /var/log/nginx/access.log | sort | uniq -c | sort -rn | head`",
                "남용이면 Cloudflare에서 IP·국가 차단 또는 챌린지. 정상 트래픽이면 자정까지 기다림(공급자 한도는 못 늘림)",
                "yes24이고 'YES24 하루 한도 소진(RATE_002)'도 같이 왔으면 YES24가 먼저 막은 것(잔량 0 헤더가 우리 셈을 "
                "한도까지 덮어씀) — 남용 확인보다 그 알림의 확인 순서부터",
            ]),
        Rule(
            uid="cb-yes24-daily-limit", tier=URGENT, title="YES24 하루 한도 소진(RATE_002)",
            expr=increase("yes24_rate_limited_total", f'{APP}, by="remote", kind="day"', "10m") + " > 0",
            summary="YES24가 하루 한도 초과(RATE_002)를 돌려줬습니다 — 우리 셈의 차단선보다 먼저 막힘",
            runbook=[
                "우리가 오늘 실제로 보낸 수: Explore 시간 범위를 오늘 0시(KST)부터 지금까지로 두고 Instant 질의로 "
                "`sum(increase(http_client_requests_seconds_count{" + APP
                + ", client_name=\"apis.yes24.com\"}[$__range]))` — 하루 한도(20,000)보다 한참 아래면 키가 다른 곳에서 "
                "쓰이는 것, YES24 키 교체 검토",
                "`external_budget_used`는 비교에 못 쓴다 — YES24가 준 잔량 0 헤더가 우리 셈을 한도까지 덮어쓴다"
                "(그래서 '예산 차단선' 알림이 같이 온다)",
                "자정(KST)까지 새 책 칸은 건너뜀. 재시작해도 YES24 쪽 한도는 그대로",
                "로그: " + APP_LOGS + " | grep RATE_002",
            ]),
        Rule(
            uid="cb-disk-full", tier=URGENT, title="디스크 거의 참",
            expr=_disk_used_above(10), for_="5m",
            summary="{{ $labels.mountpoint }} 여유 공간 10% 미만 — DB가 같은 디스크라 곧 멈춥니다",
            runbook=[
                "`df -h`, `sudo du -xh / --max-depth=2 2>/dev/null | sort -rh | head -20`",
                "흔한 원인: Docker 이미지(`sudo docker system df`, `sudo docker image prune`), journald(`journalctl --disk-usage`), 백업 파일",
            ]),
        Rule(
            uid="cb-disk-filling-fast", tier=URGENT, title="디스크가 4시간 안에 찰 것으로 보임",
            expr=_disk_used_above(20, fill_within_hours=4),
            for_="1h",
            summary="{{ $labels.mountpoint }} 여유 20% 미만이고 지난 6시간 추세로 4시간 안에 찹니다",
            runbook=["무엇이 늘고 있는지: `sudo du -xh / --max-depth=3 2>/dev/null | sort -rh | head -20` (로그 폭주가 흔함)"]),

        # ---------- 낮 ----------
        Rule(
            uid="cb-section-failed-burn", tier=INFO, title="검색 섹션 실패 소진율",
            expr=ratio_rule_expr(section_bad, section_total, "section", mins["section_failed_fast"],
                                 ratio("section_failed_fast"), "1h", "5m")
                 + "\n or "
                 + ratio_rule_expr(section_bad, section_total, "section", mins["section_failed_slow"],
                                   ratio("section_failed_slow"), "6h", "30m"),
            summary="{{ $labels.section }} 섹션 실패 비율이 SLO 99%의 오류 예산을 빠르게 쓰고 있습니다(실패 " + PCT + ")",
            runbook=[
                "이유별: Explore `sum by (reason) (increase(search_section_total{section=\"{{ $labels.section }}\", "
                "status=\"FAILED\"}[1h]))`",
                "원인: " + APP_LOGS + " | grep '통합 검색 일부 실패' — 칸마다 원인 문구가 붙어 있다. 더 보려면 그 줄의 "
                "traceId로 다시 grep(INFO 원인 줄까지). YES24는 문구가 'YES24 오류'뿐이라 같은 traceId의 `YES24 식별` 줄(실패·생략(서킷 열림)·예상 못 한 오류)을 본다",
                "TIMEOUT은 대개 공급자가 느린 것 — 대응은 기다림(서킷이 열리면 UNAVAILABLE로 바뀜). 섹션은 실패로 표시되고 "
                "나머지 결과는 나간다. 외부 호출 p99가 정상인데 TIMEOUT이면 우리 검색 풀에서 기다리다 마감을 넘긴 것(우리 쪽 혼잡)",
                "UNAVAILABLE은 외부 오류·응답 형식 이상·서킷 열림·검색 풀 포화를 다 뜻한다 — 원인 문구로 가른다. "
                "4xx(401·403)면 키·계약·차단이라 기다려도 안 낫는다: Explore `sum by (client_name, status) "
                "(increase(http_client_requests_seconds_count{" + APP + "}[1h]))`",
                "YES24 원인이 '호출 실패'(응답 없음)면 연결 거부·이름 찾기 실패·리셋을 로그로 못 가른다(밀리·정보나루는 "
                "WARN 줄의 cause=에 나온다) — 서버에서 "
                "`curl -sS -o /dev/null -w '%{http_code} %{time_total}\\n' https://<공급자 호스트>/`",
                "FAILED RATE_LIMITED는 YES24가 초당 한도로 거절한 것(우리 트래픽이 몰림, 공급자 장애 아님). "
                "'서킷 열림'이 안 왔다고 공급자가 멀쩡한 건 아니다 — YES24는 4xx·429를 서킷 실패로 세지 않는다",
            ]),
        Rule(
            uid="cb-client-slow-burn", tier=INFO, title="외부 호출 느림",
            expr=ratio_rule_expr(_client_counts("slow"), _client_counts("total"), "client_name",
                                 host_mins["client_slow_fast"], ratio("client_slow_fast"), "1h", "5m")
                 + "\n or "
                 + ratio_rule_expr(_client_counts("slow"), _client_counts("total"), "client_name",
                                   host_mins["client_slow_slow"], ratio("client_slow_slow"), "6h", "30m"),
            summary="{{ $labels.client_name }} 호출 중 1초 넘는 비율이 높습니다(" + PCT + ")",
            runbook=[
                "분포: Explore `histogram_quantile(0.99, sum by (le) (rate(http_client_requests_seconds_bucket"
                "{client_name=\"{{ $labels.client_name }}\"}[30m])))`",
                "실패도 같이 늘었는지: 밀리는 '검색 섹션 실패 소진율', 정보나루는 '공공도서관 확인 못 함 비율' 알림 확인"
                "(새 화면은 통합 검색의 도서관 칸이 늘 SKIPPED라 섹션 실패에 안 잡힌다). 공급자 쪽이면 대응은 기다림",
            ],
            lazy_metrics=("http_client_requests_seconds_count", "http_client_requests_seconds_bucket")),
        Rule(
            uid="cb-server-5xx", tier=INFO, title="서버 5xx 발생",
            # increase()만 본다. 5xx 시리즈는 Micrometer가 상태 코드 태그로 첫 응답 때 만들어서(앱에서 미리 못 만듦)
            # 프로세스당 (경로·상태)별 첫 5xx는 이 규칙이 못 센다 — 예상 못 한 500은 GlobalExceptionHandler가 ERROR로 찍어
            # 'ERROR 로그' 규칙(같은 낮 등급)이 첫 건부터 잡는다. ERROR 없이 나가는 5xx는 503 예산 소진(차단선 알림이 즉시로
            # 잡음), 전자도서관 업체 해석 실패(코드 버그), 카카오 후보 검색 실패 503(WARN, PR #84 — 장애가 이어지면 둘째부터
            # 잡히고 '서버 5xx 소진율'도 본다)이다. 첫 시리즈를 식으로 추측하던 방식은 수집 공백·끊김 표식·
            # 긴 수집 실패마다 옛 누적값을 새 사건으로 세는 구멍이 나와 뺐다(PR-B 결정 카드, 리뷰 3회)
            expr=f"sum (increase(http_server_requests_seconds_count{{{five_xx}}}[5m])) > 0",
            summary="사용자 API에서 5xx가 났습니다",
            runbook=[
                "경로: Explore `sum by (uri, status) (increase(http_server_requests_seconds_count{" + five_xx + "}[1h]))`",
                "원인 로그: " + APP_LOGS + " | grep -A20 -E ' ERROR |카카오 도서 검색'",
            ],
            lazy_metrics=("http_server_requests_seconds_count",)),
        Rule(
            uid="cb-error-log", tier=INFO, title="ERROR 로그",
            datasource="loki",
            expr='sum (count_over_time({job="checkbook/journal", level="ERROR"}[5m]))',
            summary="ERROR 로그가 " + COUNT + "줄 찍혔습니다(앱 또는 커널)",
            runbook=[
                'Explore(로그): `{job="checkbook/journal", level="ERROR"}`',
                "커널 줄(OOM 등)이면 `sudo journalctl -k -p err --since '-1 hour'`",
            ]),
        Rule(
            uid="cb-circuit-open", tier=INFO, title="서킷 열림",
            # 상태 게이지는 열림(30초)이 수집 주기(60초)보다 짧아 놓친다 — 열려서 실제로 막은 호출을 센다
            expr=increase("resilience4j_circuitbreaker_not_permitted_calls_total", APP, "10m", by="name") + " > 0",
            summary="{{ $labels.name }} 서킷이 열려 호출을 막았습니다(10분 " + COUNT + "회)",
            runbook=[
                "실패율: Explore `max by (name) (resilience4j_circuitbreaker_failure_rate)`",
                "로그: " + APP_LOGS + " | grep -iE 'circuit|폴백|서킷'",
                "공급자 장애면 대응은 기다림. 열린 동안 그 칸은 바로 실패로 나간다(응답은 빨라지지만 결과는 실패)",
                "열린 지 30초가 지난 뒤 들어온 요청부터 시험 호출(3번)로 다시 확인한다 — 공급자가 나아져도 그 전엔 계속 "
                "실패로 보이고, 요청이 없으면 열린 채로 있다",
            ]),
        Rule(
            uid="cb-budget-80", tier=INFO, title="공급자 하루 예산 80%",
            expr=(f"max by (provider) (external_budget_used{{{APP}}})\n"
                  f">= on (provider) 0.8 * max by (provider) (external_budget_limit{{{APP}, kind=\"daily\"}})"),
            summary="{{ $labels.provider }} 오늘 호출이 하루 한도의 80%를 넘었습니다",
            runbook=["남용인지 먼저: '공급자 하루 예산 차단선 도달'의 확인 순서와 같음"]),
        Rule(
            uid="cb-featured-refresh-failed", tier=INFO, title="featured 갱신 실패",
            expr=increase("featured_refresh_total", f'{APP}, result="failure"', "1h", by="type") + " > 0",
            summary="featured {{ $labels.type }} 갱신이 실패했습니다 — 다음 cron까지 지난 목록이 나갑니다",
            runbook=["로그: " + APP_LOGS.replace("-1 hour", "-2 hour") + " | grep -A10 'featured'"]),
        Rule(
            uid="cb-snapshot-persist-failed", tier=INFO, title="도서관 스냅샷 저장 실패",
            # 저장이 실패해도 사용자는 정보나루에서 받은 결과를 본다(빠지는 건 다음 조회의 캐시뿐)라 낮 등급.
            # 같은 책 동시 첫 조회는 ON CONFLICT로 없앴으니 평소엔 0이어야 한다 — 한 건이라도 나면 DB 쪽 문제다
            expr=increase("library_snapshot_persist_total", f'{APP}, result="failure"', "10m") + " > 0",
            summary="도서관 스냅샷 저장이 10분 동안 " + COUNT + "번 실패했습니다 — 결과는 나갔지만 캐시가 쌓이지 않아 정보나루를 더 부릅니다",
            runbook=[
                "로그: " + APP_LOGS + " | grep -A20 '스냅샷 저장 실패'",
                "DB 연결·커넥션 풀 대기: Explore `max (hikaricp_connections_pending)`",
                # 저장 실패는 서킷을 열지 않는다(서킷은 정보나루 건강만 잰다) — 막아 주는 건 하루 예산 차단선뿐
                "계속되면 캐시가 안 쌓여 같은 책도 매번 정보나루를 부른다 → 예산: Explore "
                "`max by (provider) (external_budget_used{provider=\"datanaru\"})`",
            ]),
        Rule(
            uid="cb-featured-expired", tier=INFO, title="featured 만료 1시간 넘음",
            # 갱신이 안 돌았거나(배포·다운으로 04:00을 놓침) 계속 실패하면 만료 시각이 지나간다
            expr=f"time() - max by (type) (featured_snapshot_expiry_seconds{{{APP}}}) > 3600",
            for_="10m",
            summary="featured {{ $labels.type }}이 만료된 지 1시간이 넘었습니다(갱신 멈춤)",
            runbook=[
                "갱신 실패 알림이 있었는지, 04:00에 배포·다운이 있었는지 확인",
                "로그: " + APP_LOGS.replace("-1 hour", "-1 day") + " | grep featured",
            ]),
        Rule(
            uid="cb-featured-never-fetched", tier=INFO, title="featured 받은 적 없음",
            # NaN은 NaN과 같지 않다 — 나이가 NaN(한 번도 받은 적 없음)인 시리즈만 남는다. 기동 직후 1분은 정상 NaN
            expr=(f"count by (type) (featured_snapshot_age_seconds{{{APP}}} "
                  f"!= featured_snapshot_age_seconds{{{APP}}})"),
            for_="30m",
            summary="featured {{ $labels.type }}을 한 번도 받지 못했습니다(빈 섹션)",
            runbook=["시드 행만 있고 첫 갱신이 안 됨 — 로그에서 featured 갱신 기록 확인: " + APP_LOGS.replace("-1 hour", "-1 day")]),
        # ---------- 공공도서관 배치(D7-1, 결정 notes/decisions/2026-11-03-D7-1-알림규칙-결정.md) ----------
        # 새 화면은 통합 검색에 좌표를 보내지 않아 PUBLIC_LIBRARY 섹션이 늘 SKIPPED다 — 위 섹션 소진율이 도서관 실패를
        # 더는 보지 못해 배치 응답(library.batch.items)으로 대신 본다. 서킷·예산·느림은 기존 규칙이 이미 잡는다.
        # 문턱은 모두 판단값(공개 전이라 기준선 없음) — 공개 뒤 실제 값을 보고 고친다
        Rule(
            uid="cb-library-unknown-ratio", tier=INFO, title="공공도서관 확인 못 함 비율",
            # 20%: 평소 ≈0%, 5곳 중 1곳꼴로 '확인 못 함'이면 사용자가 체감한다. 1시간 20곳 이상: 몇 건뿐일 때의 오탐 방지.
            # 짧은 창 5분: 지금도 계속되는지(SRE Workbook, 다른 비율 규칙과 같은 모양)
            expr=ratio_rule_expr(_library_item_counts('state="UNKNOWN"'), _library_item_counts(""),
                                 "", {"": 20}, 0.2, "1h", "5m"),
            summary="공공도서관 배치에서 확인 못 한 기관 비율이 높습니다(" + PCT + ") — 지도·목록에 '확인 못 함'이 많이 보입니다",
            runbook=[
                "원인별: Explore `sum by (cause) (increase(library_snapshot_refresh_failure_total[1h]))` "
                "— CIRCUIT_OPEN·DAILY_LIMIT이면 '서킷 열림'·'예산' 알림과 같은 사건",
                "중단 사유별: Explore `sum by (stop) (increase(library_batch_seconds_count[1h]))` — POOL_BUSY·BATCH_DEADLINE이면 우리 쪽 혼잡",
                "로그: " + APP_LOGS + " | grep -E '정보나루 조회 실패|공공도서관 배치'",
            ]),
        Rule(
            uid="cb-datanaru-invalid-response", tier=INFO, title="정보나루 응답 형식 오류",
            # 5건/1시간: 한 도서관의 이상한 응답 1~2건은 넘기고, 형식이 통째로 바뀌면 금방 수십 건이 된다.
            # 이 실패는 서킷도 열지 않고(응답은 옴) 캐시도 안 해(틀린 값을 저장하지 않음) 다른 알림에 안 잡힌다
            expr=increase("library_snapshot_refresh_failure_total", f'{APP}, cause="INVALID_RESPONSE"', "1h") + " >= 5",
            summary="정보나루 소장 응답이 1시간에 " + COUNT + "번 형식이 틀렸습니다(값 비었거나 Y/N 아님) — 그 기관들은 '확인 못 함'으로 보이고 매번 다시 부릅니다",
            runbook=[
                "로그: " + APP_LOGS + " | grep '형식 오류' | head — 같은 libCode 반복인지(한 기관) 여러 기관인지(형식 변경)",
                "여러 기관이면 정보나루 bookExist 응답 필드 변경 의심 — DatanaruClient.bookExist 파싱 확인",
                "반복 호출로 예산이 줄 수 있다: Explore `max by (provider) (external_budget_used{provider=\"datanaru\"})`",
            ]),
        Rule(
            uid="cb-library-pool-busy", tier=INFO, title="공공도서관 배치 서버 혼잡",
            # 1건/10분: 공공도서관 풀(40·큐 200)이 거절하는 건 평소 0이어야 한다. 부하 측정 중에는 무음 처리
            expr=increase("library_batch_seconds_count", f'{APP}, stop="POOL_BUSY"', "10m") + " > 0",
            summary="공공도서관 배치가 10분 동안 " + COUNT + "번 풀 거절로 멈췄습니다 — 서버가 바빠 '확인 못 함'이 납니다",
            runbook=[
                "동시 요청량: Explore `sum (rate(library_batch_seconds_count[5m]))`, 서버 요청: `sum by (uri) (rate(http_server_requests_seconds_count[5m]))`",
                "풀 상태: Explore `executor_active_threads{name=\"publicLibraryExecutor\"}`·`executor_queued_tasks` (이름은 지표에서 확인)",
                "부하 측정 중이면 정상 — 측정 단계에서 풀·큐 크기를 다시 정한다(application.yaml public-library.executor-*)",
            ]),
        Rule(
            uid="cb-elibrary-vendor-changed", tier=INFO, title="전자도서관 업체 구조 변경 의심",
            # 판단값(기준선 없음): 업체 템플릿이 바뀌면 그 업체 사이트가 한꺼번에 깨져 거의 100%가 된다
            expr=ratio_rule_expr(
                _elibrary_counts('outcome=~"structure_changed|parse_failed|endpoint_not_found"'),
                _elibrary_counts('outcome!~"daily_limit|rejected"'),
                "vendor", {"kyobo": 14, "bookcube": 14}, 0.5, "1h"),
            summary="{{ $labels.vendor }} 전자도서관 수집의 절반 넘게 구조 변경·파싱 실패입니다(" + PCT + ")",
            runbook=[
                "한 사이트 반복인지: Explore(로그) `" + LOKI_APP + ' |= "전자도서관 검색 실패" '
                '|~ "outcome=(structure_changed|parse_failed|endpoint_not_found)"` 에서 id가 하나뿐인지',
                "여러 사이트면 업체 템플릿 변경 — 해당 클라이언트(Kyobo·BookcubeELibClient) 선택자 수정 필요",
            ]),
        # 전자도서관 주간 계약 확인(결정 2026-10-21): 월 09:17에 한 번 돌고 결과 카운터가 1 오른다.
        # 위 규칙은 사용자 검색이 있어야 판단하지만, 이것은 검색이 없어도 매주 무작위 3곳을 직접 본다
        Rule(
            uid="cb-elibrary-contract-failed", tier=INFO, title="전자도서관 계약 확인 실패",
            expr=increase("elibrary_contract_check_total", f'{APP}, result=~"vendor_failure|undetermined"', "1h",
                          by="result") + " > 0",
            summary="교보 전자도서관 주간 확인 결과 {{ $labels.result }} — vendor_failure는 다시 뽑은 사이트까지 과반이 실패"
                    "(템플릿 변경 또는 공통 장애), undetermined는 검사할 사이트를 못 찾음",
            runbook=[
                "사이트별 판정: Explore(로그) `" + LOKI_APP + ' |= "전자도서관 계약 확인"`',
                "틀 변경 의심(TEMPLATE_SUSPECT)이 많으면 브라우저로 그 사이트 검색 화면을 열어 KyoboELibClient 선택자와 비교",
                "닿지 않음(UNREACHABLE)이 많으면 우리 서버 네트워크·DNS부터: 서버에서 `curl -sI <사이트 주소>`",
                "undetermined면 로그의 robots 막힘·상한 수를 본다(robots_disallowed 값을 채웠는지)",
            ]),
        Rule(
            uid="cb-elibrary-site-broken", tier=INFO, title="전자도서관 사이트 고장 후보",
            expr=increase("elibrary_contract_site_failed_total", APP, "1h") + " > 0",
            summary="주간 확인에서 전자도서관 사이트 " + COUNT + "곳이 실패했습니다(업체 공통 장애는 아님) — 정리할지 확인",
            runbook=[
                "사이트 id·이름·이유: Explore(로그) `" + LOKI_APP + ' |= "전자도서관 사이트 고장 후보"`',
                "브라우저로 열어 본다: 닫혔으면 `UPDATE elibrary SET status = 'INACTIVE' WHERE id = <id>;`, "
                "주소만 바뀌었으면 base_url 수정, 점검·일시 오류면 그대로",
            ]),
        Rule(
            uid="cb-elibrary-contract-stale", tier=INFO, title="전자도서관 계약 확인 멈춤",
            # 지표가 7~8일 전에도 있었을 때만 본다 — 배포 직후 첫 월요일 전에는 증가가 0이라 바로 울리기 때문.
            # 재시작해도 카운터는 기동 때 0으로 다시 생겨 increase()는 실제로 돈 횟수만 센다
            expr=(f"sum (increase(elibrary_contract_check_total{{{APP}}}[8d]))\n"
                  f"and on () count (max_over_time(elibrary_contract_check_total{{{APP}}}[1d] offset 7d)) > 0"),
            op="lt", value=1,
            summary="전자도서관 주간 계약 확인이 8일 넘게 돌지 않았습니다",
            runbook=[
                "로그: " + APP_LOGS.replace("-1 hour", "-8 day") + " | grep '전자도서관 계약 확인'",
                "월 09:17에 앱이 떠 있었는지(배포·다운), elibrary.contract-check.enabled가 켜져 있는지",
            ]),
        # 공공도서관 목록 동기화(2026-11-03): 기동 150초 뒤와 월 04:40에 돌고 결과 카운터가 1 오른다.
        # 기동 실행을 늦추는 건 첫 수집(60초 간격)이 0을 먼저 봐야 increase()가 그 실행을 세기 때문이다.
        # 막히면 정보나루에서 빠진 도서관이 주변 후보로 남아 '확인 못 함'이 되고, 새 도서관은 묻지 않는다
        Rule(
            uid="cb-library-list-sync-failed", tier=INFO, title="공공도서관 목록 동기화 중단",
            expr=increase("public_library_sync_total", f'{APP}, result!="success"', "1h", by="result") + " > 0",
            summary="공공도서관 목록 동기화가 {{ $labels.result }}로 끝나 목록을 바꾸지 않았습니다 — "
                    "too_many_removed는 빠질 도서관이 설정 비율(max-removal-ratio)을 넘음, "
                    "incomplete는 받은 목록이 numFound와 맞지 않음, failed는 호출 실패·오류 응답",
            runbook=[
                "사유·건수: Explore(로그) `" + LOKI_APP + ' |= "공공도서관 목록 동기화"`',
                "too_many_removed면 정보나루 참여도서관 목록(사이트의 엑셀)과 건수를 비교한다 — 정말 많이 빠졌으면 "
                "application.yaml public-library.list-sync.max-removal-ratio를 잠시 올려 배포하고 되돌린다",
                "incomplete면 로그의 received·고유 코드를 본다: received < numFound면 덜 받음(일시 오류), "
                "received는 맞는데 고유 코드가 모자라면 정보나루가 중복·코드 없는 항목을 준 것(이어지면 허용치를 코드로 정해야 함)",
                "incomplete·failed가 한 번이면 다음 실행(배포 또는 월 04:40)을 기다린다. 이어지면 정보나루 공지·상태 확인",
            ]),
        Rule(
            uid="cb-library-list-sync-stale", tier=INFO, title="공공도서관 목록 동기화 멈춤",
            # 계약 확인 멈춤과 같은 모양 — 지표가 7~8일 전에도 있었을 때만 본다. 성공만 센다(실패가 이어져도 울린다)
            expr=(f'sum (increase(public_library_sync_total{{{APP}, result="success"}}[8d]))\n'
                  f"and on () count (max_over_time(public_library_sync_total{{{APP}}}[1d] offset 7d)) > 0"),
            op="lt", value=1,
            summary="공공도서관 목록 동기화가 8일 넘게 성공하지 않았습니다 — 정보나루에서 빠진 도서관이 후보로 남을 수 있습니다",
            runbook=[
                "로그: " + APP_LOGS.replace("-1 hour", "-8 day") + " | grep '공공도서관 목록 동기화'",
                "public-library.list-sync.enabled가 켜져 있는지, 월 04:40에 앱이 떠 있었는지",
            ]),
        Rule(
            uid="cb-disk-filling", tier=INFO, title="디스크가 하루 안에 찰 것으로 보임",
            expr=_disk_used_above(40, fill_within_hours=24),
            for_="1h",
            summary="{{ $labels.mountpoint }} 여유 40% 미만이고 지난 6시간 추세로 하루 안에 찹니다",
            runbook=["`df -h`, `sudo docker system df`, `journalctl --disk-usage`"]),
        Rule(
            uid="cb-memory-high", tier=INFO, title="서버 메모리 90%",
            expr=(f"100 * (1 - node_memory_MemAvailable_bytes{{{NODE}}} / node_memory_MemTotal_bytes{{{NODE}}}) > 90"),
            for_="15m",
            summary="서버 메모리 사용이 15분 넘게 90%를 넘었습니다",
            runbook=["`free -h`, `sudo docker stats --no-stream`, JVM 힙: Explore `jvm_memory_used_bytes{area=\"heap\"}`"]),
    ]
    rules.append(_metrics_missing_rule(rules))
    return rules


def metric_names(expr):
    """식에서 '이름{' 꼴의 지표 이름. 함수는 '이름(' 꼴이라 걸리지 않는다"""
    return sorted(set(re.findall(r"\b([a-zA-Z_:][a-zA-Z0-9_:]*)\{", expr)))


def _metrics_missing_rule(rules):
    """규칙들이 보는 앱 지표가 사라지면 알린다. 비율·사건 규칙은 조건을 넘지 않으면 '데이터 없음 = 정상'이라
    지표 이름이 바뀌어도 조용히 정상으로 보인다. 앱이 살아 있을 때만 본다(죽었으면 '앱 지표 끊김'이 온다).
    평소에도 없을 수 있는 지표(lazy_metrics)는 못 본다. 요청 제한을 끈 프로필(부하 측정)은 한도 게이지가 없어
    울리지만 그 프로필은 운영에서 수집하지 않는다"""
    lazy = {m for r in rules for m in r.lazy_metrics}
    watched = sorted({m for r in rules if r.datasource == "prom" for m in metric_names(r.expr)
                      if m not in lazy and m != "up" and not m.startswith("node_")})
    # absent() 결과는 모두 {job="checkbook-app"}라 or가 하나로 합친다 — 지표마다 metric 라벨을 달아 따로 남긴다
    absent = "\n or ".join(f'label_replace(absent({m}{{{APP}}}), "metric", "{m}", "", "")' for m in watched)
    return Rule(
        uid="cb-app-metrics-missing", tier=INFO, title="알림이 보는 지표가 사라짐",
        expr=f"(\n {absent}\n)\nand on () (max (up{{{APP}}}) == 1)",
        for_="15m",
        summary="알림 규칙이 보는 지표 {{ $labels.metric }}가 앱에서 사라졌습니다 — 그 지표를 보는 규칙은 지금 아무것도 감지하지 못합니다",
        runbook=["지표 이름·태그를 바꾼 배포가 있었는지 확인하고 deploy/grafana/rules.py를 맞춘 뒤 다시 반영"])


def _annotations(rule):
    return {
        "summary": rule.summary,
        "description": "확인할 것:\n" + "\n".join(f"{i}. {line}" for i, line in enumerate(rule.runbook, 1)),
    }


def _query_model(rule):
    if rule.datasource == "loki":
        return {"refId": "A", "expr": rule.expr, "queryType": "instant"}
    return {"refId": "A", "expr": rule.expr, "instant": True, "range": False}


def grafana_rule(rule, folder_uid, datasource_uids):
    tier = TIERS[rule.tier]
    return {
        "uid": rule.uid,
        "title": rule.title,
        "folderUID": folder_uid,
        "ruleGroup": tier["group"],
        "condition": "B",
        "data": [
            {
                "refId": "A",
                "datasourceUid": datasource_uids[rule.datasource],
                # 즉시 질의라 창은 식 안의 [1h] 등이 정한다. 이 범위는 Grafana가 요구해서 넣는 값
                "relativeTimeRange": {"from": 600, "to": 0},
                "model": _query_model(rule),
            },
            {
                "refId": "B",
                "datasourceUid": "__expr__",
                "model": {
                    "refId": "B",
                    "type": "threshold",
                    "expression": "A",
                    "conditions": [{"evaluator": {"type": rule.op, "params": [rule.value]}}],
                },
            },
        ],
        "noDataState": rule.no_data,
        # 질의가 깨지면(문법·데이터 소스 오류) 조용히 넘어가지 않고 알림으로 온다
        "execErrState": "Error",
        "for": rule.for_,
        "annotations": _annotations(rule),
        "labels": {"tier": rule.tier},
        "isPaused": False,
        "notification_settings": {
            "receiver": tier["receiver"],
            "group_wait": "30s",
            "group_interval": "5m",
            "repeat_interval": tier["repeat"],
        },
    }


def grafana_groups(folder_uid, datasource_uids, rules=None):
    """{묶음 이름: provisioning API 'PUT rule-groups' 본문}"""
    rules = build_rules() if rules is None else rules
    groups = {}
    for tier in (URGENT, INFO):
        name = TIERS[tier]["group"]
        groups[name] = {
            "title": name,
            "interval": EVAL_INTERVAL_SECONDS,
            "rules": [grafana_rule(r, folder_uid, datasource_uids) for r in rules if r.tier == tier],
        }
    return groups


_PROMQL_OPS = {"gt": ">", "lt": "<"}


def promtool_rules(rules=None):
    """같은 식 + 조건 B로 Prometheus 규칙 파일. LogQL 규칙은 promtool이 못 돌려 뺀다(Explore에서 확인)"""
    rules = build_rules() if rules is None else rules
    return {"groups": [{
        "name": "checkbook",
        "interval": f"{EVAL_INTERVAL_SECONDS}s",
        "rules": [{
            "alert": r.uid,
            "expr": f"({r.expr}) {_PROMQL_OPS[r.op]} {r.value:g}",
            "for": r.for_,
            "labels": {"tier": r.tier},
        } for r in rules if r.datasource == "prom"],
    }]}


def main(argv):
    if len(argv) == 2 and argv[1] == "--list":
        for r in build_rules():
            print(f"{r.tier:6s} {r.uid:32s} {r.title}")
        return 0
    if len(argv) == 3 and argv[1] == "--promtool":
        with open(argv[2], "w", encoding="utf-8") as f:
            json.dump(promtool_rules(), f, ensure_ascii=False, indent=2)
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
