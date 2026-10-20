#!/usr/bin/env python3
"""rules.py의 알림 규칙을 Grafana Cloud에 반영한다(provisioning API, PR-B 결정 카드 '카드 1').

내 PC(WSL)에서 실행한다. 서버 작업이 아니다.
  python3 deploy/grafana/apply-alerts.py --diff        지금 Grafana와 원본의 차이만 보여 준다(읽기만)
  python3 deploy/grafana/apply-alerts.py --yes         차이를 보여 주고 반영 → 다시 읽어 원본과 같은지 확인
  python3 deploy/grafana/apply-alerts.py --fire-test   Slack 두 채널로 시험 알림을 보내고 시험 규칙을 지운다

설정은 저장소 .env에서 아래 이름만 읽는다(값은 출력하지 않는다). 토큰은 서비스 계정 토큰,
역할 fixed:alerting.provisioning:writer 하나(지표·로그는 못 읽는다, 만료 2027-10-20).
  GRAFANA_URL, GRAFANA_ALERTS_TOKEN, GRAFANA_ALERTS_FOLDER_UID, GRAFANA_PROM_UID, GRAFANA_LOKI_UID

API로 넣은 규칙은 Grafana UI에서 고칠 수 없다(provenance). 고칠 때는 rules.py를 고치고 다시 반영한다 —
저장소가 유일한 원본이라 UI에서 고친 것과 어긋나는 일이 없다.
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rules  # noqa: E402

ENV_KEYS = ("GRAFANA_URL", "GRAFANA_ALERTS_TOKEN", "GRAFANA_ALERTS_FOLDER_UID", "GRAFANA_PROM_UID",
            "GRAFANA_LOKI_UID")
DEFAULT_ENV = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".env")
TEST_GROUP = "checkbook-fire-test"
# 평가 1분 + 첫 대기 30초 + 여유. 이만큼 기다린 뒤 시험 규칙을 지운다
FIRE_TEST_WAIT_SECONDS = 150


class ApplyError(Exception):
    pass


def read_env(path):
    """KEY=VALUE 줄에서 필요한 이름만 읽는다(셸로 source하지 않는다 — 값에 든 문자가 명령이 되지 않게)"""
    values = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                m = re.match(r"\s*(?:export\s+)?([A-Z_][A-Z0-9_]*)\s*=\s*(.*?)\s*$", line)
                if m and m.group(1) in ENV_KEYS:
                    values[m.group(1)] = _env_value(m.group(2))
    except FileNotFoundError:
        raise ApplyError(f"설정 파일이 없음: {path}")
    missing = [k for k in ENV_KEYS if not values.get(k)]
    if missing:
        raise ApplyError(f"{path}에 없는 값: {', '.join(missing)}")
    return values


def _env_value(raw):
    """따옴표로 감싼 값은 따옴표 안만, 아니면 ' #' 뒤 주석을 뗀다"""
    quoted = re.match(r"""^(['"])(.*?)\1(?:\s+#.*)?$""", raw)
    if quoted:
        return quoted.group(2)
    return re.split(r"\s+#", raw, maxsplit=1)[0].strip()


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """리다이렉트를 따라가지 않는다 — urllib은 따라갈 때 Authorization 헤더를 다른 호스트로도 그대로 보낸다"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Grafana:
    def __init__(self, url, token, timeout=15):
        parsed = urllib.parse.urlparse(url)
        # 토큰이 평문으로 나가지 않게 https만. 가짜 Grafana 시험용 로컬 주소만 예외
        if parsed.scheme != "https" and parsed.hostname not in ("127.0.0.1", "localhost"):
            raise ApplyError(f"GRAFANA_URL은 https여야 함: {parsed.scheme}://{parsed.hostname}")
        self.base = url.rstrip("/")
        self._token = token
        self.timeout = timeout
        self._opener = urllib.request.build_opener(_NoRedirect)

    def request(self, method, path, body=None, allow_404=False):
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self._token}")
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with self._opener.open(req, timeout=self.timeout) as resp:
                raw = resp.read()
        except urllib.error.HTTPError as e:
            if allow_404 and e.code == 404:
                return None
            if 300 <= e.code < 400:
                raise ApplyError(f"{method} {path}: HTTP {e.code} 리다이렉트는 따라가지 않음 — GRAFANA_URL 확인"
                                 f"(가리키는 곳: {e.headers.get('Location')})")
            detail = e.read().decode("utf-8", "replace")[:500]
            if e.code in (401, 403):
                raise ApplyError(f"{method} {path}: HTTP {e.code} — 토큰이 틀렸거나 만료됐거나 권한"
                                 f"(fixed:alerting.provisioning:writer)이 없음. 응답: {detail}")
            raise ApplyError(f"{method} {path}: HTTP {e.code} — {detail}")
        except urllib.error.URLError as e:
            raise ApplyError(f"{method} {path}: 연결 실패 — {e.reason}")
        return json.loads(raw) if raw.strip() else None

    def group_path(self, folder, group):
        return (f"/api/v1/provisioning/folder/{urllib.parse.quote(folder, safe='')}"
                f"/rule-groups/{urllib.parse.quote(group, safe='')}")

    def get_group(self, folder, group):
        return self.request("GET", self.group_path(folder, group), allow_404=True)

    def put_group(self, folder, group, body):
        return self.request("PUT", self.group_path(folder, group), body)

    def delete_rule(self, uid):
        return self.request("DELETE", f"/api/v1/provisioning/alert-rules/{urllib.parse.quote(uid, safe='')}",
                            allow_404=True)


_DURATION_UNITS = {"w": 7 * 86400, "d": 86400, "h": 3600, "m": 60, "s": 1, "ms": 0.001}


def _seconds(duration):
    """'1h30m'·'90s'·'1d' → 초. Grafana가 돌려줄 때 표기가 바뀌어도 같은 값으로 비교하려고
    (실제로 repeat_interval 24h를 '1d'로 돌려준다). 단위는 Prometheus 기간 표기(w·d·h·m·s·ms)"""
    if isinstance(duration, (int, float)):
        return duration
    total = 0
    # ms를 m보다 먼저 맞춘다('500ms'가 500분 + s로 읽히지 않게)
    for value, unit in re.findall(r"(\d+)(ms|[wdhms])", duration or "0s"):
        total += int(value) * _DURATION_UNITS[unit]
    return total


def normalize(rule):
    """비교할 항목만. Grafana가 덧붙이는 값(id·updated·provenance·기본 모델 필드)은 보지 않는다"""
    query, condition = (sorted(rule.get("data", []), key=lambda d: d.get("refId", "")) + [{}, {}])[:2]
    settings = rule.get("notification_settings") or {}
    return {
        "title": rule.get("title"),
        "condition": rule.get("condition"),
        "expr": (query.get("model") or {}).get("expr"),
        "datasource": query.get("datasourceUid"),
        "evaluator": ((condition.get("model") or {}).get("conditions") or [{}])[0].get("evaluator"),
        "for": _seconds(rule.get("for")),
        # 누가 Grafana에서 멈춰 둔 규칙을 '변경 없음'으로 넘기면 알림이 꺼진 줄 모른다
        "isPaused": bool(rule.get("isPaused", False)),
        "noDataState": rule.get("noDataState"),
        "execErrState": rule.get("execErrState"),
        "annotations": rule.get("annotations") or {},
        "labels": rule.get("labels") or {},
        "receiver": settings.get("receiver"),
        "repeat": _seconds(settings.get("repeat_interval")),
        "grouping": (_seconds(settings.get("group_wait")), _seconds(settings.get("group_interval"))),
    }


def diff_group(desired, current):
    """(추가 uid, 삭제 uid, {바뀐 uid: [바뀐 항목]})"""
    want = {r["uid"]: normalize(r) for r in desired["rules"]}
    have = {r["uid"]: normalize(r) for r in (current or {}).get("rules", [])}
    added = sorted(set(want) - set(have))
    removed = sorted(set(have) - set(want))
    changed = {}
    for uid in sorted(set(want) & set(have)):
        fields = [k for k in want[uid] if want[uid][k] != have[uid][k]]
        if fields:
            changed[uid] = fields
    interval_changed = current is not None and current.get("interval") != desired["interval"]
    return added, removed, changed, interval_changed


def print_diff(name, diff, out):
    added, removed, changed, interval_changed = diff
    if not (added or removed or changed or interval_changed):
        print(f"[{name}] 변경 없음", file=out)
        return False
    print(f"[{name}]", file=out)
    for uid in added:
        print(f"  + {uid}", file=out)
    for uid in removed:
        print(f"  - {uid} (원본에 없음 → 지움)", file=out)
    for uid, fields in changed.items():
        print(f"  ~ {uid}: {', '.join(fields)}", file=out)
    if interval_changed:
        print("  ~ 평가 주기", file=out)
    return True


def apply(grafana, folder, groups, write, out):
    pending = {}
    for name, desired in groups.items():
        diff = diff_group(desired, grafana.get_group(folder, name))
        if print_diff(name, diff, out):
            pending[name] = (desired, diff)
    if not pending:
        print("반영할 것 없음", file=out)
        return
    if not write:
        print("(--yes 없이 실행해 반영하지 않음)", file=out)
        return
    # 원본에서 빠졌거나 다른 묶음으로 옮긴(등급을 바꾼) 규칙을 먼저 지운다. 같은 uid가 다른 묶음에 남아 있으면
    # 옮겨 갈 묶음의 PUT이 거절될 수 있다(묶음 PUT이 빠진 규칙을 지우는지도 문서에 없다)
    deleted = []
    for name, (_, diff) in pending.items():
        for uid in diff[1]:
            grafana.delete_rule(uid)
            deleted.append(uid)
    applied = []
    try:
        for name, (desired, _) in pending.items():
            grafana.put_group(folder, name, desired)
            applied.append(name)
    except ApplyError as e:
        # 옮기려고 지운 규칙은 새 묶음에 들어가기 전까지 어디에도 없다(그동안 그 알림은 꺼져 있다)
        raise ApplyError(f"{e}\n  이미 반영된 묶음: {applied or '없음'}, 먼저 지운 규칙: {deleted or '없음'}. "
                         "원본은 그대로이니 원인을 고친 뒤 다시 실행하면 맞춰진다") from e
    verify(grafana, folder, groups)
    count = sum(len(g["rules"]) for g in groups.values())
    print(f"반영 완료: 규칙 {count}개, 다시 읽어 원본과 같음을 확인", file=out)


def verify(grafana, folder, groups):
    problems = []
    for name, desired in groups.items():
        added, removed, changed, interval_changed = diff_group(desired, grafana.get_group(folder, name))
        if added or removed or changed or interval_changed:
            problems.append(f"{name}: 빠짐 {added}, 남음 {removed}, 다름 {list(changed)}")
    if problems:
        raise ApplyError("반영 뒤 확인 실패 — Grafana의 규칙이 원본과 다름: " + "; ".join(problems))


def fire_test(grafana, folder, datasource_uids, out, wait_seconds=FIRE_TEST_WAIT_SECONDS, sleep=time.sleep):
    """늘 참인 규칙을 등급마다 하나씩 만들어 규칙 → 연락처 → Slack 전체 경로를 확인한다(Prometheus 지침:
    알림 경로는 끝까지 시험한다). 끝나면 지운다 — 실패해도 지우기는 한다"""
    test_rules = [
        rules.Rule(uid=f"cb-fire-test-{tier}", tier=tier, title=f"시험 알림({tier})", expr="vector(1)",
                   summary="PR-B 반영 확인용 시험 알림입니다. 무시하세요", runbook=["몇 분 뒤 해소 알림이 옵니다"])
        for tier in (rules.URGENT, rules.INFO)
    ]
    body = {
        "title": TEST_GROUP,
        "interval": rules.EVAL_INTERVAL_SECONDS,
        "rules": [dict(rules.grafana_rule(r, folder, datasource_uids), ruleGroup=TEST_GROUP) for r in test_rules],
    }
    try:
        grafana.put_group(folder, TEST_GROUP, body)
        print(f"시험 규칙 2개를 만들었습니다. {wait_seconds}초 안에 #checkbook-urgent·#checkbook-info에 "
              "알림이 오는지 보세요", file=out)
        sleep(wait_seconds)
    finally:
        for r in test_rules:
            grafana.delete_rule(r.uid)
        print("시험 규칙을 지웠습니다", file=out)


def main(argv=None, out=sys.stdout, err=sys.stderr, sleep=time.sleep):
    parser = argparse.ArgumentParser(description="CheckBook 알림 규칙 반영")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--diff", action="store_true", help="차이만 보여 준다(읽기만)")
    mode.add_argument("--yes", action="store_true", help="차이를 보여 주고 반영한다")
    mode.add_argument("--fire-test", action="store_true", help="Slack 시험 알림을 보내고 지운다")
    parser.add_argument("--env-file", default=DEFAULT_ENV)
    args = parser.parse_args(argv)
    try:
        env = read_env(args.env_file)
        grafana = Grafana(env["GRAFANA_URL"], env["GRAFANA_ALERTS_TOKEN"])
        folder = env["GRAFANA_ALERTS_FOLDER_UID"]
        datasource_uids = {"prom": env["GRAFANA_PROM_UID"], "loki": env["GRAFANA_LOKI_UID"]}
        if args.fire_test:
            fire_test(grafana, folder, datasource_uids, out, sleep=sleep)
        else:
            apply(grafana, folder, rules.grafana_groups(folder, datasource_uids), write=args.yes, out=out)
    except ApplyError as e:
        print(f"실패: {e}", file=err)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
