"""반영 스크립트 시험 — 가짜 Grafana(http.server)로 정상·실패 경로를 본다. 실제 Grafana에는 닿지 않는다"""
import importlib.util
import io
import json
import os
import re
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
_spec = importlib.util.spec_from_file_location("apply_alerts", os.path.join(HERE, "..", "apply-alerts.py"))
apply_alerts = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(apply_alerts)

TOKEN = "glsa_test-token-must-never-be-printed"
FOLDER = "folder-1"


class FakeGrafana:
    """provisioning API 중 쓰는 것만. 규칙을 uid로 들고, 묶음 GET은 그 묶음의 규칙을 모아 돌려준다"""

    def __init__(self):
        self.rules = {}        # uid → 규칙
        self.intervals = {}    # 묶음 → 평가 주기
        self.writes = []       # (메서드, 경로)
        self.put_bodies = []   # PUT으로 받은 묶음 본문
        self.status_for = {}   # (메서드, 경로 앞부분) → 강제로 돌려줄 상태 코드
        self.drop_on_put = set()   # PUT 때 조용히 빼먹을 uid(반영 뒤 확인 시험)
        self.redirect_to = None    # 설정하면 GET을 그 주소로 302
        self.seen_auth = []        # 받은 Authorization 헤더(리다이렉트로 토큰이 다른 곳에 가는지 시험)
        # 묶음 PUT이 원본에 없는 규칙을 지우는지는 문서에 없다 — 가짜는 남겨 두는 쪽(나쁜 경우)으로 둔다

    def start(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _reply(self, code, body=None):
                data = b"" if body is None else json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def _handle(self, method):
                fake.seen_auth.append(self.headers.get("Authorization"))
                if self.headers.get("Authorization") != f"Bearer {TOKEN}":
                    return self._reply(401, {"message": "invalid API key"})
                if fake.redirect_to and method == "GET":
                    self.send_response(302)
                    self.send_header("Location", fake.redirect_to + self.path)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                for (m, prefix), code in fake.status_for.items():
                    if m == method and self.path.startswith(prefix):
                        return self._reply(code, {"message": "invalid query: parse error"})
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length)) if length else None
                group = re.match(r"/api/v1/provisioning/folder/([^/]+)/rule-groups/([^/]+)$", self.path)
                rule = re.match(r"/api/v1/provisioning/alert-rules/([^/]+)$", self.path)
                if method != "GET":
                    fake.writes.append((method, self.path))
                if group and method == "GET":
                    name = group.group(2)
                    found = [dict(r, id=1, provenance="api", updated="x") for r in fake.rules.values()
                             if r["ruleGroup"] == name]
                    if not found and name not in fake.intervals:
                        return self._reply(404, {"message": "rule group does not exist"})
                    return self._reply(200, {"title": name, "folderUid": group.group(1),
                                             "interval": fake.intervals.get(name), "rules": found})
                if group and method == "PUT":
                    name = group.group(2)
                    # 다른 묶음에 같은 uid가 있으면 거절하는 쪽(엄격한 경우)으로 흉내 낸다
                    elsewhere = [r["uid"] for r in body["rules"]
                                 if r["uid"] in fake.rules and fake.rules[r["uid"]]["ruleGroup"] != name]
                    if elsewhere:
                        return self._reply(400, {"message": f"rule uid already exists in another group: {elsewhere}"})
                    fake.put_bodies.append(body)
                    fake.intervals[name] = body["interval"]
                    for r in body["rules"]:
                        if r["uid"] not in fake.drop_on_put:
                            # Grafana는 for를 '3m0s'처럼 돌려줄 수 있다 — 표기가 달라도 같은 값으로 봐야 한다
                            fake.rules[r["uid"]] = dict(r, **{"for": r["for"].replace("m", "m0s")
                                                              if r["for"].endswith("m") else r["for"]})
                    return self._reply(200, body)
                if rule and method == "DELETE":
                    fake.rules.pop(rule.group(1), None)
                    return self._reply(204)
                return self._reply(404, {"message": "not found"})

            def do_GET(self):
                self._handle("GET")

            def do_PUT(self):
                self._handle("PUT")

            def do_DELETE(self):
                self._handle("DELETE")

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


class ApplyTest(unittest.TestCase):

    def setUp(self):
        self.fake = FakeGrafana()
        url = self.fake.start()
        self.addCleanup(self.fake.stop)
        self.env = tempfile.NamedTemporaryFile("w", suffix=".env", delete=False, encoding="utf-8")
        self.env.write(f"OTHER_KEY=ignored\nGRAFANA_URL={url}\nGRAFANA_ALERTS_TOKEN='{TOKEN}'\n"
                       f"GRAFANA_ALERTS_FOLDER_UID={FOLDER}\nGRAFANA_PROM_UID=prom\nGRAFANA_LOKI_UID=loki\n")
        self.env.close()
        self.addCleanup(os.unlink, self.env.name)

    def run_main(self, *args):
        out, err = io.StringIO(), io.StringIO()
        code = apply_alerts.main(["--env-file", self.env.name, *args], out=out, err=err, sleep=lambda s: None)
        text = out.getvalue() + err.getvalue()
        self.assertNotIn(TOKEN, text, "토큰이 출력에 나오면 안 된다")
        return code, out.getvalue(), err.getvalue()

    def expected_uids(self):
        return {r.uid for r in apply_alerts.rules.build_rules()}

    def test_처음_반영하면_규칙이_다_들어가고_다시_읽어_확인한다(self):
        code, out, _ = self.run_main("--yes")
        self.assertEqual(code, 0)
        self.assertEqual(set(self.fake.rules), self.expected_uids())
        self.assertIn("반영 완료", out)

    def test_다시_반영하면_변경_없음이고_쓰지_않는다(self):
        self.run_main("--yes")
        self.fake.writes.clear()
        code, out, _ = self.run_main("--yes")
        self.assertEqual(code, 0)
        self.assertEqual(self.fake.writes, [])
        self.assertIn("반영할 것 없음", out)

    def test_diff와_yes_없는_실행은_쓰지_않는다(self):
        for args in (["--diff"], []):
            code, out, _ = self.run_main(*args)
            self.assertEqual(code, 0)
            self.assertEqual(self.fake.writes, [], args)
            self.assertIn("+ cb-app-down", out)

    def test_원본에서_빠진_규칙은_지운다(self):
        self.run_main("--yes")
        old = dict(self.fake.rules["cb-memory-high"], uid="cb-removed-rule")
        self.fake.rules["cb-removed-rule"] = old
        code, out, _ = self.run_main("--yes")
        self.assertEqual(code, 0)
        self.assertIn("- cb-removed-rule", out)
        self.assertNotIn("cb-removed-rule", self.fake.rules)

    def test_등급을_옮긴_규칙은_먼저_지우고_새_묶음에_넣는다(self):
        self.run_main("--yes")
        # cb-memory-high가 즉시 묶음에 잘못 들어가 있는 상태(예: 등급을 낮으로 옮긴 직후)
        self.fake.rules["cb-memory-high"]["ruleGroup"] = "checkbook-urgent"
        code, _, err = self.run_main("--yes")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.fake.rules["cb-memory-high"]["ruleGroup"], "checkbook-info")

    def test_두_번째_묶음에서_실패하면_이미_반영된_묶음을_알린다(self):
        self.fake.status_for[("PUT", f"/api/v1/provisioning/folder/{FOLDER}/rule-groups/checkbook-info")] = 400
        code, _, err = self.run_main("--yes")
        self.assertEqual(code, 1)
        self.assertIn("이미 반영된 묶음: ['checkbook-urgent']", err)
        self.assertIn("먼저 지운 규칙: 없음", err)

    def test_https가_아니면_토큰을_보내지_않는다(self):
        self.fake_token_env(TOKEN, url="http://grafana.example.com")
        code, _, err = self.run_main("--diff")
        self.assertEqual(code, 1)
        self.assertIn("https", err)

    def test_리다이렉트를_따라가지_않아_토큰이_다른_곳으로_가지_않는다(self):
        other = FakeGrafana()
        other_url = other.start()
        self.addCleanup(other.stop)
        self.fake.redirect_to = other_url
        code, _, err = self.run_main("--diff")
        self.assertEqual(code, 1)
        self.assertIn("리다이렉트", err)
        self.assertEqual(other.seen_auth, [])

    def test_env_값_뒤의_주석과_따옴표를_뗀다(self):
        self.assertEqual(apply_alerts._env_value("abc # 메모"), "abc")
        self.assertEqual(apply_alerts._env_value("'a # b'"), "a # b")
        self.assertEqual(apply_alerts._env_value('"x"'), "x")
        self.assertEqual(apply_alerts._env_value("a#b"), "a#b")
        self.assertEqual(apply_alerts._env_value("'tok' # it's mine"), "tok", "주석 속 따옴표가 값에 붙으면 안 된다")

    def test_Grafana에서_멈춘_규칙은_다시_켠다(self):
        self.run_main("--yes")
        self.fake.rules["cb-app-down"]["isPaused"] = True
        code, out, _ = self.run_main("--yes")
        self.assertEqual(code, 0)
        self.assertIn("~ cb-app-down: isPaused", out)
        self.assertFalse(self.fake.rules["cb-app-down"]["isPaused"])

    def test_바뀐_항목을_보여_준다(self):
        self.run_main("--yes")
        self.fake.rules["cb-app-down"]["noDataState"] = "OK"
        _, out, _ = self.run_main("--diff")
        self.assertIn("~ cb-app-down: noDataState", out)

    def test_토큰이_틀리면_권한_안내와_함께_실패(self):
        self.fake_token_env("wrong-token")
        code, _, err = self.run_main("--diff")
        self.assertEqual(code, 1)
        self.assertIn("HTTP 401", err)
        self.assertIn("fixed:alerting.provisioning:writer", err)
        self.assertNotIn("wrong-token", err)

    def test_잘못된_질의로_거절되면_Grafana_메시지와_함께_실패(self):
        self.fake.status_for[("PUT", "/api/v1/provisioning/folder/")] = 400
        code, _, err = self.run_main("--yes")
        self.assertEqual(code, 1)
        self.assertIn("HTTP 400", err)
        self.assertIn("invalid query", err)

    def test_반영_뒤_다시_읽어_다르면_실패(self):
        self.fake.drop_on_put.add("cb-disk-full")
        code, _, err = self.run_main("--yes")
        self.assertEqual(code, 1)
        self.assertIn("반영 뒤 확인 실패", err)
        self.assertIn("cb-disk-full", err)

    def test_설정이_빠지면_이름만_말하고_실패(self):
        with open(self.env.name, "w", encoding="utf-8") as f:
            f.write(f"GRAFANA_URL=http://127.0.0.1:1\nGRAFANA_ALERTS_TOKEN={TOKEN}\n")
        code, _, err = self.run_main("--diff")
        self.assertEqual(code, 1)
        self.assertIn("GRAFANA_ALERTS_FOLDER_UID", err)

    def test_연결이_안_되면_실패(self):
        self.fake.stop()
        self.fake.stop = lambda: None   # 정리 때 두 번 멈추지 않게
        code, _, err = self.run_main("--diff")
        self.assertEqual(code, 1)
        self.assertIn("연결 실패", err)

    def test_시험_알림은_두_연락처로_보내고_끝나면_지운다(self):
        code, _, _ = self.run_main("--fire-test")
        self.assertEqual(code, 0)
        (body,) = self.fake.put_bodies
        self.assertEqual(body["title"], apply_alerts.TEST_GROUP)
        self.assertEqual(sorted(r["notification_settings"]["receiver"] for r in body["rules"]),
                         ["slack-info", "slack-urgent"])
        self.assertTrue(all(r["ruleGroup"] == apply_alerts.TEST_GROUP for r in body["rules"]))
        self.assertEqual(self.fake.rules, {}, "시험 규칙이 남으면 안 된다")

    def test_시험_알림을_만들다_실패해도_지우기는_한다(self):
        self.fake.status_for[("PUT", "/api/v1/provisioning/folder/")] = 400
        code, _, _ = self.run_main("--fire-test")
        self.assertEqual(code, 1)
        self.assertEqual([m for m, _ in self.fake.writes].count("DELETE"), 2)

    def fake_token_env(self, token, url=None):
        url = url or f"http://127.0.0.1:{self.fake.server.server_address[1]}"
        with open(self.env.name, "w", encoding="utf-8") as f:
            f.write(f"GRAFANA_URL={url}\nGRAFANA_ALERTS_TOKEN={token}\nGRAFANA_ALERTS_FOLDER_UID={FOLDER}\n"
                    "GRAFANA_PROM_UID=prom\nGRAFANA_LOKI_UID=loki\n")


class NormalizeTest(unittest.TestCase):

    def test_기간_표기가_달라도_같은_값(self):
        self.assertEqual(apply_alerts._seconds("3m"), apply_alerts._seconds("3m0s"))
        self.assertEqual(apply_alerts._seconds("1h"), 3600)
        self.assertEqual(apply_alerts._seconds("0s"), 0)

    def test_Grafana가_일·주_단위로_돌려줘도_같은_값(self):
        # 실제 Grafana는 repeat_interval 24h를 '1d'로 돌려준다(10-20 첫 반영에서 확인)
        self.assertEqual(apply_alerts._seconds("1d"), apply_alerts._seconds("24h"))
        self.assertEqual(apply_alerts._seconds("1w"), 7 * 86400)
        self.assertEqual(apply_alerts._seconds("1d12h"), 36 * 3600)
        self.assertEqual(apply_alerts._seconds("1m30s"), 90)
        self.assertEqual(apply_alerts._seconds("500ms"), 0.5)


if __name__ == "__main__":
    unittest.main()
